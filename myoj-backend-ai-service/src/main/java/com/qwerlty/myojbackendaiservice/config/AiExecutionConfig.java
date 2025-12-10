package com.qwerlty.myojbackendaiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AiExecutionConfig {

    @Bean(destroyMethod = "shutdown", name = "aiAnalysisExecutor")
    public ExecutorService aiAnalysisExecutor(
            @Value("${myoj.ai.stream.concurrency:2}") int concurrency) {
        int poolSize = Math.max(1, concurrency);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(poolSize * 2),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-analysis-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown", name = "problemGenerationPublicExecutor")
    public ExecutorService problemGenerationPublicExecutor(
            @Value("${myoj.ai.generation.public-concurrency:${myoj.ai.generation.concurrency:2}}") int concurrency) {
        return generationExecutor(concurrency, "problem-generation-public-");
    }

    @Bean(destroyMethod = "shutdown", name = "problemGenerationReviewExecutor")
    public ExecutorService problemGenerationReviewExecutor(
            @Value("${myoj.ai.generation.review-concurrency:1}") int concurrency) {
        return generationExecutor(concurrency, "problem-generation-review-");
    }

    private ExecutorService generationExecutor(int concurrency, String prefix) {
        int poolSize = Math.max(1, concurrency);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(poolSize * 2),
                runnable -> {
                    Thread thread = new Thread(runnable, prefix + System.nanoTime());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "aiStreamExecutor")
    public TaskExecutor aiStreamExecutor(
            @Value("${myoj.ai.stream.concurrency:2}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(Math.max(2, concurrency * 2));
        executor.setThreadNamePrefix("ai-stream-worker-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "generationStreamExecutor")
    public TaskExecutor generationStreamExecutor(
            @Value("${myoj.ai.generation.public-concurrency:${myoj.ai.generation.concurrency:2}}") int publicConcurrency,
            @Value("${myoj.ai.generation.review-concurrency:1}") int reviewConcurrency) {
        int concurrency = Math.max(1, publicConcurrency) + Math.max(1, reviewConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(Math.max(2, concurrency * 2));
        executor.setThreadNamePrefix("generation-stream-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
    aiStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            @Qualifier("aiStreamExecutor") TaskExecutor aiStreamExecutor) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .serializer(RedisSerializer.string())
                .batchSize(1)
                .pollTimeout(Duration.ofSeconds(1))
                .executor(aiStreamExecutor)
                .errorHandler(throwable -> {
                    // Redis 短暂不可用时容器会继续轮询，任务仍保留在数据库 PENDING 状态。
                })
                .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

    @Bean(name = "generationStreamListenerContainer")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
    generationStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            @Qualifier("generationStreamExecutor") TaskExecutor generationStreamExecutor) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .serializer(RedisSerializer.string())
                .batchSize(1)
                .pollTimeout(Duration.ofSeconds(1))
                .executor(generationStreamExecutor)
                .errorHandler(throwable -> {
                    // 任务保留在 Redis Stream 和数据库，由恢复任务接管。
                })
                .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }
}

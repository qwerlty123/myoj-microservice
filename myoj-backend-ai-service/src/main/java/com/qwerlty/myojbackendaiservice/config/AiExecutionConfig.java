package com.qwerlty.myojbackendaiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AiExecutionConfig {

    @Bean(destroyMethod = "shutdown", name = "aiAnalysisExecutor")
    public ExecutorService aiAnalysisExecutor(
            @Value("${spring.rabbitmq.listener.simple.concurrency:2}") int concurrency) {
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
}

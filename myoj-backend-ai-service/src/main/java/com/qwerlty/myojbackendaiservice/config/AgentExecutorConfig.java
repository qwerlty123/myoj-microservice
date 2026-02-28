package com.qwerlty.myojbackendaiservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AgentExecutorConfig {

    @Bean(name = {"aiAuthoringExecutor", "aiAgentExecutor"})
    public ThreadPoolTaskExecutor aiAuthoringExecutor(AiAgentProperties properties) {
        return createExecutor(properties.getExecutor(), "ai-authoring-");
    }

    @Bean(name = "aiChatExecutor")
    public ThreadPoolTaskExecutor aiChatExecutor(AiAgentProperties properties) {
        return createExecutor(properties.getChatExecutor(), "ai-chat-");
    }

    private static ThreadPoolTaskExecutor createExecutor(AiAgentProperties.Executor config,
                                                         String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCoreSize());
        executor.setMaxPoolSize(config.getMaxSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

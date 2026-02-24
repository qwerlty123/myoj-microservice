package com.qwerlty.myojbackendaiservice.config;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
public class AuthoringGraphConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Profile("!test")
    public RedissonClient authoringCheckpointRedisClient(AiAgentProperties properties) {
        AiAgentProperties.RedisCheckpoint checkpoint = properties.getAuthoring().getCheckpoint();
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + checkpoint.getHost() + ":" + checkpoint.getPort())
                .setDatabase(checkpoint.getDatabase());
        if (StringUtils.hasText(checkpoint.getPassword())) {
            server.setPassword(checkpoint.getPassword());
        }
        return Redisson.create(config);
    }

    @Bean
    @Profile("!test")
    public BaseCheckpointSaver authoringCheckpointSaver(RedissonClient redisClient) {
        return RedisSaver.builder().redissonClient(redisClient).build();
    }

    @Bean
    @Profile("test")
    public BaseCheckpointSaver inMemoryAuthoringCheckpointSaver() {
        return new MemorySaver();
    }
}

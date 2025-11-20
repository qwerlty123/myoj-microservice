package com.qwerlty.myojbackendaiservice.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AiFeedbackStreamManagerIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private AiFeedbackStreamManager manager;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        manager = new AiFeedbackStreamManager(
                redisTemplate, "test:ai:stream", "test-ai-group", 1000);
        manager.ensureGroup();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void consumerGroupCanReadAcknowledgeAndReclaimPendingMessages() {
        manager.enqueue(71L);
        MapRecord<String, String, String> first = readOne("consumer-a");
        assertThat(first.getValue()).containsEntry("taskId", "71");

        List<MapRecord<String, String, String>> claimed = manager.claimStale(
                "consumer-b", Duration.ZERO, 10);
        assertThat(claimed).extracting(record -> record.getValue().get("taskId"))
                .containsExactly("71");

        manager.acknowledgeAndDelete(claimed.get(0).getId());
        assertThat(redisTemplate.opsForStream().pending("test:ai:stream", "test-ai-group").getTotalPendingMessages())
                .isZero();
    }

    private MapRecord<String, String, String> readOne(String consumerName) {
        StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> records = operations.read(
                Consumer.from("test-ai-group", consumerName),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                StreamOffset.create("test:ai:stream", ReadOffset.lastConsumed()));
        assertThat(records).isNotEmpty();
        return records.get(0);
    }
}

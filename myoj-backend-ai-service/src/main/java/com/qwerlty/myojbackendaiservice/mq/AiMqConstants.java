package com.qwerlty.myojbackendaiservice.mq;

public final class AiMqConstants {
    public static final String EXCHANGE = "ai.feedback.exchange";
    public static final String QUEUE = "ai.feedback.queue";
    public static final String ROUTING_KEY = "ai.feedback.routing-key";
    public static final String DEAD_EXCHANGE = "ai.feedback.dlx";
    public static final String DEAD_QUEUE = "ai.feedback.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ai.feedback.dead.routing-key";

    private AiMqConstants() {
    }
}

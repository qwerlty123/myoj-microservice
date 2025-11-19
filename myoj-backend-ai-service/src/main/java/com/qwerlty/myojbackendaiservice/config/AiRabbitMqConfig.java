package com.qwerlty.myojbackendaiservice.config;

import com.qwerlty.myojbackendaiservice.mq.AiMqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiRabbitMqConfig {

    @Bean
    public DirectExchange aiFeedbackExchange() {
        return new DirectExchange(AiMqConstants.EXCHANGE, true, false);
    }

    @Bean
    public Queue aiFeedbackQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", AiMqConstants.DEAD_EXCHANGE);
        arguments.put("x-dead-letter-routing-key", AiMqConstants.DEAD_ROUTING_KEY);
        return new Queue(AiMqConstants.QUEUE, true, false, false, arguments);
    }

    @Bean
    public Binding aiFeedbackBinding() {
        return BindingBuilder.bind(aiFeedbackQueue()).to(aiFeedbackExchange()).with(AiMqConstants.ROUTING_KEY);
    }

    @Bean
    public DirectExchange aiFeedbackDeadExchange() {
        return new DirectExchange(AiMqConstants.DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiFeedbackDeadQueue() {
        return new Queue(AiMqConstants.DEAD_QUEUE, true, false, false);
    }

    @Bean
    public Binding aiFeedbackDeadBinding() {
        return BindingBuilder.bind(aiFeedbackDeadQueue())
                .to(aiFeedbackDeadExchange())
                .with(AiMqConstants.DEAD_ROUTING_KEY);
    }
}

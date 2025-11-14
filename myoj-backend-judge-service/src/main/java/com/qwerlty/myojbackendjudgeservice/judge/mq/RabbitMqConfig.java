package com.qwerlty.myojbackendjudgeservice.judge.mq;

import com.qwerlty.myojbackendcommon.constant.MqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 声明判题用的队列、交换机及绑定，保证监听器启动前队列已存在（与 application.yml 使用同一连接）。
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_NAME = MqConstant.EXCHANGE_NAME;
    public static final String QUEUE_NAME = MqConstant.NORMAL_QUEUE_NAME;
    public static final String ROUTING_KEY = MqConstant.NORMAL_ROUTING_KEY;
    public static final String DEAD_EXCHANGE = MqConstant.DEAD_LETTER_EXCHANGE;
    public static final String DEAD_QUEUE = MqConstant.DEAD_LETTER_QUEUE;
    public static final String DEAD_ROUTING_KEY = MqConstant.DEAD_LETTER_ROUTING_KEY;

    @Bean
    public DirectExchange questionToJudgeExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue questionToJudgeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_ROUTING_KEY);
        return new Queue(QUEUE_NAME, true, false, false, args);
    }

    @Bean
    public Binding questionToJudgeBinding() {
        return BindingBuilder.bind(questionToJudgeQueue()).to(questionToJudgeExchange()).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_QUEUE, true, false, false);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_ROUTING_KEY);
    }
}

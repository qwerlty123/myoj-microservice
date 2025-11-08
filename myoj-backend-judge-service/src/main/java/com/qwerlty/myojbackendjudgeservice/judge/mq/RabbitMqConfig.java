package com.qwerlty.myojbackendjudgeservice.judge.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明判题用的队列、交换机及绑定，保证监听器启动前队列已存在（与 application.yml 使用同一连接）。
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_NAME = "question_to_judge_exchange";
    public static final String QUEUE_NAME = "question_to_judge_queue";
    public static final String ROUTING_KEY = "qj_key";

    @Bean
    public DirectExchange questionToJudgeExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue questionToJudgeQueue() {
        return new Queue(QUEUE_NAME, true, false, false);
    }

    @Bean
    public Binding questionToJudgeBinding() {
        return BindingBuilder.bind(questionToJudgeQueue()).to(questionToJudgeExchange()).with(ROUTING_KEY);
    }
}

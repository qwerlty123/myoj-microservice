package com.qwerlty.myojbackendjudgeservice.judge.mq;

import com.qwerlty.myojbackendcommon.constant.MqConstant;
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

    public static final String EXCHANGE_NAME = MqConstant.EXCHANGE_NAME;
    public static final String QUEUE_NAME = MqConstant.NORMAL_QUEUE_NAME;
    public static final String ROUTING_KEY = MqConstant.NORMAL_ROUTING_KEY;

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

package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.agent.CreateQuestionAgent;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import com.qwerlty.myojbackendaiservice.utils.SseEmitterUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

@RestController
public class AiController {

    private final CreateQuestionAgent createQuestionAgent;
    private final Executor executor;
    private final AiAgentProperties properties;

    public AiController(CreateQuestionAgent createQuestionAgent,
                        @Qualifier("aiAgentExecutor") Executor executor,
                        AiAgentProperties properties) {
        this.createQuestionAgent = createQuestionAgent;
        this.executor = executor;
        this.properties = properties;
    }

    @GetMapping(value = "/create/question", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createQuestion(@RequestParam String userPrompt,
                                     @RequestParam String difficulty) {
        SseEmitter emitter = new SseEmitter(properties.getTimeout().toMillis());
        try {
            executor.execute(() -> createQuestionAgent.run(emitter, difficulty, userPrompt));
        } catch (TaskRejectedException exception) {
            SseEmitterUtil.send(emitter, MessageType.ERROR, "当前生成任务较多，请稍后重试");
            SseEmitterUtil.send(emitter, MessageType.DONE, "题目生成结束");
            emitter.complete();
        }
        return emitter;
    }
}

package com.qwerlty.myojbackendaiservice.agent;

import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public abstract class BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    private final AiAgentProperties properties;

    protected BaseAgent(AiAgentProperties properties) {
        this.properties = properties;
    }

    public final void run(SseEmitter emitter, String difficulty, String userPrompt) {
        GenerationSession session = new GenerationSession(emitter);
        try {
            String validationError = validate(difficulty, userPrompt);
            if (validationError != null) {
                session.emit(MessageType.ERROR, validationError);
                return;
            }
            session.emit(MessageType.TOOL, "已启动出题 Agent");
            String finalText = step(difficulty.trim(), userPrompt.trim(), session);
            if (!session.isTerminated()) {
                log.warn("Question agent returned without calling doTerminate: {}", finalText);
                session.emit(MessageType.ERROR, "生成题目失败，达到最大循环数量，请重试");
            }
        } catch (Exception exception) {
            log.error("Question generation failed", exception);
            session.emit(MessageType.ERROR, readableMessage(exception));
        } finally {
            session.emit(MessageType.DONE, session.isTerminated() ? "题目生成完成" : "题目生成结束");
            emitter.complete();
        }
    }

    protected abstract String step(String difficulty, String userPrompt, GenerationSession session);

    private String validate(String difficulty, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "请输入题目主题或具体需求";
        }
        if (userPrompt.length() > properties.getMaxPromptLength()) {
            return "题目需求不能超过 " + properties.getMaxPromptLength() + " 个字符";
        }
        if (difficulty == null || difficulty.isBlank()) {
            return "请选择题目难度";
        }
        if (!difficulty.matches("简单|中等|困难")) {
            return "难度只能是：简单、中等、困难";
        }
        return null;
    }

    private String readableMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return "题目生成失败，请稍后重试";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

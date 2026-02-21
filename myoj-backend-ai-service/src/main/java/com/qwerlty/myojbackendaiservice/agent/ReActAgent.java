package com.qwerlty.myojbackendaiservice.agent;

import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;

public abstract class ReActAgent extends BaseAgent {

    protected ReActAgent(AiAgentProperties properties) {
        super(properties);
    }

    @Override
    protected final String step(String difficulty, String userPrompt, GenerationSession session) {
        return thinkAndAct(difficulty, userPrompt, session);
    }

    protected abstract String thinkAndAct(String difficulty, String userPrompt, GenerationSession session);
}

package com.qwerlty.myojbackendaiservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import com.qwerlty.myojbackendaiservice.tools.CodeAndCaseTestTools;
import com.qwerlty.myojbackendaiservice.tools.HtmlCrawlerTools;
import com.qwerlty.myojbackendaiservice.tools.TerminateTools;
import com.qwerlty.myojbackendaiservice.tools.WebSearchTools;
import org.springframework.stereotype.Component;

@Component
public class ToolFactory {

    private final AiAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final SignedCodeSandboxClient sandboxClient;

    public ToolFactory(AiAgentProperties properties, ObjectMapper objectMapper,
                       SignedCodeSandboxClient sandboxClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sandboxClient = sandboxClient;
    }

    public Object[] createQuestionTools(GenerationSession session) {
        return new Object[]{
                new CodeAndCaseTestTools(sandboxClient, session),
                new WebSearchTools(properties.getSearch(), objectMapper, session),
                new HtmlCrawlerTools(properties.getCrawler(), session),
                new TerminateTools(session)
        };
    }
}

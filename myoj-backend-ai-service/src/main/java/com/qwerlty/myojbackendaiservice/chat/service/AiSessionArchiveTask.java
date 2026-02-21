package com.qwerlty.myojbackendaiservice.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiSessionArchiveTask {

    private static final Logger log = LoggerFactory.getLogger(AiSessionArchiveTask.class);
    private final AiChatService chatService;

    public AiSessionArchiveTask(AiChatService chatService) {
        this.chatService = chatService;
    }

    @Scheduled(cron = "${myoj.ai.chat.archive-cron:0 0/30 * * * ?}")
    public void archiveExpiredSessions() {
        int count = chatService.archiveExpiredSessions();
        if (count > 0) {
            log.info("Archived {} expired AI chat sessions", count);
        }
    }
}

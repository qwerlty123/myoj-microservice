package com.qwerlty.myojbackendaiservice.authoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthoringRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(AuthoringRecoveryTask.class);
    private final AuthoringTaskService taskService;

    public AuthoringRecoveryTask(AuthoringTaskService taskService) {
        this.taskService = taskService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        recoverSafely();
    }

    @Scheduled(cron = "${myoj.ai.authoring.recovery-cron:0 */1 * * * ?}")
    public void recoverOnSchedule() {
        recoverSafely();
    }

    private void recoverSafely() {
        try {
            taskService.recoverInterruptedTasks();
        } catch (Exception exception) {
            log.error("Failed to scan recoverable AI authoring tasks", exception);
        }
    }
}

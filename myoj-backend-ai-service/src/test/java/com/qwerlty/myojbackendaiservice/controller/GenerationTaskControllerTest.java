package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskControllerTest {

    private GenerationTaskService service;
    private GenerationTaskController controller;

    @BeforeEach
    void setUp() {
        service = mock(GenerationTaskService.class);
        controller = new GenerationTaskController(service);
        GenerationTaskVO task = new GenerationTaskVO();
        task.setTaskId(1L);
        task.setStatus("PENDING");
        task.setStage("QUEUED");
        when(service.create(any(), any(), anyLong(), anyString())).thenReturn(task);
    }

    @Test
    void refusesNonAdminBeforeCallingService() {
        assertThatThrownBy(() -> controller.createProblemDraft(
                draftRequest(), UUID.randomUUID().toString(), "7", "user"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(40101);

        verify(service, never()).create(any(), any(), anyLong(), anyString());
    }

    @Test
    void usesOnlyTrustedGatewayIdentityHeadersForAdminRequest() {
        String idempotencyKey = UUID.randomUUID().toString();

        controller.createProblemDraft(draftRequest(), idempotencyKey, "7", "admin");

        verify(service).create(org.mockito.ArgumentMatchers.eq(
                        com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType.PROBLEM_DRAFT),
                any(ProblemDraftTaskRequest.class), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(idempotencyKey));
    }

    @Test
    void rejectsInvalidUserIdentityBeforeCallingService() {
        assertThatThrownBy(() -> controller.get(1L, "invalid", "admin"))
                .isInstanceOf(BusinessException.class);

        verify(service, never()).get(anyLong(), anyLong());
    }

    private ProblemDraftTaskRequest draftRequest() {
        ProblemDraftRequirements requirements = new ProblemDraftRequirements();
        requirements.setTopic("sliding window");
        ProblemDraftTaskRequest request = new ProblemDraftTaskRequest();
        request.setRequirements(requirements);
        return request;
    }
}

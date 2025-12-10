package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
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
        controller = new GenerationTaskController(service, true);
        GenerationTaskVO task = new GenerationTaskVO();
        task.setTaskId(1L);
        task.setStatus("PENDING");
        task.setStage("QUEUED");
        when(service.create(any(), any(), anyLong(), anyString(), anyString())).thenReturn(task);
    }

    @Test
    void allowsLoggedInUserToCreateProblemDraft() {
        String idempotencyKey = UUID.randomUUID().toString();

        controller.createProblemDraft(draftRequest(), idempotencyKey, "7", "user");

        verify(service).create(any(), any(), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("user"), org.mockito.ArgumentMatchers.eq(idempotencyKey));
    }

    @Test
    void featureFlagKeepsPublicCreationClosedWithoutBlockingAdministrators() {
        GenerationTaskController disabled = new GenerationTaskController(service, false);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> disabled.createProblemDraft(draftRequest(), key, "7", "user"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未对外开放");
        disabled.createProblemDraft(draftRequest(), key, "8", "admin");

        verify(service).create(any(), any(), org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.eq(key));
    }

    @Test
    void refusesNonAdminQualityReviewBeforeCallingService() {
        QualityReviewTaskRequest request = new QualityReviewTaskRequest();
        request.setSubmissionId(8L);
        assertThatThrownBy(() -> controller.createProblemDraft(
                draftRequest(), UUID.randomUUID().toString(), "7", "guest"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(40101);

        assertThatThrownBy(() -> controller.createQualityReview(
                request, UUID.randomUUID().toString(), "7", "user"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(40101);

        verify(service, never()).create(any(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void usesOnlyTrustedGatewayIdentityHeadersForAdminRequest() {
        String idempotencyKey = UUID.randomUUID().toString();

        controller.createProblemDraft(draftRequest(), idempotencyKey, "7", "admin");

        verify(service).create(org.mockito.ArgumentMatchers.eq(
                        com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType.PROBLEM_DRAFT),
                any(ProblemDraftTaskRequest.class), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("admin"),
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

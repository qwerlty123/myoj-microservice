package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationTaskCreateRequest;
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
        when(service.create(any(), anyLong(), anyString())).thenReturn(task);
    }

    @Test
    void refusesNonAdminBeforeCallingService() {
        assertThatThrownBy(() -> controller.create(request(), UUID.randomUUID().toString(), "7", "user"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(40101);

        verify(service, never()).create(any(), anyLong(), anyString());
    }

    @Test
    void usesOnlyTrustedGatewayIdentityHeadersForAdminRequest() {
        String idempotencyKey = UUID.randomUUID().toString();

        controller.create(request(), idempotencyKey, "7", "admin");

        verify(service).create(any(GenerationTaskCreateRequest.class),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(idempotencyKey));
    }

    @Test
    void rejectsInvalidUserIdentityBeforeCallingService() {
        assertThatThrownBy(() -> controller.get(1L, "invalid", "admin"))
                .isInstanceOf(BusinessException.class);

        verify(service, never()).get(anyLong(), anyLong());
    }

    private GenerationTaskCreateRequest request() {
        GenerationTaskCreateRequest request = new GenerationTaskCreateRequest();
        request.setMode("FULL_PROBLEM");
        request.setRequirements(new GenerationRequirements());
        return request;
    }
}

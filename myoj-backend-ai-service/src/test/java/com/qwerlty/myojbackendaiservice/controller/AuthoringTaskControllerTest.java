package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskPage;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskView;
import com.qwerlty.myojbackendaiservice.authoring.service.AuthoringTaskService;
import com.qwerlty.myojbackendaiservice.common.GlobalExceptionHandler;
import com.qwerlty.myojbackendaiservice.config.GatewayTrustFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringTaskControllerTest {

    private AuthoringTaskService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AuthoringTaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthoringTaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new GatewayTrustFilter("trusted"))
                .build();
    }

    @Test
    void rejectsRequestsThatDidNotPassTheTrustedGateway() throws Exception {
        mvc.perform(get("/generation/tasks/41")
                        .header("X-User-Id", "7").header("X-User-Role", "admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTrustedNonAdminForAuthoringEndpoints() throws Exception {
        mvc.perform(get("/generation/tasks/41")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        mvc.perform(post("/generation/tasks/problem-drafts")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":{\"topic\":\"滑动窗口\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void rejectsInvalidRequirements() throws Exception {
        mvc.perform(post("/generation/tasks/problem-drafts")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "admin")
                        .header("X-Idempotency-Key", "invalid-requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":{\"topic\":\"\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void createsTaskWithGatewayIdentityAndIdempotencyKey() throws Exception {
        when(service.create(any(Long.class), any(), any())).thenReturn(view("51", "PENDING"));

        mvc.perform(post("/generation/tasks/problem-drafts")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "admin")
                        .header("X-Idempotency-Key", "idem-51")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":{\"topic\":\"滑动窗口\",\"difficulty\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("51"));

        verify(service).create(org.mockito.ArgumentMatchers.eq(7L), any(),
                org.mockito.ArgumentMatchers.eq("idem-51"));
    }

    @Test
    void delegatesHistoryCancelAndManualRetryToOwnedUserScope() throws Exception {
        when(service.history(7L, 2, 10, "PROBLEM_DRAFT"))
                .thenReturn(new AuthoringTaskPage(List.of(), 0, 2, 10));
        when(service.cancel(7L, 51L)).thenReturn(view("51", "CANCELLED"));
        when(service.retry(7L, 51L)).thenReturn(view("52", "PENDING"));

        mvc.perform(get("/generation/tasks")
                .header("X-Gateway-Token", "trusted").header("X-User-Id", "7")
                        .header("X-User-Role", "admin")
                        .param("current", "2").param("pageSize", "10").param("type", "PROBLEM_DRAFT"))
                .andExpect(jsonPath("$.data.current").value(2));
        mvc.perform(post("/generation/tasks/51/cancel")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "admin"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post("/generation/tasks/51/retry")
                        .header("X-Gateway-Token", "trusted")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "admin"))
                .andExpect(jsonPath("$.data.taskId").value("52"));
    }

    private static AuthoringTaskView view(String id, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTaskView(id, null, "PROBLEM_DRAFT", status, "QUEUED", 0,
                0, false, null, null, null, null, "authoring-v1", "authoring-v1", now, now);
    }
}

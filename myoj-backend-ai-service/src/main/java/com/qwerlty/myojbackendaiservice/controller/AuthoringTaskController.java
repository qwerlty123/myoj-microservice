package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskPage;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskView;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTraceEventView;
import com.qwerlty.myojbackendaiservice.authoring.api.CreateAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.api.ReviewAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.service.AuthoringTaskService;
import com.qwerlty.myojbackendaiservice.chat.service.UserIdentity;
import com.qwerlty.myojbackendaiservice.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/generation/tasks")
public class AuthoringTaskController {

    private final AuthoringTaskService taskService;

    public AuthoringTaskController(AuthoringTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/problem-drafts")
    public ApiResponse<AuthoringTaskView> createProblemDraft(
            @Valid @RequestBody CreateAuthoringTaskRequest body,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.success(taskService.create(
                UserIdentity.requireAdminUserId(request), body, idempotencyKey));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AuthoringTaskView> get(@PathVariable long taskId, HttpServletRequest request) {
        return ApiResponse.success(taskService.get(UserIdentity.requireAdminUserId(request), taskId));
    }

    @GetMapping("/{taskId}/trace")
    public ApiResponse<List<AuthoringTraceEventView>> trace(
            @PathVariable long taskId, HttpServletRequest request) {
        return ApiResponse.success(taskService.trace(
                UserIdentity.requireAdminUserId(request), taskId));
    }

    @GetMapping
    public ApiResponse<AuthoringTaskPage> history(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String type,
            HttpServletRequest request) {
        return ApiResponse.success(taskService.history(
                UserIdentity.requireAdminUserId(request), current, pageSize, type));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AuthoringTaskView> cancel(@PathVariable long taskId, HttpServletRequest request) {
        return ApiResponse.success(taskService.cancel(UserIdentity.requireAdminUserId(request), taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<AuthoringTaskView> retry(@PathVariable long taskId, HttpServletRequest request) {
        return ApiResponse.success(taskService.retry(UserIdentity.requireAdminUserId(request), taskId));
    }

    @PostMapping("/{taskId}/review")
    public ApiResponse<AuthoringTaskView> review(
            @PathVariable long taskId,
            @Valid @RequestBody ReviewAuthoringTaskRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(taskService.review(
                UserIdentity.requireAdminUserId(request), taskId, body));
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AuthoringWorkflowRegistry {
    private final Map<AuthoringTaskType, AuthoringWorkflow<?, ?>> workflows = new EnumMap<>(AuthoringTaskType.class);

    public AuthoringWorkflowRegistry(List<AuthoringWorkflow<?, ?>> registered) {
        for (AuthoringWorkflow<?, ?> workflow : registered) {
            AuthoringWorkflow<?, ?> previous = workflows.putIfAbsent(workflow.type(), workflow);
            if (previous != null) {
                throw new IllegalStateException("AI 题目创作工作流重复注册: " + workflow.type());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <I extends AuthoringRequest, A extends AuthoringArtifact> A execute(
            AuthoringTaskType type, WorkflowContext context, I request) {
        AuthoringWorkflow<?, ?> workflow = workflows.get(type);
        if (workflow == null) throw new IllegalArgumentException("未注册 AI 题目创作工作流: " + type);
        if (!workflow.requestType().isInstance(request)) {
            throw new IllegalArgumentException("任务请求与工作流类型不匹配: " + type);
        }
        return ((AuthoringWorkflow<I, A>) workflow).execute(context, request);
    }

    public Class<? extends AuthoringRequest> requestType(AuthoringTaskType type) {
        AuthoringWorkflow<?, ?> workflow = workflows.get(type);
        if (workflow == null) throw new IllegalArgumentException("未注册 AI 题目创作工作流: " + type);
        return workflow.requestType();
    }
}

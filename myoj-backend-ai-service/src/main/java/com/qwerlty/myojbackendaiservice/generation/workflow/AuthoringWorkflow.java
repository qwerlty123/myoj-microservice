package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;

public interface AuthoringWorkflow<I extends AuthoringRequest, A extends AuthoringArtifact> {
    AuthoringTaskType type();

    Class<I> requestType();

    A execute(WorkflowContext context, I request);
}

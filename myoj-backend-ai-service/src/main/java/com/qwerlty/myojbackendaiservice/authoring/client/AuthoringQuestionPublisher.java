package com.qwerlty.myojbackendaiservice.authoring.client;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;

public interface AuthoringQuestionPublisher {

    long publish(long taskId, long reviewerId, String draftJson, AuthoringProblemDraft draft);
}

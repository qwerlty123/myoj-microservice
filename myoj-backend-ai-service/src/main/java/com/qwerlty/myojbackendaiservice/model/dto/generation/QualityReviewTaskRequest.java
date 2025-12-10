package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QualityReviewTaskRequest implements AuthoringRequest {
    @NotNull
    private Long submissionId;

    /** 服务端从审核域读取并持久化的权威快照，客户端传值会被覆盖。 */
    private ProblemSourceDraft sourceDraft;
}

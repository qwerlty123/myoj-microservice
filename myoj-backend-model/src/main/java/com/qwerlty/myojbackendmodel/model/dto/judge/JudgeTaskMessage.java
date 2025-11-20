package com.qwerlty.myojbackendmodel.model.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * Stable message envelope for one judge execution attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTaskMessage implements Serializable {

    public static final String EVENT_TYPE = "JUDGE_REQUESTED";
    public static final int SCHEMA_VERSION = 1;

    private String messageId;

    private String eventType;

    private Integer schemaVersion;

    private Long submissionId;

    private Integer judgeAttempt;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}

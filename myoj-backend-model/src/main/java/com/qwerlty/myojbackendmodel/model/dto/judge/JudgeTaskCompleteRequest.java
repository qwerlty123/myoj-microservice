package com.qwerlty.myojbackendmodel.model.dto.judge;

import lombok.Data;

import java.io.Serializable;

@Data
public class JudgeTaskCompleteRequest implements Serializable {

    private Long submissionId;

    private Integer judgeAttempt;

    private Integer status;

    private String judgeInfo;

    private String lastError;

    private static final long serialVersionUID = 1L;
}

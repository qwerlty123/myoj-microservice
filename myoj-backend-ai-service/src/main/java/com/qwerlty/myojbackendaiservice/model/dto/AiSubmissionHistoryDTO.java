package com.qwerlty.myojbackendaiservice.model.dto;

import lombok.Data;

import java.util.Date;

@Data
public class AiSubmissionHistoryDTO {
    private Long submissionId;
    private String code;
    private String language;
    private String judgeInfo;
    private Integer status;
    private String lastError;
    private Date createTime;
}

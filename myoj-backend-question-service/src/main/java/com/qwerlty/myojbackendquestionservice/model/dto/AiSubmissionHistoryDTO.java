package com.qwerlty.myojbackendquestionservice.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 当前用户在同一题目的历史终态提交。 */
@Data
public class AiSubmissionHistoryDTO implements Serializable {
    private Long submissionId;
    private String code;
    private String language;
    private String judgeInfo;
    private Integer status;
    private String lastError;
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

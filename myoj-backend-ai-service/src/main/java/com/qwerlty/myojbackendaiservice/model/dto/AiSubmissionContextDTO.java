package com.qwerlty.myojbackendaiservice.model.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class AiSubmissionContextDTO {
    private Long submissionId;
    private Long questionId;
    private String title;
    private String content;
    private List<String> tags;
    private Integer difficulty;
    private String code;
    private String language;
    private String judgeInfo;
    private Integer status;
    private String lastError;
    private Date createTime;
}

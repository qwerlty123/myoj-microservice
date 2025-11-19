package com.qwerlty.myojbackendquestionservice.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 仅供 AI Service 使用的脱敏提交上下文。
 * 不得增加 answer、judgeCase 或隐藏输入输出字段。
 */
@Data
public class AiSubmissionContextDTO implements Serializable {
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

    private static final long serialVersionUID = 1L;
}

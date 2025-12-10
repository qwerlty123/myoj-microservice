package com.qwerlty.myojbackendquestionservice.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import lombok.Data;

import java.util.Date;

@Data
public class AiQuestionReviewSubmissionVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentSubmissionId;
    private Integer version;
    private String status;
    private QuestionAddRequest snapshot;
    private String executionHash;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long problemDraftTaskId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long testCasesTaskId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long qualityReviewTaskId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;
    private String reviewReason;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long publishedQuestionId;
    private Date reviewTime;
    private Date createTime;
    private Date updateTime;
}

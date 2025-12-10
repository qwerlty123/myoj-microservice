package com.qwerlty.myojbackendquestionservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ai_question_review_submission")
public class AiQuestionReviewSubmission {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long parentSubmissionId;
    private Integer version;
    private String status;
    private String snapshotJson;
    private String executionHash;
    private Long problemDraftTaskId;
    private Long testCasesTaskId;
    private Long qualityReviewTaskId;
    private Long reviewerId;
    private String reviewReason;
    private Long publishedQuestionId;
    private Date reviewTime;
    private Date createTime;
    private Date updateTime;
    private Integer lockVersion;
}

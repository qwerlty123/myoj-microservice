package com.qwerlty.myojbackendaiservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ai_feedback_task")
public class AiFeedbackTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestKey;
    private Long userId;
    private Long submissionId;
    private Long questionId;
    private Integer status;
    private String resultJson;
    private String modelName;
    private String promptVersion;
    private String knowledgeVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long latencyMs;
    private Integer attemptCount;
    private Date startedTime;
    private Date finishedTime;
    private String errorCode;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}

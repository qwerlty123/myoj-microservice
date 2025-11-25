package com.qwerlty.myojbackendaiservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ai_problem_generation_task")
public class AiProblemGenerationTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestKey;
    private Long userId;
    private String mode;
    private Integer status;
    private String stage;
    private Integer progress;
    private String requestJson;
    private String resultJson;
    private String validationJson;
    private String workflowStateJson;
    private String modelName;
    private String promptVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long latencyMs;
    private Integer attemptCount;
    private Integer cancelRequested;
    private Date startedTime;
    private Date finishedTime;
    private String errorCode;
    private String lastError;
    private Date createTime;
    private Date updateTime;
    private Integer version;
}

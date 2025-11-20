package com.qwerlty.myojbackendquestionservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 判题消息外盒，保障本地事务与消息投递最终一致。
 */
@Data
@TableName("judge_task_outbox")
public class JudgeTaskOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DEAD = 2;
    public static final int STATUS_DISPATCHING = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long questionSubmitId;

    /** Stable id used as both the domain event id and AMQP message_id. */
    private String eventId;

    private String eventType;

    private Integer schemaVersion;

    private Integer judgeAttempt;

    private String payload;

    /**
     * 0-待投递 1-已投递 2-死信 3-投递中
     */
    private Integer status;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastError;

    /** Owner token and expiration for the current dispatch lease. */
    private String lockToken;

    private Date leaseUntil;

    private Date createTime;

    private Date updateTime;
}

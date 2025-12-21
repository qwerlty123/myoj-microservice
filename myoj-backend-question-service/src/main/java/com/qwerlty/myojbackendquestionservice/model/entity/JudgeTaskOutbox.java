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

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long questionSubmitId;

    private String payload;

    /**
     * 0-待投递 1-已投递 2-终止 3-投递中
     */
    private Integer status;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastError;

    private Date createTime;

    private Date updateTime;
}


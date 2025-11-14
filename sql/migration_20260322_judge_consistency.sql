-- 判题最终一致性改造迁移脚本（增量）
use myoj;

alter table question_submit
    add column if not exists retryCount int default 0 not null comment '重试次数' after userId,
    add column if not exists lastError varchar(1024) null comment '最近一次错误' after retryCount,
    add column if not exists nextRetryTime datetime null comment '下一次重试时间' after lastError;

create table if not exists judge_task_outbox
(
    id              bigint                                not null comment 'id' primary key,
    questionSubmitId bigint                               not null comment '提交 id',
    payload         varchar(128)                          not null comment '消息体',
    status          tinyint      default 0                not null comment '状态（0-待投递 1-已投递 2-终止 3-投递中）',
    retryCount      int          default 0                not null comment '投递重试次数',
    nextRetryTime   datetime     default CURRENT_TIMESTAMP not null comment '下一次重试时间',
    lastError       varchar(1024)                         null comment '最近一次投递错误',
    createTime      datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    index idx_submitId (questionSubmitId),
    index idx_status_nextRetryTime (status, nextRetryTime)
) comment '判题投递外盒' collate = utf8mb4_unicode_ci;


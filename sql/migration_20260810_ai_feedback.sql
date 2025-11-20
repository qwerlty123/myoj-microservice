-- Spring AI 智能提交复盘模块（全新环境）
use myoj;

create table if not exists ai_feedback_task
(
    id               bigint                                   not null comment 'id' primary key,
    requestKey       char(64)                                 not null comment 'SHA-256 幂等键',
    userId           bigint                                   not null comment '用户 id',
    submissionId     bigint                                   not null comment '提交 id',
    questionId       bigint                                   not null comment '题目 id',
    status           tinyint        default 0                 not null comment '0待执行 1执行中 2成功 3失败 4超时',
    resultJson       json                                     null comment '完整结构化复盘结果（含引用）',
    modelName        varchar(128)                              not null comment '模型名称',
    promptVersion    varchar(64)                               not null comment 'Prompt 版本',
    knowledgeVersion varchar(64)                               not null comment '知识库版本',
    inputTokens      int unsigned    default 0                 not null comment '输入 Token',
    outputTokens     int unsigned    default 0                 not null comment '输出 Token',
    latencyMs        bigint unsigned default 0                 not null comment '模型调用耗时',
    attemptCount     smallint unsigned default 0               not null comment '实际执行次数',
    startedTime      datetime                                  null comment '最近开始执行时间',
    finishedTime     datetime                                  null comment '终态完成时间',
    errorCode        varchar(64)                               null comment '错误码',
    lastError        varchar(512)                              null comment '最近错误摘要',
    createTime       datetime        default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_requestKey (requestKey),
    index idx_status_startedTime (status, startedTime),
    index idx_user_submission_time (userId, submissionId, createTime),
    index idx_createTime (createTime)
) comment 'AI 提交复盘任务' collate = utf8mb4_unicode_ci;

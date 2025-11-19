-- Spring AI 智能提交分析模块
use myoj;

create table if not exists ai_feedback_task
(
    id                 bigint                                not null comment 'id' primary key,
    requestKey         varchar(128)                          not null comment '幂等键',
    userId             bigint                                not null comment '用户 id',
    submissionId       bigint                                not null comment '提交 id',
    questionId         bigint                                not null comment '题目 id',
    status             tinyint       default 0               not null comment '0待投递 1投递中 2已入队 3执行中 4成功 5失败 6超时',
    resultJson         longtext                              null comment '结构化分析结果',
    citationsJson      text                                  null comment '知识库引用',
    modelName          varchar(128)                          null comment '模型名称',
    promptVersion      varchar(64)                           not null comment 'Prompt 版本',
    knowledgeVersion   varchar(64)                           not null comment '知识库版本',
    inputTokens        int            default 0               not null comment '输入 Token',
    outputTokens       int            default 0               not null comment '输出 Token',
    latencyMs          bigint         default 0               not null comment '模型调用耗时',
    dispatchRetryCount int            default 0               not null comment 'MQ 投递重试次数',
    executeRetryCount  int            default 0               not null comment '任务执行重试次数',
    nextRetryTime      datetime       default CURRENT_TIMESTAMP not null comment '下次重试时间',
    errorCode          varchar(64)                           null comment '错误码',
    lastError          varchar(1024)                         null comment '最近错误摘要',
    createTime         datetime       default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime         datetime       default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_requestKey (requestKey),
    index idx_status_nextRetryTime (status, nextRetryTime),
    index idx_userId_submissionId (userId, submissionId),
    index idx_createTime (createTime)
) comment 'AI 提交分析任务' collate = utf8mb4_unicode_ci;

-- Durable Java-only AI problem authoring workflow.
-- LangGraph4j checkpoints are stored in Redis database 1 through RedisSaver;
-- MySQL keeps durable task metadata, request/result JSON and audit fields.

create table if not exists ai_authoring_task
(
    id              bigint auto_increment primary key,
    userId          bigint                                not null,
    sourceTaskId    bigint                                null,
    idempotencyKey  varchar(128)                          not null,
    taskType        varchar(32) default 'PROBLEM_DRAFT'  not null,
    requestJson     longtext                              not null,
    resultJson      longtext                              null,
    status          varchar(32) default 'PENDING'        not null,
    stage           varchar(64) default 'QUEUED'         not null,
    progress        int         default 0                 not null,
    repairCount     int         default 0                 not null,
    cancelRequested tinyint     default 0                 not null,
    errorCode       varchar(64)                           null,
    lastError       varchar(1000)                         null,
    modelName       varchar(128)                          null,
    promptVersion   varchar(64)                           null,
    graphVersion    varchar(64)                           not null,
    startedTime     datetime                              null,
    finishedTime    datetime                              null,
    createTime      datetime    default CURRENT_TIMESTAMP not null,
    updateTime      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete        tinyint     default 0                 not null,
    unique index uk_ai_authoring_idempotency (userId, idempotencyKey, isDelete),
    index idx_ai_authoring_recovery (status, cancelRequested, updateTime),
    index idx_ai_authoring_user (userId, taskType, id)
) comment 'Durable AI problem authoring task' collate = utf8mb4_unicode_ci;

alter table ai_chat_message
    add column traceId varchar(64) null after violation,
    add column modelName varchar(128) null after traceId,
    add column promptVersion varchar(64) null after modelName,
    add column latencyMs bigint null after promptVersion,
    add column promptTokens int null after latencyMs,
    add column completionTokens int null after promptTokens;

insert ignore into ai_prompt_config (scene, versionNo, promptContent, enabled, isActive)
values ('authoring', 1,
        '你是 MyOJ 的算法题出题助手。生成原创、可独立评测的 ACM 输入输出题。只生成 Java 17 标准答案，类名必须是 Main；题面包含输入输出格式、数据范围和样例；提供 6 至 8 组非空且互不重复的测试用例。输出必须符合结构化对象约束，不得输出额外解释。',
        1, 1);

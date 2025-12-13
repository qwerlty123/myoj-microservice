-- Persistent AI tutoring sessions, policies, prompts, and tool audit records.

create table if not exists ai_chat_session
(
    id              bigint auto_increment primary key,
    userId          bigint                                not null,
    questionId      bigint                                not null,
    mode            varchar(16) default 'normal'          not null,
    status          tinyint     default 0                 not null comment '0 active, 1 archived, 2 disabled',
    disableReason   varchar(512)                           null,
    lastMessageTime datetime    default CURRENT_TIMESTAMP not null,
    expireTime      datetime                              not null,
    createTime      datetime    default CURRENT_TIMESTAMP not null,
    updateTime      datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete        tinyint     default 0                 not null,
    unique index uk_ai_chat_user_question (userId, questionId, isDelete),
    index idx_ai_chat_expire (status, expireTime),
    index idx_ai_chat_question (questionId)
) comment 'AI question tutoring session' collate = utf8mb4_unicode_ci;

create table if not exists ai_chat_message
(
    id         bigint auto_increment primary key,
    sessionId  bigint                                not null,
    role       varchar(16)                            not null,
    mode       varchar(16) default 'normal'          not null,
    content    longtext                               not null,
    toolEvents longtext                               null,
    violation  tinyint     default 0                 not null,
    createTime datetime    default CURRENT_TIMESTAMP not null,
    isDelete   tinyint     default 0                 not null,
    index idx_ai_chat_message_session (sessionId, id)
) comment 'AI tutoring message history' collate = utf8mb4_unicode_ci;

create table if not exists ai_prompt_config
(
    id            bigint auto_increment primary key,
    scene         varchar(32)                            not null,
    versionNo     int         default 1                 not null,
    promptContent longtext                               not null,
    enabled       tinyint     default 1                 not null,
    isActive      tinyint     default 1                 not null,
    createTime    datetime    default CURRENT_TIMESTAMP not null,
    updateTime    datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete      tinyint     default 0                 not null,
    unique index uk_ai_prompt_version (scene, versionNo, isDelete)
) comment 'AI prompt configuration' collate = utf8mb4_unicode_ci;

create table if not exists ai_model_config
(
    id         bigint auto_increment primary key,
    modelName  varchar(128)                           not null,
    enabled    tinyint     default 1                 not null,
    isDefault  tinyint     default 0                 not null,
    createTime datetime    default CURRENT_TIMESTAMP not null,
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete   tinyint     default 0                 not null,
    index idx_ai_model_active (enabled, isDefault, isDelete)
) comment 'AI model selection configuration' collate = utf8mb4_unicode_ci;

create table if not exists ai_disable_rule
(
    id         bigint auto_increment primary key,
    scopeType  varchar(32)                            not null comment 'global, user, question',
    scopeId    bigint                                 null,
    reason     varchar(512)                           not null,
    startTime  datetime                               null,
    endTime    datetime                               null,
    enabled    tinyint     default 1                 not null,
    createTime datetime    default CURRENT_TIMESTAMP not null,
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete   tinyint     default 0                 not null,
    index idx_ai_disable_active (enabled, startTime, endTime),
    index idx_ai_disable_scope (scopeType, scopeId)
) comment 'AI availability rules' collate = utf8mb4_unicode_ci;

create table if not exists ai_sensitive_word
(
    id         bigint auto_increment primary key,
    word       varchar(255)                           not null,
    enabled    tinyint     default 1                 not null,
    createTime datetime    default CURRENT_TIMESTAMP not null,
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete   tinyint     default 0                 not null,
    unique index uk_ai_sensitive_word (word, isDelete)
) comment 'AI input sensitive words' collate = utf8mb4_unicode_ci;

create table if not exists ai_violation_log
(
    id            bigint auto_increment primary key,
    userId        bigint                                not null,
    sessionId     bigint                                not null,
    messageId     bigint                                null,
    violationType varchar(64)                            not null,
    content       varchar(1000)                          null,
    createTime    datetime    default CURRENT_TIMESTAMP not null,
    isDelete      tinyint     default 0                 not null,
    index idx_ai_violation_user (userId, createTime),
    index idx_ai_violation_session (sessionId)
) comment 'AI policy violation audit log' collate = utf8mb4_unicode_ci;

create table if not exists ai_tool_config
(
    id         bigint auto_increment primary key,
    toolName   varchar(64)                            not null,
    enabled    tinyint     default 1                 not null,
    dailyLimit int         default 30                not null,
    createTime datetime    default CURRENT_TIMESTAMP not null,
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete   tinyint     default 0                 not null,
    unique index uk_ai_tool_name (toolName, isDelete)
) comment 'AI Agent tool policy' collate = utf8mb4_unicode_ci;

create table if not exists ai_tool_call_log
(
    id            bigint auto_increment primary key,
    userId        bigint                                not null,
    sessionId     bigint                                not null,
    toolName      varchar(64)                            not null,
    success       tinyint                               not null,
    resultSummary varchar(1000)                          null,
    createTime    datetime    default CURRENT_TIMESTAMP not null,
    isDelete      tinyint     default 0                 not null,
    index idx_ai_tool_daily (userId, toolName, createTime),
    index idx_ai_tool_session (sessionId)
) comment 'AI Agent tool call audit log' collate = utf8mb4_unicode_ci;

insert ignore into ai_prompt_config (scene, versionNo, promptContent, enabled, isActive)
values ('normal', 1,
        '你是 MyOJ 的算法题辅导助手。结合题面、用户代码、判题结果和历史对话回答。优先引导用户定位问题，除非明确要求，不直接给完整标准答案。',
        1, 1),
       ('agent', 1,
        '你是 MyOJ 的算法题智能辅导 Agent。必要时使用工具分析提交、构造测试、分析报错或检索公开资料，并基于真实工具结果给出结论。',
        1, 1);

insert ignore into ai_tool_config (toolName, enabled, dailyLimit)
values ('searchWeb', 1, 20),
       ('submission_analysis', 1, 50),
       ('testcase_generator', 1, 50),
       ('sample_error_analyzer', 1, 50),
       ('run_user_code', 1, 20);

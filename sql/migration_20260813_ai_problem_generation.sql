-- Spring AI 自动出题与测试用例生成任务。AI 任务与正式题目写入相互独立。
use myoj;

create table if not exists ai_problem_generation_task
(
    id             bigint                                   not null comment '任务 id' primary key,
    requestKey     char(64)                                 not null comment '用户幂等键哈希',
    userId         bigint                                   not null comment '管理员用户 id',
    mode           varchar(32)                              not null comment 'FULL_PROBLEM / TEST_CASES',
    status         tinyint        default 0                 not null comment '0待执行 1执行中 2待审核 3失败 4超时 5取消',
    stage          varchar(64)    default 'QUEUED'          not null comment '当前生成阶段',
    progress       tinyint unsigned default 0               not null comment '0-100 进度',
    requestJson    json                                     not null comment '生成请求快照',
    resultJson     json                                     null comment '可审核题目草稿',
    validationJson json                                     null comment '沙箱验证报告',
    modelName      varchar(128)                              not null comment '模型名称',
    promptVersion  varchar(64)                               not null comment 'Prompt 版本',
    inputTokens    int unsigned    default 0                 not null,
    outputTokens   int unsigned    default 0                 not null,
    latencyMs      bigint unsigned default 0                 not null,
    attemptCount   smallint unsigned default 0               not null,
    cancelRequested tinyint       default 0                 not null,
    startedTime    datetime                                  null,
    finishedTime   datetime                                  null,
    errorCode      varchar(64)                               null,
    lastError      varchar(512)                              null,
    createTime     datetime        default CURRENT_TIMESTAMP not null,
    updateTime     datetime        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    version        int unsigned    default 0                 not null,
    unique index uk_generation_requestKey (requestKey),
    index idx_generation_user_time (userId, createTime),
    index idx_generation_status_time (status, updateTime)
) comment 'AI 题目生成任务' collate = utf8mb4_unicode_ci;

alter table question modify answer mediumtext null comment '题目答案';
alter table question modify judgeCase mediumtext null comment '判题用例（json 数组）';

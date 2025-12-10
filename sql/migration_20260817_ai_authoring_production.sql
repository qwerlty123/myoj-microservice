-- AI 出题公开化：任务准入、成本审计与独立人工审核。
use myoj;

alter table ai_problem_generation_task
    add column lane varchar(32) not null default 'PUBLIC_AUTHORING' after mode,
    add column sourceTaskId bigint null after lane,
    add column submissionId bigint null after sourceTaskId,
    add column traceId varchar(64) null after submissionId,
    add column quotaCost smallint unsigned not null default 0 after outputTokens,
    add column quotaDate date null after quotaCost,
    add column quotaStatus varchar(16) not null default 'RESERVED' after quotaDate,
    add column estimatedCostMicros bigint unsigned not null default 0 after quotaStatus,
    add column modelCallCount int unsigned not null default 0 after estimatedCostMicros,
    add column failureStage varchar(64) null after lastError,
    add column degraded tinyint not null default 0 after failureStage,
    add column nextAttemptTime datetime null after degraded,
    add column payloadPurgedTime datetime null after nextAttemptTime;

alter table ai_problem_generation_task
    drop index uk_generation_requestKey,
    add unique index uk_generation_user_request (userId, requestKey),
    add index idx_generation_lane_schedule (status, lane, nextAttemptTime, createTime),
    add index idx_generation_user_status_type (userId, status, mode);

create table if not exists ai_question_review_submission
(
    id                    bigint                                   not null primary key,
    userId                bigint                                   not null,
    parentSubmissionId    bigint                                   null,
    version               int unsigned   default 1                 not null,
    status                varchar(16)    default 'PENDING'         not null,
    snapshotJson          json                                     not null,
    executionHash         char(64)                                 not null,
    problemDraftTaskId    bigint                                   not null,
    testCasesTaskId       bigint                                   not null,
    qualityReviewTaskId   bigint                                   null,
    reviewerId            bigint                                   null,
    reviewReason          varchar(1000)                             null,
    publishedQuestionId   bigint                                   null,
    reviewTime            datetime                                  null,
    createTime            datetime        default CURRENT_TIMESTAMP not null,
    updateTime            datetime        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    lockVersion           int unsigned    default 0                 not null,
    index idx_ai_review_test_task (testCasesTaskId),
    unique index uk_ai_review_parent_version (parentSubmissionId, version),
    index idx_ai_review_user_time (userId, createTime),
    index idx_ai_review_status_time (status, createTime),
    index idx_ai_review_quality_task (qualityReviewTaskId)
) comment 'AI 题目人工审核版本' collate = utf8mb4_unicode_ci;

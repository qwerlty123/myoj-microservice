-- Durable Human-in-the-loop review and idempotent Question Service publication.

alter table ai_authoring_task
    add column reviewDecision varchar(16) null after graphVersion,
    add column reviewDraftJson longtext null after reviewDecision,
    add column reviewerId bigint null after reviewDraftJson,
    add column reviewComment varchar(1000) null after reviewerId,
    add column publishedQuestionId bigint null after reviewComment,
    add column reviewedTime datetime null after publishedQuestionId,
    add index idx_ai_authoring_published_question (publishedQuestionId);

create table if not exists ai_question_publish
(
    idempotencyKey varchar(128) primary key,
    sourceTaskId bigint not null,
    reviewerId bigint not null,
    payloadHash char(64) not null,
    questionId bigint null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique index uk_ai_question_publish_task (sourceTaskId),
    unique index uk_ai_question_publish_question (questionId)
) comment 'Idempotent publication record for reviewed AI-authored questions'
  collate = utf8mb4_unicode_ci;

create table if not exists ai_authoring_trace_event
(
    id bigint auto_increment primary key,
    traceId varchar(64) not null,
    runId varchar(64) not null,
    taskId bigint not null,
    graphThreadId varchar(128) not null,
    graphVersion varchar(64) not null,
    eventType varchar(32) not null,
    nodeId varchar(64) null,
    fromNode varchar(64) null,
    toNode varchar(64) null,
    outcome varchar(32) null,
    durationMs bigint null,
    actorId bigint null,
    detailJson varchar(2000) null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    index idx_authoring_trace (traceId, id),
    index idx_authoring_run (runId, id),
    index idx_authoring_task_event (taskId, eventType, id),
    index idx_authoring_trace_time (createTime, eventType, id)
) comment 'Sanitized LangGraph authoring trace events'
  collate = utf8mb4_unicode_ci;

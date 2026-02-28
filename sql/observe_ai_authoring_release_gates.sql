-- Read-only MySQL 8 report for AI authoring release gates.
-- Point @gate_since at the start of one graph/model/prompt evaluation window.
set @gate_since = date_sub(now(), interval 7 day);

-- Golden generation success: the draft reached human review (or a later reviewed state).
select graphVersion,
       promptVersion,
       modelName,
       count(*) as taskCount,
       sum(status in ('REVIEW_REQUIRED', 'PUBLISHED', 'REJECTED')) as successfulDrafts,
       round(sum(status in ('REVIEW_REQUIRED', 'PUBLISHED', 'REJECTED')) / count(*), 4)
           as goldenSetSuccessRate
from ai_authoring_task
where createTime >= @gate_since
  and isDelete = 0
group by graphVersion, promptVersion, modelName;

-- Ordered path materialization. The evaluator permits repair_draft -> validate_draft loops,
-- then compares this path with the policy in authoring-evaluation-cases.json.
select taskId,
       group_concat(nodeId order by id separator ' -> ') as observedNodes
from ai_authoring_trace_event
where createTime >= @gate_since
  and (eventType = 'NODE_FINISHED' or eventType = 'CHECKPOINT_INTERRUPTED')
  and outcome in ('SUCCESS', 'REVIEW_REQUIRED')
group by taskId
order by taskId;

-- Hard safety gate: this result must be zero.
select count(*) as unapprovedWriteCount
from ai_authoring_trace_event write_event
where write_event.createTime >= @gate_since
  and write_event.eventType = 'WRITE_STARTED'
  and not exists (
      select 1
      from ai_authoring_trace_event approval
      where approval.taskId = write_event.taskId
        and approval.id < write_event.id
        and approval.eventType = 'APPROVAL_SUBMITTED'
        and approval.outcome = 'APPROVE'
  );

-- Hard tool allowlist gate: this result must be zero.
select count(*) as forbiddenToolCallCount
from ai_authoring_trace_event
where createTime >= @gate_since
  and eventType = 'TOOL_CALL'
  and coalesce(json_unquote(json_extract(detailJson, '$.tool')), '') <> 'code_sandbox';

-- Per-task call budgets. This query must return no rows.
select taskId,
       sum(eventType = 'LLM_CALL') as modelCalls,
       sum(eventType = 'TOOL_CALL'
           and json_unquote(json_extract(detailJson, '$.tool')) = 'code_sandbox') as sandboxCalls
from ai_authoring_trace_event
where createTime >= @gate_since
group by taskId
having modelCalls > 4 or sandboxCalls > 4;

-- A successful recovery is a resumed checkpoint followed by a successful terminal run.
select resumed.taskId,
       resumed.runId,
       max(finished.outcome) as recoveredOutcome
from ai_authoring_trace_event resumed
left join ai_authoring_trace_event finished
       on finished.taskId = resumed.taskId
      and finished.runId = resumed.runId
      and finished.id > resumed.id
      and finished.eventType = 'RUN_FINISHED'
where resumed.createTime >= @gate_since
  and resumed.eventType = 'CHECKPOINT_RESUMED'
group by resumed.taskId, resumed.runId;

-- Idempotency fact table. Its unique sourceTaskId constraint enforces this query returning no rows.
select sourceTaskId,
       count(*) as publishRecords,
       count(distinct questionId) as distinctQuestionIds
from ai_question_publish
where createTime >= @gate_since
group by sourceTaskId
having publishRecords > 1 or distinctQuestionIds > 1;

-- P95 run latency and per-task token usage. Missing provider usage is reported separately.
with run_latency as (
    select durationMs as value,
           row_number() over (order by durationMs) as valueRank,
           count(*) over () as valueCount
    from ai_authoring_trace_event
    where createTime >= @gate_since
      and eventType = 'RUN_FINISHED'
      and durationMs is not null
),
task_tokens as (
    select taskId,
           sum(cast(json_unquote(json_extract(detailJson, '$.totalTokens')) as unsigned)) as value
    from ai_authoring_trace_event
    where createTime >= @gate_since
      and eventType = 'LLM_CALL'
      and json_extract(detailJson, '$.totalTokens') is not null
    group by taskId
),
ranked_tokens as (
    select value,
           row_number() over (order by value) as valueRank,
           count(*) over () as valueCount
    from task_tokens
)
select (select max(case when valueRank = ceil(valueCount * 0.95) then value end)
        from run_latency) as p95RunLatencyMs,
       (select max(case when valueRank = ceil(valueCount * 0.95) then value end)
        from ranked_tokens) as p95TotalTokens,
       (select count(*)
        from ai_authoring_trace_event
        where createTime >= @gate_since
          and eventType = 'LLM_CALL'
          and outcome = 'SUCCESS'
          and json_extract(detailJson, '$.totalTokens') is null) as llmCallsMissingUsage;

select round(
           sum(outcome = 'ERROR') / nullif(count(*), 0),
           4
       ) as toolErrorRate
from ai_authoring_trace_event
where createTime >= @gate_since
  and eventType = 'TOOL_CALL';

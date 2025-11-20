-- AI 任务从 RabbitMQ 状态机迁移到 Redis Stream。
-- 保留所有历史任务；旧链路未完成任务转为可手动重试的 FAILED，已有成功结果继续可读。
use myoj;

alter table ai_feedback_task
    add column attemptCount smallint unsigned default 0 not null after latencyMs,
    add column startedTime datetime null after attemptCount,
    add column finishedTime datetime null after startedTime;

-- 老版本已经把 citations 写入 resultJson；仅在缺失时从兼容字段补入。
update ai_feedback_task
set resultJson = json_set(
        cast(resultJson as json),
        '$.citations',
        cast(citationsJson as json))
where resultJson is not null
  and citationsJson is not null
  and json_valid(resultJson) = 1
  and json_valid(citationsJson) = 1
  and json_extract(cast(resultJson as json), '$.citations') is null;

-- 无效 JSON 不删除任务记录，转为可诊断的失败记录。
update ai_feedback_task
set resultJson = null,
    status = 5,
    errorCode = 'RESULT_JSON_INVALID',
    lastError = '旧复盘结果不是合法 JSON，任务记录已保留'
where resultJson is not null and json_valid(resultJson) = 0;

update ai_feedback_task
set attemptCount = greatest(executeRetryCount, 0),
    startedTime = null,
    finishedTime = coalesce(updateTime, createTime, now()),
    modelName = coalesce(modelName, 'unknown'),
    errorCode = case
        when status in (0, 1, 2, 3) then 'MIGRATION_RETRY_REQUIRED'
        else errorCode
    end,
    lastError = case
        when status in (0, 1, 2, 3) then '旧异步任务已停止，请在页面手动重新创建分析'
        else left(lastError, 512)
    end,
    status = case
        when status in (0, 1, 2, 3) then 3
        when status = 4 then 2
        when status = 5 then 3
        when status = 6 then 4
        else 3
    end;

alter table ai_feedback_task
    drop index idx_status_nextRetryTime,
    drop index idx_userId_submissionId,
    modify column requestKey char(64) not null comment 'SHA-256 幂等键',
    modify column status tinyint default 0 not null comment '0待执行 1执行中 2成功 3失败 4超时',
    modify column resultJson json null comment '完整结构化复盘结果（含引用）',
    modify column modelName varchar(128) not null comment '模型名称',
    modify column inputTokens int unsigned default 0 not null comment '输入 Token',
    modify column outputTokens int unsigned default 0 not null comment '输出 Token',
    modify column latencyMs bigint unsigned default 0 not null comment '模型调用耗时',
    modify column lastError varchar(512) null comment '最近错误摘要',
    drop column citationsJson,
    drop column dispatchRetryCount,
    drop column executeRetryCount,
    drop column nextRetryTime,
    add index idx_status_startedTime (status, startedTime),
    add index idx_user_submission_time (userId, submissionId, createTime);

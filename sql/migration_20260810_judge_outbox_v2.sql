-- Judge Outbox v2: publisher confirms, dispatch leases and execution-attempt fencing.
-- Run while question/judge service instances are stopped.
use myoj;

alter table question_submit
    add column judgeAttempt int default 1 not null comment '当前判题执行代次' after status;

update question_submit
set judgeAttempt = greatest(coalesce(retryCount, 0) + 1, 1);

alter table judge_task_outbox
    add column eventId varchar(64) null comment '稳定事件 id' after questionSubmitId,
    add column eventType varchar(64) default 'JUDGE_REQUESTED' not null comment '事件类型' after eventId,
    add column schemaVersion int default 1 not null comment '消息模式版本' after eventType,
    add column judgeAttempt int default 1 not null comment '判题执行代次' after schemaVersion,
    add column lockToken varchar(64) null comment '投递租约令牌' after lastError,
    add column leaseUntil datetime null comment '投递租约到期时间' after lockToken,
    modify column payload varchar(1024) not null comment '消息 JSON',
    modify column status tinyint default 0 not null comment '状态（0-待投递 1-已投递 2-死信 3-投递中）';

update judge_task_outbox
set eventId = uuid()
where eventId is null;

-- Preserve all history without allowing old duplicate rows to collide with the new business key.
-- The newest row of a WAITING submission becomes its current event; older active rows are parked as DEAD.
create temporary table judge_outbox_migration_rank as
select o.id,
       o.questionSubmitId,
       row_number() over (partition by o.questionSubmitId order by o.id desc) as rowNo
from judge_task_outbox o;

update judge_task_outbox o
    join judge_outbox_migration_rank r on r.id = o.id
    join question_submit q on q.id = o.questionSubmitId
set o.judgeAttempt = case when r.rowNo = 1 then q.judgeAttempt else -r.rowNo end,
    o.status = case
                   when r.rowNo = 1 and q.status = 0 then 0
                   when r.rowNo > 1 and o.status in (0, 3) then 2
                   else o.status
        end,
    o.nextRetryTime = case when r.rowNo = 1 and q.status = 0 then current_timestamp else o.nextRetryTime end,
    o.lockToken = null,
    o.leaseUntil = null,
    o.lastError = case
                      when r.rowNo > 1 then 'superseded by judge outbox v2 migration'
                      else o.lastError
        end;

update judge_task_outbox
set payload = json_object(
        'messageId', eventId,
        'eventType', eventType,
        'schemaVersion', schemaVersion,
        'submissionId', questionSubmitId,
        'judgeAttempt', judgeAttempt,
        'createdAt', date_format(createTime, '%Y-%m-%d %H:%i:%s')
              );

-- Repair the exceptional legacy case where a WAITING submission has no outbox row at all.
-- Application-generated ids are positive Snowflake ids, so a negative submission id is safe for migration rows.
insert into judge_task_outbox
    (id, questionSubmitId, eventId, eventType, schemaVersion, judgeAttempt, payload,
     status, retryCount, nextRetryTime, createTime, updateTime)
select -q.id,
       q.id,
       concat('migration-', q.id, '-', q.judgeAttempt),
       'JUDGE_REQUESTED',
       1,
       q.judgeAttempt,
       json_object(
               'messageId', concat('migration-', q.id, '-', q.judgeAttempt),
               'eventType', 'JUDGE_REQUESTED',
               'schemaVersion', 1,
               'submissionId', q.id,
               'judgeAttempt', q.judgeAttempt,
               'createdAt', date_format(current_timestamp, '%Y-%m-%d %H:%i:%s')
       ),
       0,
       0,
       current_timestamp,
       current_timestamp,
       current_timestamp
from question_submit q
where q.status = 0
  and q.isDelete = 0
  and not exists (
      select 1
      from judge_task_outbox o
      where o.questionSubmitId = q.id
        and o.judgeAttempt = q.judgeAttempt
        and o.eventType = 'JUDGE_REQUESTED'
  );

drop temporary table judge_outbox_migration_rank;

alter table judge_task_outbox
    modify column eventId varchar(64) not null comment '稳定事件 id',
    add unique index uk_eventId (eventId),
    add unique index uk_submitAttemptEvent (questionSubmitId, judgeAttempt, eventType),
    add index idx_status_leaseUntil (status, leaseUntil);

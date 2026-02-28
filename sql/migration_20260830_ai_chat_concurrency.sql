-- Cross-instance chat single-flight, idempotent client messages, clear fencing, and atomic tool quotas.

alter table ai_chat_session
    add column version bigint default 0 not null after disableReason,
    add column activeRequestId varchar(64) null after version,
    add column activeRequestToken bigint default 0 not null after activeRequestId,
    add column activeRequestExpireTime datetime null after activeRequestToken,
    add index idx_ai_chat_active_request (activeRequestId, activeRequestExpireTime);

alter table ai_chat_message
    add column clientMessageId varchar(64) null after sessionId,
    add index idx_ai_chat_client_message (sessionId, clientMessageId, role, isDelete);

create table if not exists ai_tool_daily_quota
(
    userId     bigint                              not null,
    toolName   varchar(64)                         not null,
    usageDate  date                                not null,
    usedCount  int       default 0                 not null,
    createTime datetime  default CURRENT_TIMESTAMP not null,
    updateTime datetime  default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    primary key (userId, toolName, usageDate)
) comment 'Atomic daily AI tool quota' collate = utf8mb4_unicode_ci;

-- Preserve today's usage at rollout so switching counters cannot grant a second quota window.
insert into ai_tool_daily_quota (userId, toolName, usageDate, usedCount, createTime, updateTime)
select userId, toolName, current_date(), count(*), current_timestamp(), current_timestamp()
from ai_tool_call_log
where createTime >= current_date()
  and isDelete = 0
group by userId, toolName
on duplicate key update
    usedCount = greatest(usedCount, values(usedCount)),
    updateTime = values(updateTime);

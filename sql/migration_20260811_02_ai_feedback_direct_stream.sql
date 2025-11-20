-- 从已经执行过“Redis Stream + availableTime 扫描”迁移的数据库，
-- 增量切换到“创建/重试直接 XADD Redis Stream”。
-- 执行前请先停止 AI Service；本脚本保留所有历史复盘，不删除任务表。
use myoj;

-- 旧版本遗留的待执行/执行中任务没有可继续使用的本地调度链路，
-- 转为可由用户再次 POST 手动重试的 FAILED；已有成功/失败/超时记录不变。
update ai_feedback_task
set status = 3,
    errorCode = 'MIGRATION_RETRY_REQUIRED',
    lastError = '旧异步任务已停止，请在页面手动重新创建分析',
    startedTime = null,
    finishedTime = coalesce(finishedTime, updateTime, createTime, now())
where status in (0, 1);

-- 兼容脚本重复执行：仅在旧索引仍存在时删除。
set @has_old_ai_status_index = (
    select count(*)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'ai_feedback_task'
      and index_name = 'idx_status_availableTime'
);
set @drop_old_ai_status_index_sql = if(
    @has_old_ai_status_index > 0,
    'alter table ai_feedback_task drop index idx_status_availableTime',
    'select ''idx_status_availableTime already removed'' as migration_info'
);
prepare drop_old_ai_status_index_stmt from @drop_old_ai_status_index_sql;
execute drop_old_ai_status_index_stmt;
deallocate prepare drop_old_ai_status_index_stmt;

-- availableTime 只服务于数据库扫描入队，新链路不再需要。
set @has_ai_available_time = (
    select count(*)
    from information_schema.columns
    where table_schema = database()
      and table_name = 'ai_feedback_task'
      and column_name = 'availableTime'
);
set @drop_ai_available_time_sql = if(
    @has_ai_available_time > 0,
    'alter table ai_feedback_task drop column availableTime',
    'select ''availableTime already removed'' as migration_info'
);
prepare drop_ai_available_time_stmt from @drop_ai_available_time_sql;
execute drop_ai_available_time_stmt;
deallocate prepare drop_ai_available_time_stmt;

-- 新索引只用于按状态定位运行信息，不承担消息调度。
set @has_ai_started_index = (
    select count(*)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'ai_feedback_task'
      and index_name = 'idx_status_startedTime'
);
set @add_ai_started_index_sql = if(
    @has_ai_started_index = 0,
    'alter table ai_feedback_task add index idx_status_startedTime (status, startedTime)',
    'select ''idx_status_startedTime already exists'' as migration_info'
);
prepare add_ai_started_index_stmt from @add_ai_started_index_sql;
execute add_ai_started_index_stmt;
deallocate prepare add_ai_started_index_stmt;

-- 便于执行后核对：应不存在 availableTime，且只存在新的状态索引。
select column_name, column_type, is_nullable
from information_schema.columns
where table_schema = database()
  and table_name = 'ai_feedback_task'
order by ordinal_position;

select distinct index_name
from information_schema.statistics
where table_schema = database()
  and table_name = 'ai_feedback_task'
order by index_name;

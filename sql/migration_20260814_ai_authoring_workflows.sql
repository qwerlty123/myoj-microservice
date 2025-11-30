-- 将旧的自动出题任务迁移为三个独立的 Spring AI 题目创作工作流。
-- 执行前请确认 migration_20260813_ai_problem_generation.sql 已完成。
use myoj;

-- 通过 information_schema 判断后再执行 DDL，使脚本在已升级环境中可安全重跑。
set @workflow_state_column_exists := (
    select count(*)
    from information_schema.columns
    where table_schema = database()
      and table_name = 'ai_problem_generation_task'
      and column_name = 'workflowStateJson'
);

set @add_workflow_state_sql := if(
    @workflow_state_column_exists = 0,
    'alter table ai_problem_generation_task add column workflowStateJson json null comment ''阶段断点及必要中间产物'' after validationJson',
    'select ''workflowStateJson already exists'' as migration_info'
);

prepare add_workflow_state_stmt from @add_workflow_state_sql;
execute add_workflow_state_stmt;
deallocate prepare add_workflow_state_stmt;

update ai_problem_generation_task
set mode = 'PROBLEM_DRAFT'
where mode = 'FULL_PROBLEM';

-- 旧记录保留原始产物与验证报告，但统一包装为判别联合结果。
update ai_problem_generation_task
set resultJson = json_object(
        'type', mode,
        'schemaVersion', 1,
        'data', json_object(
                'artifact', resultJson,
                'legacyValidation', validationJson,
                'migratedLegacy', true
                )
                 )
where resultJson is not null
  and json_extract(resultJson, '$.schemaVersion') is null;

-- validationJson 为兼容旧数据继续保留；新任务不再写入该列。

-- 将旧的自动出题任务迁移为三个独立的 Spring AI 题目创作工作流。
-- 执行前请确认 migration_20260813_ai_problem_generation.sql 已完成。
use myoj;

alter table ai_problem_generation_task
    add column workflowStateJson json null comment '阶段断点及必要中间产物' after validationJson;

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

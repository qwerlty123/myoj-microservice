-- Run these queries while the judge load test is active.
select status, count(*) as total
from judge_task_outbox
group by status
order by status;

select status,
       count(*) as total,
       round(avg(timestampdiff(microsecond, createTime, updateTime)) / 1000, 2) as avg_end_to_end_ms,
       max(timestampdiff(second, createTime, updateTime)) as max_end_to_end_seconds
from question_submit
group by status
order by status;

select count(*) as waiting_over_60_seconds
from question_submit
where status = 0
  and createTime < now() - interval 60 second
  and isDelete = 0;

select count(*) as running_over_180_seconds
from question_submit
where status = 1
  and updateTime < now() - interval 180 second
  and isDelete = 0;

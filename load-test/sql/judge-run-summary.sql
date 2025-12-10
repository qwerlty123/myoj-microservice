SET @marker = CONCAT('LOAD_TEST_RUN_ID=', @run_id);

SELECT 'run_summary' AS section;
SELECT COUNT(*) AS submitted,
       SUM(status IN (2, 3)) AS terminal,
       ROUND(100 * SUM(status IN (2, 3)) / COUNT(*), 2) AS terminal_percent,
       SUM(status = 0) AS waiting,
       SUM(status = 1) AS running,
       SUM(status = 3) AS system_failed,
       SUM(retryCount > 0) AS submissions_retried,
       MIN(createTime) AS first_submitted_at,
       MAX(createTime) AS last_submitted_at,
       MAX(updateTime) AS last_updated_at
FROM question_submit
WHERE LOCATE(@marker, code) > 0
  AND isDelete = 0;

SELECT 'end_to_end_latency' AS section;
WITH ranked AS (
    SELECT TIMESTAMPDIFF(MICROSECOND, createTime, updateTime) / 1000.0 AS latency_ms,
           ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND, createTime, updateTime)) AS row_number_value,
           COUNT(*) OVER () AS total_rows
    FROM question_submit
    WHERE LOCATE(@marker, code) > 0
      AND isDelete = 0
      AND status IN (2, 3)
)
SELECT COUNT(*) AS terminal_samples,
       ROUND(AVG(latency_ms), 2) AS avg_ms,
       ROUND(MIN(CASE WHEN row_number_value >= CEIL(total_rows * 0.50) THEN latency_ms END), 2) AS p50_ms,
       ROUND(MIN(CASE WHEN row_number_value >= CEIL(total_rows * 0.95) THEN latency_ms END), 2) AS p95_ms,
       ROUND(MIN(CASE WHEN row_number_value >= CEIL(total_rows * 0.99) THEN latency_ms END), 2) AS p99_ms,
       ROUND(MAX(latency_ms), 2) AS max_ms
FROM ranked;

SELECT 'submission_status_distribution' AS section;
SELECT CASE status
           WHEN 0 THEN 'WAITING'
           WHEN 1 THEN 'RUNNING'
           WHEN 2 THEN 'COMPLETED'
           WHEN 3 THEN 'SYSTEM_FAILED'
           ELSE CONCAT('UNKNOWN_', status)
       END AS submission_status,
       COUNT(*) AS total
FROM question_submit
WHERE LOCATE(@marker, code) > 0
  AND isDelete = 0
GROUP BY status
ORDER BY status;

SELECT 'judge_result_distribution' AS section;
SELECT CASE
           WHEN JSON_VALID(judgeInfo) = 1
               THEN COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo, '$.message')), 'null'), 'NO_MESSAGE')
           ELSE 'INVALID_JUDGE_INFO'
       END AS judge_result,
       COUNT(*) AS total
FROM question_submit
WHERE LOCATE(@marker, code) > 0
  AND isDelete = 0
  AND status IN (2, 3)
GROUP BY judge_result
ORDER BY total DESC;

SELECT 'outbox_distribution' AS section;
SELECT CASE o.status
           WHEN 0 THEN 'PENDING'
           WHEN 1 THEN 'SENT'
           WHEN 2 THEN 'DEAD'
           WHEN 3 THEN 'DISPATCHING'
           ELSE CONCAT('UNKNOWN_', o.status)
       END AS outbox_status,
       COUNT(*) AS total,
       MAX(o.retryCount) AS max_retry_count
FROM judge_task_outbox o
JOIN question_submit q ON q.id = o.questionSubmitId
WHERE LOCATE(@marker, q.code) > 0
  AND q.isDelete = 0
GROUP BY o.status
ORDER BY o.status;

SELECT 'latest_system_errors' AS section;
SELECT id AS submission_id,
       retryCount,
       LEFT(COALESCE(lastError, ''), 240) AS last_error
FROM question_submit
WHERE LOCATE(@marker, code) > 0
  AND isDelete = 0
  AND status = 3
ORDER BY id DESC
LIMIT 20;

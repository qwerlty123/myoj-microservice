# AI 出题：HITL、Trace、评估集与上线门禁

## 标准 Human-in-the-loop 发布链

MyOJ 的 AI 出题工作流把“生成草稿”和“发布题目”分成两个 run，人工等待期间不占用线程：

```text
START
  -> generate_draft
  -> validate_draft
  -> sandbox_verify
  -> prepare_review
  -> interruptBefore(human_review)       # Redis checkpoint，状态 REVIEW_REQUIRED

POST /api/ai/generation/tasks/{taskId}/review
  -> 持久化 decision / reviewer / reviewed draft
  -> updateState(..., asNode=human_review)
  -> GraphInput.resume()
       APPROVE -> publish_question -> PUBLISHED
       REJECT  -> reject_draft     -> REJECTED
```

`prepare_review` 先把已通过确定性校验和 Java 沙箱的 artifact 保存到 MySQL，Graph 再通过
`interruptBefore("human_review")` 落 Redis checkpoint。审核接口只接受 Gateway 注入的管理员身份，并使用
`REVIEW_REQUIRED -> RUNNING` 的条件更新保证审核决定只能提交一次。人工修改后的最终草稿、审核人、意见和时间都持久化，不能只放在
HTTP 请求或进程内存里。

管理员可以修订标题、题面、标签和题解；参考代码、测试用例与资源限制属于已经通过沙箱的验证载荷，批准时必须保持不变。若这些字段
需要调整，应创建重试任务重新经过确定性校验和沙箱，不能沿用旧的 `sandboxPassed` 结论。

批准后，写操作由 Graph 的 `publish_question` 节点调用 Question Service，而不是由浏览器绕过工作流调用普通新增接口。
Question Service 在本地事务中以 `ai-authoring-task-{taskId}-publish-v1` 抢占幂等记录、锁定记录并创建题目；重试、超时回放或
checkpoint 重放只会返回原 `questionId`。拒绝分支不包含写客户端。

## Trace 契约

每条 trace 事件保存到 `ai_authoring_trace_event`。标识的粒度如下：

| 字段 | 语义 |
|---|---|
| `traceId` | 一个出题任务的完整生命周期，固定为 `authoring-task-{taskId}` |
| `runId` | 每次首次执行、故障恢复或人工审核恢复各生成一个 UUID |
| `graphThreadId` | LangGraph checkpoint thread，固定为 `authoring-task-{taskId}` |
| `graphVersion` | Graph 拓扑版本；本次为 `authoring-v2-hitl` |
| `nodeId/fromNode/toNode` | 节点执行和条件边选择 |
| `actorId` | 人工审批和发布写操作对应的管理员 ID |
| `detailJson` | 仅保存可聚合的脱敏元数据 |

事件覆盖：

| 范围 | 事件 | 主要明细 |
|---|---|---|
| Graph run | `RUN_STARTED`, `RUN_FINISHED`, `RUN_FAILED` | initial/recovery/review 模式、最终状态、错误类型 |
| Node | `NODE_STARTED`, `NODE_FINISHED` | node、耗时、repair count、结果 |
| Edge | `EDGE_ROUTED` | from/to、校验错误数、沙箱结果、审核决定 |
| LLM | `LLM_CALL` | model、prompt version、prompt/completion/total tokens、耗时 |
| Tool | `TOOL_CALL` | `code_sandbox`、用例数、错误数、耗时 |
| Checkpoint | `CHECKPOINT_INTERRUPTED`, `CHECKPOINT_RESUMED` | checkpoint ID、下一节点 |
| Human | `APPROVAL_SUBMITTED` | APPROVE/REJECT、reviewer、草稿 SHA-256、是否有意见 |
| Write | `WRITE_STARTED`, `WRITE_COMPLETED` | 操作、草稿 SHA-256、question ID、幂等标记、耗时 |

Trace 不保存完整 prompt、题面、答案、测试用例、参考代码、模型原始响应、密钥或可信身份头。内容关联只使用 SHA-256；错误只记录
异常类型，业务可见的有限错误摘要仍保存在任务表。Trace 写入失败会告警，但不会把已经幂等的发布动作变成重试风暴。

管理员可按任务读取 trace：

```http
GET /api/ai/generation/tasks/{taskId}/trace
Authorization: Bearer <admin-jwt>
```

接口沿用任务 owner 检查；其他管理员或普通用户不能用 task ID 枚举 trace。

## 评估集

[`authoring-evaluation-cases.json`](../myoj-backend-ai-service/src/test/resources/authoring-evaluation-cases.json)
包含三层信息：

1. `metadata`：固定 schema、Graph、Prompt 和语言版本，避免跨版本结果混算。
2. `policy`：20 条生成 golden cases 共用的期望结果、允许/禁止工具、期望路径、最大 Graph/模型/沙箱步数和安全约束。
3. `workflowScenarios`：批准发布、拒绝零写入、checkpoint 恢复、发布回放四个状态机专项样本。
4. `releaseGates`：CI 或演示环境评测必须满足的阈值。

项目的基准路径是：

```text
draft:   generate_draft -> validate_draft -> sandbox_verify -> prepare_review -> human_review
approve: human_review -> publish_question
reject:  human_review -> reject_draft
```

`repair_draft -> validate_draft` 是允许出现的修复环。最多 3 次修复，因此每个任务最多 4 次模型调用和 4 次沙箱调用。

## 上线门禁

当前阈值由评估集版本化管理：

| 门禁 | 阈值 |
|---|---:|
| 20 条 golden set 成功率 | `>= 90%` |
| 关键路径命中率 | `100%` |
| 禁止工具/未审批写操作 | `0` |
| checkpoint 恢复演练 | 必须通过 |
| 重复副作用 | `0` |
| 单任务模型/沙箱调用 | `<= 4 / <= 4` |
| P95 run 延迟 | `<= 120000 ms` |
| P95 total tokens | `<= 24000` |
| 成功 LLM call 缺失 usage | `0` |
| tool error rate | `<= 5%` |

发布判定以 trace 和系统事实表共同计算：路径、延迟、token 和工具错误来自 `ai_authoring_trace_event`；真正的副作用数量以
`ai_question_publish` 和 `question` 为准，不能仅凭 `WRITE_COMPLETED` 事件推断。安全门禁是硬门禁，即使任务成功率达标，只要出现
一次未审批写、禁用工具调用或同一 source task 创建多题，版本也不能上线。

[`observe_ai_authoring_release_gates.sql`](../sql/observe_ai_authoring_release_gates.sql) 提供按评测窗口聚合成功率、实际路径、未审批写、
工具 allowlist、调用预算、checkpoint 恢复、重复副作用、P95 延迟/token 和工具错误率的只读查询；CI 读取同一结果并与 JSON 阈值比较。

自动化测试分别覆盖：审批前写客户端调用数为 0、APPROVE 后发布、REJECT 零写入、相同幂等键回放返回原题目，以及 Redis
checkpoint 恢复不重复执行已经完成的生成节点。真实模型的 golden set、P95 和 token 门禁应在具有模型与沙箱凭据的预发布环境执行。

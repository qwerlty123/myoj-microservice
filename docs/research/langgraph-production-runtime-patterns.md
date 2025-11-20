# LangGraph 生产运行模式调研

调研日期：2026-08-13

## 结论

官方 LangGraph 实际分为两层：

1. 开源 LangGraph 是嵌入应用的图执行库，负责图、checkpoint、interrupt/resume 和节点执行，但不自带完整的 HTTP 服务、持久任务队列、Worker 调度与自动扩缩容。
2. LangSmith Deployment 的 Agent Server 是生产运行平台，在开源图执行能力之外提供 Threads、Runs、持久任务队列、Worker、流式输出和扩缩容。

官方 Agent Server 的关键设计是：

> PostgreSQL 保存所有持久事实；Redis 只承担跨实例唤醒、取消、心跳和实时流式通信。

因此 Redis 不是运行开源 LangGraph 的前提，也不应该成为工作流状态的第二个真相来源。

## 1. 开源 LangGraph 如何运行

开源库由应用直接调用 `invoke`、`ainvoke` 或 stream 方法。应用在编译 Graph 时注入 checkpointer；每个 `thread_id` 对应一条可持续恢复的工作流线程。

Checkpoint 用于：

- 在图步骤结束后保存 Graph State；
- Human-in-the-loop 中断和恢复；
- 服务崩溃后从最近成功步骤继续；
- 查看历史状态和 time travel；
- 保存并行 super-step 中已经成功的 pending writes，避免恢复时重复执行成功节点。

`InMemorySaver` 只适合开发测试，进程重启后状态会消失。官方 Python 实现生产环境主要推荐 PostgreSQL checkpointer，也提供 MongoDB、Redis、Cosmos DB 等 Adapter。Redis Saver 是一种可选 checkpoint 后端，不等于任务队列，也不是必需依赖。

来源：

- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [Checkpointer integrations](https://docs.langchain.com/oss/python/integrations/checkpointers/index)
- [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)

## 2. 官方 Agent Server 如何运行

Agent Server 把生产运行模型显式拆成：

```text
Client
  -> API Server
  -> durable Run record / queue
  -> Queue Worker acquires lease
  -> executes Graph
  -> writes checkpoints
  -> updates Run status
```

一个 Run 是一次 Graph 调用；一个 Thread 是保存连续 Graph State 的容器。Worker 获取 Run 租约后执行 Graph，同一 Thread 同一时刻最多运行一个 Run。

官方支持三种部署模式：

1. Single host：API Server 在同一部署中管理任务队列，适合开发和低流量。
2. Split API and queue：API 与 Queue Worker 分开部署和扩容。
3. Distributed runtime：进一步拆分编排和节点执行，适合高并发。

来源：

- [Agent Server runtime architecture](https://docs.langchain.com/langsmith/agent-server)
- [Runs](https://docs.langchain.com/langsmith/runs)

## 3. PostgreSQL 与 Redis 的真实职责

### PostgreSQL

官方 Agent Server 使用 PostgreSQL 保存：

- assistants；
- threads；
- runs；
- checkpoint；
- long-term store；
- 持久任务队列状态；
- 并发租约和 exactly-once Run attempt 控制。

Worker 真正执行的 Run 内容始终从 PostgreSQL 读取。官方利用 PostgreSQL MVCC 控制每次 Run attempt 只被一个 Worker 处理。

### Redis

Redis 只保存临时元数据和跨实例通信：

- 通知 Worker 有新任务；
- API Server 与 Worker 之间的流式事件 Pub/Sub；
- 取消正在执行的 Run；
- Worker 心跳；
- 临时尝试信息。

官方明确说明用户数据和 Run 数据不持久化在 Redis。Redis 暂时不可用可能影响实时通信和调度，但不会丢失持久 Run 状态。

来源：

- [Agent Server task queue](https://docs.langchain.com/langsmith/agent-server)
- [Self-host standalone server](https://docs.langchain.com/langsmith/deploy-standalone-server)
- [Scalability and resilience](https://docs.langchain.com/langsmith/scalability-and-resilience)

## 4. 崩溃恢复

官方运行模式包含两个恢复层次：

1. Graph checkpoint：节点失败或进程中断后，从最近成功 Graph 步骤继续。
2. Run queue recovery：Worker 持续写心跳；Worker 硬崩溃后，sweeper 找出心跳超时的 Run，重新放回队列。

Checkpoint 只代表“能够从哪里继续”，不会自动唤醒新的 Worker。自托管开源库时，应用仍然需要执行器、Run 记录、租约和恢复扫描。

## 5. 外部副作用仍要幂等

Checkpoint 不能天然保证跨服务调用只执行一次。例如：

```text
Question Service 已创建题目
  -> AI 进程在写 checkpoint 前崩溃
  -> Graph 恢复后再次执行 publish 节点
```

因此模型调用、Judge 执行和正式发布都应使用稳定 execution key：

```text
taskId + nodeName + revision + inputHash
```

正式发布还应由 Question Service 保存唯一 idempotency key，并在重复请求时返回同一个 `questionId`。

来源：[LangGraph Functional API and idempotency](https://docs.langchain.com/oss/python/langgraph/functional-api)

## 6. LangGraph4j 的差异

LangGraph4j 是嵌入 Spring Boot 的 Java 图执行库，不是 Java 版本的完整 Agent Server。它提供：

- StateGraph 与条件边；
- checkpoint 和恢复；
- MySQL、PostgreSQL、Redis 等 Saver；
- interrupt/resume；
- Spring AI 集成；
- 异步与流式执行；
- Studio 调试界面。

它不直接提供与官方 Agent Server 等价的持久 Run 队列、分布式 Worker 租约、心跳 sweeper 和自动扩缩容。这些需要应用自行实现。

LangGraph4j 的 Redis Saver 与 Redis Stream 是两件不同的事情：前者保存 checkpoint，后者可以由应用用来唤醒或分发任务。

来源：

- [LangGraph4j repository](https://github.com/langgraph4j/langgraph4j)
- [LangGraph4j Studio](https://langgraph4j.github.io/langgraph4j/main/studio/)

## 7. 对 MyOJ 的映射

MyOJ 当前属于低流量、自托管、嵌入式 LangGraph4j 场景，建议先实现与官方 Single host 类似的模式：

```text
HTTP Controller
  -> MySQL 创建 Task/Run
  -> 同一 AI Service 内的 Worker 获取 MySQL 租约
  -> LangGraph4j 执行
  -> MySQL Saver 保存 checkpoint
  -> 前端轮询 Run/Task 状态
```

建议数据映射：

| 官方概念 | MyOJ 实现 |
|---|---|
| Thread | `ai_authoring_task`，对应一次持续出题工作流 |
| Run | `ai_authoring_run`，对应开始、恢复或重试执行 |
| Checkpoint | LangGraph4j MySQL Saver 表 |
| Assistant | 固定的 authoring graph + prompt/model/workflow version |
| Worker lease | MySQL CAS 更新 `lock_owner`、`lease_until` |
| Run recovery | Spring Scheduler 扫描超时租约 |
| Stream | 第一版前端轮询，后续可增加 SSE |

第一版不需要为 Authoring 链路新增 Redis。已有 Redis 可以继续服务现有 AI Feedback，不与新 Graph 状态混用。

当出现以下需求时，再参考官方 Split API and queue 模式加入 Redis：

- AI API 与 Worker 分开部署；
- 多个 Worker 横向扩容；
- 需要跨实例 SSE；
- 需要低延迟取消；
- 数据库轮询成为瓶颈。

届时职责应保持：

```text
MySQL：Task、Run、checkpoint、租约、幂等结果，唯一持久事实
Redis：唤醒、取消、心跳、实时事件，只保存临时信号
Worker：从 MySQL 领取 Run 后执行 LangGraph4j
```

## 最终建议

MyOJ 第一版采用：

```text
LangGraph4j
+ Spring AI
+ MySQL Checkpoint Saver
+ ai_authoring_task / ai_authoring_run
+ MySQL lease
+ Spring TaskExecutor
+ Spring Scheduler recovery
```

不要在第一版把 Redis Stream 设计成工作流执行状态源。如果后续扩成多 Worker，再加入 Redis 作为临时协调层，同时保持 MySQL 为唯一持久事实。

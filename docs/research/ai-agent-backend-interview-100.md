# MyOJ：AI 应用 / Agent / Java 后端面试高频 100 题

> 整理日期：2026-09-01  
> 适用岗位：Java 后端、AI 应用开发、Agent 后端、LLM 应用工程  
> 排序原则：越靠前越值得优先准备。1～40 是项目必问与高压追问，41～80 是 AI 工程和后端高频题，81～100 是中间件、沙箱、测试与综合设计题。

这份清单不是把通用八股机械拼在一起，而是依据三类证据交叉筛选：2025～2026 年牛客相关面经的真实问法、MyOJ 当前 AI 模块源码中的可追问实现，以及 Spring AI、LangGraph4j、MySQL、Redis、JDK 的官方资料。每道题后面的“追问”就是面试官最可能继续深挖的方向。

## 一、项目总览与架构取舍（1～10）

1. 请你用 3 分钟讲清楚 MyOJ 的 AI 模块：解决什么问题、服务谁、核心链路和最终效果是什么？——追问：不要罗列技术栈，要给出输入、处理、输出和可量化结果。
2. 为什么把 AI 能力拆成独立的 AI Service，而不是直接写在 Question Service 或 Judge Service 里？——追问：服务边界、故障隔离、独立扩缩容、模型依赖和数据一致性。
3. 学生发送一句“为什么这个样例不通过”后，请从 Gateway 一直讲到 LLM、工具、沙箱、MySQL 和 SSE 返回的完整调用链。——追问：每一步可能失败在哪里。
4. 管理员创建一道 AI 题目后，请完整讲解“生成—校验—沙箱验证—修复—人工审核—发布”的状态流转。——追问：哪些步骤可重试，哪些步骤有副作用。
5. 你的学生辅导为什么可以称为 Agent，而不只是套壳聊天机器人？管理端出题又为什么更接近 Workflow？——追问：自主决策、工具调用、循环、状态和确定性边界。
6. 为什么学生辅导和管理端出题没有共用一套 Agent 编排？——追问：交互式低延迟与长任务可恢复工作流的差异。
7. 这个项目中你认为技术含量最高的一点是什么？如果只能保留一个亮点，你保留什么？——追问：必须落到源码、故障场景和验证证据。
8. 当前实现最大的技术债或风险是什么？为什么当时接受它？——追问：单实例出题、真实模型评测、输入安全、工具能力和前端能力边界。
9. 如果并发量突然增长 10 倍，最先扛不住的是模型、线程池、数据库、SSE 连接还是代码沙箱？——追问：容量估算、背压和扩容顺序。
10. 这个 AI 模块明确没有做什么？为什么没有上 RAG、多 Agent、MQ Worker、向量库和自动发布？——追问：MVP 边界与避免过度设计。

## 二、LLM、Prompt 与 Spring AI（11～20）

11. 一次 LLM 请求在你的系统中包含哪些消息、模型参数和上下文？返回结果又如何落库？——追问：system/user/history/tool result、token usage、traceId 和 promptVersion。
12. Transformer 的自注意力大致解决什么问题？上下文窗口、token 和位置编码为什么会影响 AI 应用设计？——追问：不要推公式，联系长题面、代码和历史消息截断。
13. Spring AI 中 ChatModel、ChatClient、Prompt、Message 和 Advisor 分别承担什么职责？你为什么主要使用 ChatClient？——追问：高层封装与底层控制的取舍。
14. Spring AI 的工具调用循环是怎样工作的？模型是真正执行了 Java 方法吗？——追问：工具定义、JSON Schema、模型返回 tool call、应用执行、结果回填和终止条件。
15. 为什么选 Spring AI，而不是 LangChain4j 或直接调用 OpenAI 兼容 HTTP API？——追问：Spring 生态集成、可控性、学习成本、版本兼容和框架锁定。
16. normal 模式与 agent 模式有什么本质区别？为什么不能永远把全部工具暴露给模型？——追问：延迟、成本、准确率、攻击面和上下文膨胀。
17. 你的 system prompt 如何约束“只给提示、不直接泄露完整答案”？仅靠 Prompt 为什么不可靠？——追问：策略、确定性校验、输出审查和产品权限共同兜底。
18. 为什么 Prompt 要存数据库并带版本号？如何做灰度、A/B 实验、回滚和历史结果复现？——追问：模型版本、Prompt 版本、Graph 版本和评测集版本如何绑定。
19. 管理端用结构化输出生成题目时，模型返回非法 JSON、缺字段或字段类型错误怎么办？——追问：schema、转换器、确定性校验、修复提示与重试上限。
20. 模型该如何选型？为什么 temperature 设为较低值？如果主模型超时、限流或涨价，如何降级？——追问：质量、延迟、token 成本、工具能力和 fallback 策略。

## 三、Agent、工具调用、记忆与流式交互（21～30）

21. ReAct、Plan-and-Execute 和固定 Workflow 有什么区别？MyOJ 两条 AI 链路分别更接近哪一种，为什么？——追问：任务可预测性、工具数量、执行时长和错误恢复。
22. 模型如何决定直接回答还是调用工具？工具的名称、描述和参数 schema 会怎样影响选择准确率？——追问：误调用、漏调用和工具冲突如何评测。
23. 你给辅导 Agent 提供了哪些工具？分析提交、生成测试用例、运行代码和联网搜索各自的输入输出是什么？——追问：哪些是只读工具，哪些具备高风险副作用。
24. 为什么 Agent 最多执行 4 步？怎样防止死循环、重复调用同一工具和 token 失控？——追问：step budget、tool budget、去重、超时和提前停止。
25. 模型生成的工具参数能直接信任吗？你如何做类型、长度、枚举、权限和业务约束校验？——追问：越权 questionId、超长代码、恶意输入和未知工具名。
26. 工具超时、沙箱报错、返回空结果或部分成功时，Agent 应该重试、换工具、降级回答还是终止？——追问：错误分类、重试退避、幂等和用户可见提示。
27. 多轮会话的短期记忆如何实现？为什么只取最近 N 条消息，而不是把全部历史都发给模型？——追问：窗口截断、摘要、事实记忆、工具消息和跨题污染。
28. 同一用户连续点击两次发送，或者网络重试命中两个 AI 实例时，如何保证只生成一次回答？——追问：clientMessageId、MySQL claim、COMPLETED/BUSY、请求 token 和版本栅栏。
29. SSE 为什么适合这类 AI 回复？tool、delta、meta、done、error 五类事件分别表达什么？——追问：事件顺序、重连、代理缓冲和浏览器断开。
30. SSE 连接断开后，为什么要取消 Future 并释放会话 claim？Thread.interrupt 一定能停止模型 HTTP 调用吗？——追问：协作式取消、资源泄漏、迟到结果和最终写入栅栏。

## 四、LangGraph4j 与 AI 出题工作流（31～40）

31. 为什么管理端出题要引入 LangGraph4j，而不是写一个普通的 if/else 或责任链？——追问：显式状态、条件边、checkpoint、恢复、可测试性与引入成本。
32. 请画出 QuestionAuthoringGraph 的所有节点和边，并说明 AuthoringState 中每个关键字段由谁读、谁写。——追问：generate、validate、verify、repair、human_review、publish、reject、fail。
33. 为什么题目字段完整性、测试用例数量、Java Main 入口和判题配置必须由确定性 Validator 校验，而不能让 LLM 自评？——追问：模型幻觉与可重复验证。
34. 为什么生成参考代码后还必须进入真实代码沙箱逐用例验证？——追问：编译错误、运行错误、超时、输出差异以及“模型说正确”不等于正确。
35. 自动修复为什么限制最多 3 次？修复 Prompt 应携带哪些错误证据，怎样避免模型在原地打转？——追问：失败指纹、调用预算、成本和人工兜底。
36. Redis checkpoint 保存什么？服务在生成后宕机并重启时，如何从已完成节点之后继续且不重复调用模型？——追问：threadId、state snapshot、启动扫描和恢复测试。
37. Human-in-the-loop 是怎样实现的？为什么 interruptBefore human_review 比线程阻塞等待管理员更合理？——追问：不占线程、跨进程恢复、审核数据持久化和状态条件更新。
38. 管理员批准后调用 Question Service 发布，如果“题目已写入但 HTTP 响应丢失”，重试如何避免创建两道题？——追问：稳定幂等键、Question Service 本地事务和原 questionId 回放。
39. Graph 拓扑升级后，旧 checkpoint 为什么可能不能直接恢复？Graph version、Prompt version 和数据迁移如何设计？——追问：兼容恢复、冻结旧版本、重跑新任务和审计。
40. 当前出题任务为什么限定单 AI Service 实例？如果改为多实例，如何设计任务租约、心跳、抢占和 fencing token？——追问：数据库条件更新、Redis 锁的局限与重复执行。

## 五、AI 安全、RAG、评测与可观测性（41～50）

41. 现在如何识别 prompt injection？关键字规则能防住“忽略之前指令”之外的变体、编码和间接注入吗？——追问：分层防御、上下文隔离、模型分类器和红队集。
42. 如何防止模型输出系统 Prompt、隐藏答案、用户代码或其他用户的会话内容？——追问：数据隔离、最小上下文、输出检测、日志脱敏和权限控制。
43. 工具安全应该如何做 allowlist、最小权限和人工确认？如果未来增加“删除题目”工具，哪些控制必须前置？——追问：风险分级、审批、审计、returnDirect 和可撤销性。
44. 你怎样评测一个 AI 出题版本能否上线？20 条 golden cases 应统计哪些指标？——追问：成功率、关键路径、非法工具、重复副作用、P95 延迟、token 和工具错误率。
45. 如何评测辅导回答是否真的有帮助，而不是“看起来很像答案”？——追问：证据一致性、提示泄露率、用户下一次提交通过率、人工评分和成对比较。
46. Trace 为什么同时需要 traceId、runId、graphThreadId、模型/Prompt/Graph 版本？为什么不记录完整 Prompt 和代码？——追问：跨恢复串联、隐私、成本、哈希指纹和可审计性。
47. 你会为 AI 服务定义哪些 SLI/SLO 和告警？——追问：首 token 延迟、完整响应延迟、成功率、429、tool error、token 成本、checkpoint 恢复和沙箱通过率。
48. 当前项目为什么没有 RAG？如果要让辅导 Agent 检索题解、知识点和历史错因，你会怎样接入？——追问：切分、元数据过滤、召回、重排、引用和权限。
49. 向量检索的 embedding、余弦相似度、召回率和精确率分别是什么？为什么生产 RAG 常用关键词与向量混合检索再 rerank？——追问：难例、热更新、索引版本和离线评测。
50. 本地 Function Calling、MCP、Skills 和多 Agent 分别解决什么问题？MyOJ 什么时候值得引入，什么时候只是堆概念？——追问：标准化互操作、工具渐进披露、角色边界和通信成本。

## 六、Java 并发、线程池与 JVM（51～60）

51. AI Chat 线程池为什么配置 core=8、max=8、queue=32？请结合平均响应时间估算吞吐和排队时间。——追问：Little 定律、下游限流、CPU 密集与 IO 密集。
52. ThreadPoolExecutor 的 7 个核心参数是什么？任务从提交到执行、入队和拒绝的完整流程是什么？——追问：为什么这里必须使用有界队列。
53. 线程池满时你选择哪种拒绝策略？为什么 AI 接口更适合快速返回 429，而不是 CallerRunsPolicy 拖住 Web 线程？——追问：背压、雪崩和用户重试。
54. Future.get(timeout)、Future.cancel(true) 和 Thread.interrupt 分别做什么？为什么取消是“请求中断”而不是强制杀线程？——追问：阻塞 IO、响应式客户端和中断标记恢复。
55. AtomicBoolean 和 AtomicReference 如何保证 closed、task、claim 的并发可见性与原子更新？CAS 有什么 ABA 问题？——追问：compareAndSet 与 volatile 语义。
56. 多个线程向同一个 SseEmitter 写事件为什么需要同步？如果不加锁可能出现什么问题？——追问：事件交错、完成竞态和重复关闭。
57. ConcurrentHashMap 能否解决多实例任务互斥？为什么进程内 activeTasks 与数据库 claim/分布式租约不是一回事？——追问：JVM 边界和故障后状态丢失。
58. HashMap 1.7 与 1.8 的主要差异是什么？ConcurrentHashMap 如何保证并发安全？——追问：数组+链表/红黑树、扩容、CAS 和锁粒度。
59. AI 服务发生 OOM 时，你会区分哪些内存区域和泄漏类型？如何通过 GC 日志、heap dump 和对象引用链定位？——追问：长会话、SSE、线程池队列和大字符串。
60. 线上 CPU 100%、响应变慢但错误率不高，你会按什么顺序排查？——追问：top、线程 ID 转十六进制、jstack、死循环、锁竞争、GC 和下游慢调用。

## 七、Spring、微服务、网关与网络（61～70）

61. Spring Bean 从实例化到销毁经历哪些阶段？AI Client、线程池和定时恢复器分别适合什么作用域？——追问：依赖注入、初始化回调和优雅关闭。
62. @Transactional 为什么依赖代理？同类方法自调用、异步线程和远程 Feign 调用为什么不会自动加入同一个事务？——追问：事务边界与跨服务一致性。
63. Spring Boot 自动配置的原理是什么？如果项目里存在多个 ChatModel、ObjectMapper 或 Executor，如何避免注入歧义？——追问：条件注解、Bean 名称与 Qualifier。
64. AI Service 如何通过 Nacos 和 Feign 找到 Question Service？服务发现、负载均衡、超时与重试分别在哪一层生效？——追问：配置中心与注册中心区别。
65. Gateway 校验 JWT 后注入可信用户头，怎样防止浏览器自己伪造这个头绕过鉴权？——追问：先删除外部头、内部签名/共享令牌、网络隔离和恒定时间比较。
66. 为什么模型调用、代码执行和发布接口不能无脑开启自动重试？——追问：幂等性、副作用、重试风暴、指数退避和熔断降级。
67. SSE、WebSocket、普通轮询和长轮询各自适合什么场景？为什么当前回答流选择 SSE？——追问：单向流、HTTP 兼容性、连接数和断线重连。
68. 浏览器访问一个 HTTPS API，从 URL 输入到收到响应经历了什么？——追问：DNS、TCP、TLS、HTTP、Gateway、服务发现和连接池。
69. TIME_WAIT 和 CLOSE_WAIT 分别说明什么？大量 AI 流式连接关闭后出现它们该如何排查？——追问：主动/被动关闭、连接泄漏和 keep-alive。
70. 如何为 AI 接口做用户级、题目级和全局限流，并在模型不可用时优雅降级？——追问：令牌桶、并发隔离、429、静态提示和功能开关。

## 八、MySQL、事务、锁与幂等（71～80）

71. 为什么聊天会话、消息、违规记录、出题任务、发布记录和 Trace 要拆成不同表？——追问：领域边界、冷热数据、查询模式和保留周期。
72. getOrCreateSession 在两个并发请求下可能同时查不到记录，怎样依靠唯一索引和异常回查避免重复会话？——追问：先查后插的竞态。
73. claimSession 为什么需要事务和 SELECT ... FOR UPDATE？锁住的是记录、间隙还是索引范围？——追问：索引是否命中与隔离级别。
74. 清空会话与正在生成的回答并发时，sessionVersion 和 requestToken 如何形成 fencing，阻止迟到回答写回？——追问：仅仅 cancel 线程为什么不够。
75. clientMessageId 幂等与“同一会话只允许一个 in-flight 请求”是两个什么维度的问题？——追问：请求去重、互斥和已完成结果回放。
76. InnoDB 的 MVCC 如何实现一致性读？READ COMMITTED 与 REPEATABLE READ 对本项目有哪些影响？——追问：Read View、undo log、当前读和幻读。
77. 多个事务更新会话、消息和发布记录时如何避免死锁？发生死锁后应该怎样记录和重试？——追问：固定加锁顺序、缩短事务、索引和幂等重试。
78. 请为“按 userId+questionId 查会话”“按 sessionId 倒序取最近消息”“按 status+updateTime 扫描遗留任务”设计联合索引。——追问：最左前缀、覆盖索引和 explain。
79. Prompt、题面、代码、toolEvents 和 trace detail 这些大字段用 TEXT/JSON 存储有什么代价？如何避免列表查询回表读取大字段？——追问：垂直拆表、摘要字段和冷热归档。
80. AI Service 调 Question Service 发布属于跨服务写操作，当前用幂等接收方解决了什么、没解决什么？何时应该升级为 Outbox/Saga？——追问：本地事务、消息投递和最终一致性。

## 九、Redis、缓存与消息队列（81～90）

81. 为什么 LangGraph checkpoint 放 Redis，而任务状态和审核结果放 MySQL？两者分别充当什么事实来源？——追问：运行态快照与业务审计状态。
82. checkpoint 使用 Redis AOF、noeviction 且不设 TTL 的理由是什么？这些配置各自仍有哪些风险？——追问：RPO、磁盘增长、主从切换和备份。
83. 如果 Redis checkpoint 已写成功、MySQL 阶段字段却没更新，或者反过来，恢复器应相信谁？——追问：幂等节点、对账、状态机单调性和修复策略。
84. Redis 分布式锁的正确加锁与解锁方式是什么？为什么不能简单 SETNX 后直接 DEL？——追问：唯一随机值、Lua 原子校验、租约续期和 Redlock 争议。
85. 缓存穿透、击穿、雪崩和数据库缓存一致性分别怎么处理？哪些配置数据适合缓存，哪些不能把 Redis 当最终事实？——追问：空值、布隆过滤器、互斥重建、随机 TTL 和失效策略。
86. 当前 OJ 判题为什么适合通过 RabbitMQ 异步解耦？从提交到消费、沙箱执行和结果落库的链路怎样保证状态正确？——追问：削峰、ack、超时和消费幂等。
87. MQ 的 at-most-once、at-least-once 和 exactly-once 分别意味着什么？业务上如何用幂等键实现“效果上的 exactly-once”？——追问：重复消息和消费者崩溃窗口。
88. RabbitMQ、Kafka 和 RocketMQ 各自更适合什么场景？MyOJ 判题和 AI 长任务为什么可能做出不同选择？——追问：吞吐、延迟、路由、顺序、回溯和运维成本。
89. 判题或 AI 任务消费失败时，如何设计重试队列、指数退避、死信队列和人工补偿？——追问：毒消息、最大重试次数和可观测字段。
90. 如果把 AI 出题从单实例线程池迁移成 MQ Worker，API、任务表、消费者租约、checkpoint 和取消语义要怎样改？——追问：控制面与执行面分离。

## 十、代码沙箱、测试、排障与综合设计（91～100）

91. AI Service 调代码沙箱为什么要做 HMAC 签名？签名内容应该包含哪些字段，双方如何避免序列化不一致？——追问：method、path、body hash、timestamp、nonce 和 key rotation。
92. 只有 HMAC 还可能遭遇重放攻击，如何用 timestamp、nonce、过期窗口和请求记录防重放？——追问：时钟漂移、存储成本和幂等键。
93. 为什么不能在 AI Service 宿主机直接 Runtime.exec 用户代码？Docker/隔离沙箱至少要限制哪些资源和系统能力？——追问：CPU、内存、进程数、文件、网络、syscall、只读文件系统和非 root。
94. 沙箱返回“运行成功”就能说明答案正确吗？如何处理换行、尾部空格、浮点误差、多组用例、TLE、MLE 和非零退出码？——追问：执行事实与判题 verdict 的边界。
95. LLM 输出具有随机性，单元测试如何稳定？哪些层应该 mock，哪些层必须用真实 Redis、MySQL、沙箱或模型做集成评测？——追问：契约测试、Testcontainers、golden set 和预发布门禁。
96. 你会设计哪些故障注入测试来证明系统可恢复？——追问：模型超时、节点后宕机、Redis 短暂不可用、SSE 断开、发布响应丢失和重复审核。
97. 如何对 AI Chat 做压测？除了 QPS，还必须观察哪些指标，如何避免压测费用和真实模型限额失控？——追问：并发 SSE、首 token/P95、队列深度、429、token 和沙箱并发。
98. 请现场实现一个线程安全或至少 O(1) 的 LRU Cache，并说明它可否直接用于会话记忆缓存。——追问：HashMap+双向链表、并发、容量和淘汰回调。
99. 写一条 SQL：统计最近 7 天每个模型的请求量、成功率、平均延迟和 P95 延迟；数据量很大时如何优化？——追问：条件索引、预聚合、窗口函数和监控时序库。
100. 现在让你从零设计一个支持 10 万用户的 AI OJ：既能流式辅导，又能异步生成题目和安全执行代码，你会如何拆服务、存状态、限流、恢复、评测和控制成本？——追问：请先给容量假设，再画控制面、Agent 面、确定性判题面、沙箱面和数据面。

## 建议的准备顺序

1. 先把 1～10 练到能在 3～5 分钟内画图讲清楚。
2. 再逐题准备 11～50，每题至少能说出“当前实现、为什么这样做、缺点、下一版方案”。
3. 51～90 不要背定义，要能回到 MyOJ 的线程池、数据库 claim、Redis checkpoint、Gateway 和 RabbitMQ 链路。
4. 91～100 用白板或纸笔演练，尤其是故障恢复、LRU、SQL 和系统设计。

## MyOJ 源码定位

- 学生聊天编排：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/chat/service/AiChatService.java
- Tutor Agent：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/chat/agent/QuestionTutorAgent.java
- 工具实现：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/chat/tools/TutorToolService.java
- 聊天幂等与单飞：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/chat/repository/AiChatRepository.java
- 出题状态图：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/authoring/graph/QuestionAuthoringGraph.java
- 结构化生成：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/authoring/graph/SpringAiAuthoringDraftModel.java
- 任务与恢复：myoj-backend-ai-service/src/main/java/com/qwerlty/myojbackendaiservice/authoring/service/AuthoringTaskService.java
- 服务说明：myoj-backend-ai-service/README.md
- HITL、Trace 和评测门禁：docs/ai-authoring-hitl-trace-gates.md

## 参考依据

### 牛客真实问法样本

- [字节大模型应用开发一面：项目、代码执行安全、失败修复与人工兜底](https://www.nowcoder.com/discuss/916420927619928064)
- [Agent 面试准备：架构取舍、RAG、工具容错、上下文和线上排障](https://ac.nowcoder.com/discuss/1665488?type=0)
- [AI-Agent 后端三场连面：多工具、向量库、并发会话、MVCC、线程池与 Linux 排障](https://www.nowcoder.com/discuss/919608103723622400)
- [腾讯/百度大模型与 Agent 面经：多 Agent、恢复、RAG 热更新、上下文、工具选择](https://www.nowcoder.com/discuss/878600528970735616)
- [Java 后端与 AI 工程高频总结](https://www.nowcoder.com/discuss/864594486704291840)
- [阿里后端/后端 AI：Java、Spring、Redis、MySQL、Agent、MCP 与系统设计](https://www.nowcoder.com/discuss/923310928014082048)
- [AI 应用与后端项目追问：RAG、Agent、LangGraph、Memory、缓存与 MQ](https://www.nowcoder.com/discuss/889150185626955776)

牛客内容用于归纳“出现过的问法与追问顺序”，不作为技术事实的唯一依据。帖子可能包含候选人个人转述，因此具体原理以下列官方资料与项目源码为准。

### 官方技术资料

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Structured Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)
- [LangGraph4j 官方仓库](https://github.com/langgraph4j/langgraph4j)
- [MySQL 8.4 Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [Redis Distributed Locks](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/)
- [JDK 17 ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)


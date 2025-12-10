# 软件、后端与 AI 应用项目的 STAR 简历 bullet 写法

> 调研日期：2026-08-15  
> 调研问题：如何把 MyOJ 项目压缩成 3 条后端、2 条 AI 应用方向的高质量简历 bullet。  
> 资料口径：写法原则优先采用 Google 招聘资料和高校职业中心原始指南；公开简历与项目句式只作为范例，不能把其中的技术或数字移植到 MyOJ。

## 1. 结论先行

1. **STAR 是信息采集框架，不是要求在简历里依次写四句话。** Columbia 的官方职业规划指南建议先用 STAR 梳理经历，再把项目目的、范围、个人动作和成果压成 bullet；CMU-SV 则把成稿公式概括为 `Action Verb + Context + Result`。因此简历里应把 Situation/Task 压成一个短语，把主要篇幅留给 Action 和 Result。[Columbia Career Planning Guide](https://www.careereducation.columbia.edu/sites/default/files/cce_cpg_16-17.pdf)、[CMU-SV Resume Guide](https://www.sv.cmu.edu/current-students/career-services/jobs-and-internships/create-your-resume.html)
2. **MyOJ 的五条内容应各证明一种不同能力，而不是五次罗列技术栈。** 推荐依次证明：可靠异步判题、沙箱安全、性能与可观测性、RAG 错题反馈、AI 出题质检。每条只保留最能区分候选人的 1 个问题、1 组关键机制和 1 组证据。
3. **数字必须带口径或比较基线。** Google 的官方简历资料明确要求指标既有数字也有上下文；Yale 进一步建议提供比较基线，让招聘者能解释指标。后端优先写 QPS、p95/p99、错误率、积压恢复时间和故障测试结果；AI 应用优先写评测集规模、任务正确率/拒绝率、引用有效率、验证通过率、人工采纳率、延迟和 Token/成本。[Google Resume Overview](https://services.google.com/fh/files/misc/resumetipshandout2016.pdf)、[Yale: Writing Impactful Resume Bullets](https://ocs.yale.edu/resources/writing-impactful-resume-bullets/)
4. **没有实测结果时，可以写已验证的绝对规模或功能结果，但不能编造提升百分比。** 例如“三语言容器执行”“30 篇知识卡”“故障注入 N 次无消息丢失”都可以在证据充分时写；“性能提升 40%”则必须有同环境、同脚本的前后对照。University of Michigan 还特别提醒，生成式 AI 产出的简历句子只能作为起点，必须改成准确、属于自己的表述。[University of Michigan Resume Resources](https://careercenter.umich.edu/article/resume-resources)
5. **面向后端岗位时把 3 条后端放前，面向 AI 应用岗位时把 2 条 AI 放前。** Berkeley 与 CMU-SV 都建议根据真实职位描述选择关键词、技能与项目成果，而不是向所有岗位投同一份内容。[Berkeley Career Engagement](https://www.career.berkeley.edu/prepare-for-success/resumes/)、[CMU-SV Resume Guide](https://www.sv.cmu.edu/current-students/career-services/jobs-and-internships/create-your-resume.html)

## 2. 从 STAR 到一条技术简历 bullet

### 2.1 四个字段先写全

| STAR 字段 | 对技术项目应回答的问题 | MyOJ 可用信息类型 |
| --- | --- | --- |
| S：情境 | 原系统有什么风险、瓶颈或用户痛点？ | 判题消息可能丢失/重复；不可信代码需要隔离；LLM 反馈可能幻觉或泄露答案 |
| T：任务 | 你负责达到什么明确目标或约束？ | 可靠投递、受限执行、可追溯反馈、AI 产物必须经过确定性验证和人工审核 |
| A：行动 | 你本人设计/实现了什么，如何做？ | Outbox/确认与幂等；容器安全限制；RAG、Tool Calling、状态机、评测与监控 |
| R：结果 | 哪个可验证指标、范围或交付结果因此改变？ | QPS/p95/错误率；故障恢复；安全用例；AI 评测；采纳率；时间或成本 |

STAR 的标准含义以及“结果尽量量化”可由 UConn 官方说明交叉核验；但成稿不应机械写成 S、T、A、R 四段。[UConn: Types of Questions](https://career.uconn.edu/types-of-questions/)

### 2.2 推荐成稿公式

```text
[强动作动词] [系统/能力]，通过 [本人实现的 2–3 个关键机制] 解决 [约束或问题]；
在 [数据集/压测/故障测试口径] 下，将 [指标] 从 [基线] 优化至 [结果] / 达成 [绝对结果]。
```

如果一句过长，可以采用以下顺序，但仍保持一个 bullet：

```text
[动作 + 技术对象] + [how：核心机制] + [why：解决的问题] + [result：带口径的结果]
```

这与 Yale 的 `Action + Project + Result`、Michigan 的 `Action Verb + What + How/Why/Impact`、CMU 工程学院的 `Action Verb + Context + Result` 三种官方公式一致。[Yale](https://ocs.yale.edu/resources/writing-impactful-resume-bullets/)、[Michigan](https://careercenter.umich.edu/article/resume-resources)、[CMU College of Engineering Resume Guide](https://www.cmu.edu/career/documents/sample-resumes-cover-letters/resumeguide-collegeofengineeringgraduatestudents-2019.pdf)

### 2.3 压缩规则

- Situation/Task 只保留能解释技术决策的约束，不写项目背景故事。
- Action 必须体现个人贡献，用“设计、实现、重构、构建、建立、验证”等明确动词，避免“负责、参与、了解”。
- Result 优先写“基线 → 结果”；没有基线时写在明确测试条件下的绝对结果。
- 每条只讲一个主结果，技术栈嵌入“如何做”，不要在句尾堆十个名词。
- 控制为一句、通常 1–2 行；UConn 官方 bullet 指南给出的也是一句、通常 1–2 行，并要求以强动作动词开头。[UConn: Writing Bullet Points](https://career.uconn.edu/writing-bullet-points/)

## 3. MyOJ 五条 bullet 应分别证明什么

下表不是最终简历文案，而是写作与补测清单。方括号数字必须用测试报告、数据库查询或代码事实补齐。

| 顺序 | 方向与主张 | S/T 应压缩成 | A 中值得保留的机制 | R 应优先补测/取证 |
| --- | --- | --- | --- | --- |
| 1 | 后端：可靠异步判题 | 提交入库后，消息发送、消费与服务故障之间存在丢失、重复和状态不一致风险 | Transactional Outbox、publisher confirm/return、消费幂等、CAS/租约、重试与补偿；只选项目中确已实现的机制 | 故障注入 `[N]` 次消息丢失数/重复副作用数；`[RPM]` 下积压峰值与恢复时间；最终状态一致率 |
| 2 | 后端：不可信代码沙箱 | OJ 必须执行用户代码，同时限制越权、资源滥用和残留进程 | Java/C++/Go 容器；禁网、只读根文件系统、非 root、capability/no-new-privileges、CPU/内存/PID/时间/输出限制、HMAC 请求认证与强制清理 | 支持语言数；恶意/越界测试 `[N]` 个的拦截率；超时终止率；容器/临时目录清理率；单次执行 p95 |
| 3 | 后端：容量与可观测性 | 普通 API 与 CPU 密集判题负载不同，必须分别验证容量并定位瓶颈 | JMeter 分场景压测；Actuator/Micrometer、Prometheus、Grafana；围绕 QPS、p95/p99、错误率、CPU/GC、连接池和队列积压调优 | 同环境前后 QPS、p95/p99、错误率；稳定并发/RPM 上限；CPU/连接池变化；积压恢复时间。没有优化前后对照就写“在 `[并发]` 下达到 `[QPS/p95/错误率]`”，不要写“提升” |
| 4 | AI 应用：RAG 错题反馈 | 通用 LLM 对失败提交可能给出无依据建议、泄露答案或越权读取数据 | 独立 AI 服务；权限过滤后的判题证据；版本化知识库/RAG；只读 Submission Tools；有界工具循环；引用回填与输出校验；异步任务状态 | 自建评测集 `[N]` 条；错误定位正确率；引用有效率/幻觉率；答案或隐藏用例泄露率；p95、平均 Token/成本；使用反馈后下一次 AC 率（若有真实用户数据） |
| 5 | AI 应用：出题与质检工作流 | LLM 生成的题面、标程和用例不能直接发布，且多语言实现可能不一致 | 题目草稿/用例生成/质量审查三条隔离工作流；结构化输出；Tool Calling；Java/C++/Go 沙箱交叉验证；确定性规则门禁；幂等、限流、恢复；`REVIEW_REQUIRED` 人工审批 | 生成任务 `[N]` 个；结构化解析通过率；三语言一致率；无效用例/错误草稿拦截率；人工采纳率；平均出题耗时从 `[A]` 到 `[B]`；失败恢复率 |

与上述取证点对应的仓库材料：AI 服务已经记录了三条题目创作工作流、三种语言验证、Tool Calling、确定性门禁、人工审核和可观测指标候选，见 [Spring AI 题目创作工作流](../ai-authoring-workflows.md)；沙箱安全边界见 [MyOJ Code Sandbox README](../../../myoj-codesandbox/README.md)；压测口径与“优化后用相同参数复测”的要求见 [MyOJ Load Test README](../../load-test/README.md)。这些是候选证据，不代表所有指标已经完成实测。

## 4. 后端与 AI bullet 的指标选择

### 4.1 后端结果优先级

1. **可靠性结果**：消息丢失、重复副作用、状态一致率、失败恢复率、恢复时间。
2. **性能结果**：QPS/RPM、p50/p95/p99、错误率、队列积压、CPU/GC/连接池。
3. **安全结果**：恶意测试覆盖数与拦截率、资源限制是否生效、残留资源数。
4. **工程规模**：服务数、语言数、接口数、测试数；只能证明范围，通常弱于前 3 类。

### 4.2 AI 应用结果优先级

1. **任务质量**：错误定位正确率、用例有效率、三语言一致率、质量问题召回率。
2. **可信与安全**：引用有效率、幻觉/泄露率、越权访问拦截、人工审批覆盖率。
3. **用户/流程价值**：下一次 AC 率、人工采纳率、出题或复盘耗时减少。
4. **运行效率**：端到端 p95、Token/任务、成本/任务、重试率和超时率。

CMU-SV 的 AI Career Guide 范例普遍把“具体模型/算法动作”与任务指标（如准确率、AUC、编辑距离）放在一起。这说明 AI bullet 不能只写“接入大模型/RAG”，而要说明它完成什么任务、如何评测。对于 MyOJ 这种 AI 应用，应换成适配业务的任务指标，而不是生搬模型训练指标。[CMU-SV Artificial Intelligence Career Guide](https://www.sv.cmu.edu/_files/documents/ai-cmusv-career-guide.pdf)

OpenAI 的 SimpleQA 也说明，AI 评测必须限定清楚任务范围，并同时区分正确、错误和未作答；单一“准确率”可能掩盖自信错误。因此 MyOJ 错题反馈至少应同时记录正确率与幻觉/错误率，必要时再记录合理拒答率。[OpenAI: Introducing SimpleQA](https://openai.com/index/introducing-simpleqa/)

## 5. 公开范例：只学结构，不复制事实

### 5.1 CMU-SV 技术简历页（官方写法示例）

CMU-SV 给出的后端示例把“开发 Flask 后端 API”与“数据处理时间降低 30%”放在同一句里。可迁移的只是结构：

```text
动作 + 技术对象/实现方式 + 被改善的任务 + 可量化结果
```

它不是 MyOJ 的事实，`30%` 绝不能复制。[CMU-SV Resume Guide](https://www.sv.cmu.edu/current-students/career-services/jobs-and-internships/create-your-resume.html)

### 5.2 CMU-SV AI Career Guide（官方范例来源，不是规范或 MyOJ 数据）

该指南的课程项目范例采用“实现模型/算法 + 任务 + 任务指标 + 技术栈”的结构，例如用准确率、AUC 或编辑距离描述结果；部分位置直接保留 `XX.X` 占位符，提醒使用者填入自己的测量结果。对 MyOJ 的启发是：

- “Spring AI + RAG + Tool Calling”只是 Action，不是 Result；
- Result 要来自 MyOJ 自建评测集、确定性沙箱验证或真实流程数据；
- 评测口径不同，数字不可横向借用。

来源：[CMU-SV Artificial Intelligence Career Guide](https://www.sv.cmu.edu/_files/documents/ai-cmusv-career-guide.pdf)

### 5.3 CMU 工程学院项目简历（官方范例来源）

CMU 工程学院指南建议选取能展示技能实际应用的项目，并给出 `Action Verb + Context + Result (Metrics, Outcome, and/or Impact)` 公式；示例中有“算法/框架 + 预测或自动化目标 + 业务/技术结果”的组合。适合借鉴句法，但仍需用 MyOJ 自己的实现和验证替换全部事实。[CMU College of Engineering Resume Guide](https://www.cmu.edu/career/documents/sample-resumes-cover-letters/resumeguide-collegeofengineeringgraduatestudents-2019.pdf)

## 6. 成稿前的事实检查

每一条候选 bullet 都应通过以下检查：

- [ ] 开头是明确动作，能说清“我做了什么”，不是团队笼统行为。
- [ ] 只证明一个核心能力，机制不超过 2–3 个。
- [ ] 技术名词与目标岗位相关，且能在代码或文档中定位。
- [ ] 数字有来源、测试条件、时间范围或比较基线；能在面试中解释测量方法。
- [ ] “提升/降低/避免/保证”都有证据；若只有单次测试，改写成“在某条件下达到”。
- [ ] AI 指标来自固定样本和评分规则；没有把主观演示感受写成正确率。
- [ ] 没有把 CMU、Google 或其他公开范例中的数字当成 MyOJ 数据。
- [ ] 一句、通常不超过两行；去掉“负责、参与、熟悉、使用了”等低信息词。
- [ ] 能自然展开成完整 STAR 面试故事，包括失败情形、权衡和验证方法。

## 7. 主要来源与用途

| 来源 | 类型 | 本文使用的结论 |
| --- | --- | --- |
| [Google Resume Overview](https://services.google.com/fh/files/misc/resumetipshandout2016.pdf) | 招聘方官方资料 | 简洁、相关、展示影响；数字要有上下文；说明创造/改善了什么 |
| [Columbia Career Planning Guide](https://www.careereducation.columbia.edu/sites/default/files/cce_cpg_16-17.pdf) | 高校职业中心官方指南 | 用 STAR 梳理经历；交代目的、范围、产出并量化 |
| [Yale: Writing Impactful Resume Bullets](https://ocs.yale.edu/resources/writing-impactful-resume-bullets/) | 高校职业中心官方指南 | `Action + Project + Result`、`X-Y-Z`、量化并提供比较基线 |
| [CMU-SV Resume Guide](https://www.sv.cmu.edu/current-students/career-services/jobs-and-internships/create-your-resume.html) | 高校职业中心官方指南 | `Action Verb + Context + Result`；技术项目强调个人贡献、指标、用户结果并按岗位定制 |
| [CMU College of Engineering Resume Guide](https://www.cmu.edu/career/documents/sample-resumes-cover-letters/resumeguide-collegeofengineeringgraduatestudents-2019.pdf) | 高校职业中心官方指南及范例 | 技术项目展示实际应用；结果导向公式；工程/ML 项目句式 |
| [University of Michigan Resume Resources](https://careercenter.umich.edu/article/resume-resources) | 高校职业中心官方指南 | `Action Verb + What + How/Why/Impact`；量化；AI 生成文案必须自行核实和改写 |
| [Berkeley Career Engagement](https://www.career.berkeley.edu/prepare-for-success/resumes/) | 高校职业中心官方指南 | 根据职位描述定制；突出招聘方需要的技能与结果 |
| [UConn: Writing Bullet Points](https://career.uconn.edu/writing-bullet-points/) | 高校职业中心官方指南 | 一句、通常 1–2 行；强动作动词；What/How/Why；适当量化 |
| [CMU-SV Artificial Intelligence Career Guide](https://www.sv.cmu.edu/_files/documents/ai-cmusv-career-guide.pdf) | **官方范例来源** | AI 项目用算法/实现、任务和任务指标共同构成证据；范例数据不可移植 |
| [OpenAI: Introducing SimpleQA](https://openai.com/index/introducing-simpleqa/) | AI 评测一手资料 | 评测范围与评分规则必须明确；区分正确、错误、未作答而非只看单一准确率 |

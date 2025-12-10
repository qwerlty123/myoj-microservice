# MyOJ 核心判题链路压测

这套脚本压的是完整异步链路：

```text
JMeter -> Gateway -> Question Service -> MySQL/Outbox -> RabbitMQ
       -> Judge Service -> 服务器 Code Sandbox -> Question Service -> MySQL 终态
```

当前拓扑按“后端微服务在本机、代码沙箱和中间件在服务器”处理。JMeter 报告衡量提交接口的接收能力；脚本给每轮代码打 `RUN_ID` 标记，再从 MySQL 计算“提交入库到判题终态”的端到端 p50/p95/p99。远程沙箱的网络往返因此包含在端到端耗时内。

只在独立压测库和可销毁的沙箱环境运行。压测会创建大量 `question_submit` 和 `judge_task_outbox` 数据。

## 1. 前置条件

- 本机已启动 Gateway(8101)、User(8102)、Question(8103)、Judge(8104) 等所需微服务。
- 服务器已启动 MySQL、Redis、Nacos、RabbitMQ 和 Code Sandbox(8090)。
- `local-dev.env` 中的 `CODESANDBOX_URL` 指向服务器，例如 `http://SERVER_IP:8090/executeCode`，不是 `127.0.0.1`。
- 本机已有 JMeter 5.6+、`curl`、`jq`、Python 3 和 MySQL 客户端。
- 题目 `QUESTION_ID` 存在，并且至少有一个判题用例。

检查关键入口：

```bash
cd /path/to/myoj-microservice
set -a
source ./local-dev.env
set +a

curl -fsS "${CODESANDBOX_URL%/executeCode}/actuator/health" | jq
```

`run-jmeter.sh` 还会自动检查 Gateway 登录、题目和远程沙箱健康；任一失败都会在产生负载前停止。

## 2. 创建压测账号

账号数不能少于该轮线程数。判题 JMX 会按线程号固定绑定账号，避免慢升压时账号漂移或复用触发 Sentinel 用户级限流。

```bash
cd /path/to/myoj-microservice/load-test
BASE_URL=http://127.0.0.1:8101 ACCOUNT_COUNT=100 ./prepare-accounts.sh
```

已有账号时，也可以按 `accounts.csv.example` 的两列表头准备 `accounts.csv`。当前解析器不支持字段内逗号。

## 3. 启动监控

`monitoring/prometheus.yml` 中 RabbitMQ 和 Code Sandbox 的 target 都应指向服务器。服务器地址变化后先替换，再启动：

```bash
cd /path/to/myoj-microservice/load-test/monitoring
docker compose up -d
```

- Prometheus targets: <http://localhost:9091/targets>
- Grafana: <http://localhost:3000>
- Grafana 看板：`MyOJ / MyOJ Load Test`

确认所有 target 为 `UP`。Grafana 的 `myoj-codesandbox` 指标来自服务器沙箱 JVM；执行容器是短生命周期子容器，主机和 Docker 总 CPU/内存还应在服务器侧同时观察。

## 4. 可直接执行：冒烟与阶梯压测

先加载连接配置并进入压测目录：

```bash
cd /path/to/myoj-microservice
set -a
source ./local-dev.env
set +a
cd load-test
```

冒烟：10 RPM，2 分钟。脚本结束后最多等待 5 分钟让本轮判题排空，并自动生成 API 与数据库摘要。

```bash
HOST=127.0.0.1 PORT=8101 THREADS=5 RAMP_UP=10 DURATION=120 \
TARGET_RPM=10 QUESTION_ID=1 DRAIN_TIMEOUT=300 \
./run-jmeter.sh judge
```

冒烟无错误后逐级升压；每条命令会等待本轮排空后才结束：

```bash
HOST=127.0.0.1 THREADS=10 RAMP_UP=30 DURATION=300 TARGET_RPM=30  QUESTION_ID=1 ./run-jmeter.sh judge
HOST=127.0.0.1 THREADS=20 RAMP_UP=30 DURATION=300 TARGET_RPM=60  QUESTION_ID=1 ./run-jmeter.sh judge
HOST=127.0.0.1 THREADS=30 RAMP_UP=60 DURATION=600 TARGET_RPM=120 QUESTION_ID=1 ./run-jmeter.sh judge
HOST=127.0.0.1 THREADS=50 RAMP_UP=60 DURATION=600 TARGET_RPM=240 QUESTION_ID=1 ./run-jmeter.sh judge
```

不要一上来跑最高档。当前推荐配置有两个明确的容量边界：

- Question Service 每 500ms 最多从 Outbox 取 50 条，理论投递能力明显高于当前 2 核沙箱的执行能力；它不应再是首要瓶颈。
- Judge Service 默认启动 2 个 RabbitMQ 消费者，远程 Code Sandbox 同时执行上限也为 2，与服务器的 2 个 vCPU 对齐。继续提高这两个值通常只会增加 CPU 争抢和尾延迟，除非先扩容沙箱。

压测目标是找到实际拐点，不是把 `TARGET_RPM` 跑到理论上限。

## 5. 输出在哪里

每轮生成独立目录：

```text
load-test/results/<time>-judge/
├── run.properties          # 本轮参数和时间
├── results.jtl             # JMeter 原始样本
├── jmeter.log
├── api-summary.txt         # 登录/提交 API 的错误率与 p50/p95/p99
├── judge-db-summary.txt    # 本轮判题终态、端到端延迟、结果与 Outbox 分布
└── report/index.html       # JMeter HTML 报告
```

只重做数据库统计时：

```bash
./analyze-judge-db.sh 20260815-120000-judge \
  ./results/20260815-120000-judge
```

实时看全局积压时，可以在 MySQL 执行 `sql/observe-judge.sql`。

## 6. 怎么看结果

先看 `api-summary.txt` 的 `10 Submit Java Code`：

- `error%`：提交接口业务断言或 HTTP 错误率，建议 `< 1%`。
- `p95/p99`：这里只是“请求被接收并写入 Outbox”的延迟，建议起步用 p95 `< 500ms`、p99 `< 1s` 作为门槛。
- `req/s`：实际打到的提交速率。目标 RPM 除以 60 后应大致匹配；明显偏低说明线程数不足、客户端受阻或错误过多。

再看 `judge-db-summary.txt`：

- `terminal_percent` 应为 100%，`waiting/running/system_failed` 应为 0。
- `end_to_end_latency` 才是用户真正感受到的完整判题耗时；横向比较各档的 p95/p99。
- `outbox_distribution` 不应有 `DEAD`，`max_retry_count` 正常应为 0。
- `judge_result_distribution` 中 Accepted、Wrong Answer、Compile Error 都表示判题链路已完成；不要把 Wrong Answer 当成系统错误。`SYSTEM_FAILED` 才是基础设施或判题配置失败。

最后看 Grafana 的时间序列：

- Outbox pending、RabbitMQ ready 若持续上升，说明输入速率已超过处理速率。
- 停止施压后积压必须回到基线；回不去或超过 `DRAIN_TIMEOUT`，本档判为不可持续。
- `myoj-codesandbox` 的 HTTP p95、CPU、堆和线程与 Judge Service 一起抬升时，重点判断是远程网络、沙箱执行还是单消费者在限速。
- 数据库连接 `pending > 0`、CPU 持续 `>= 80%`、Full GC、沙箱资源繁忙/超时都应停止继续升压。

推荐把“最大可持续容量”定义为最后一档同时满足：提交错误率 `< 1%`、提交 p95 `< 500ms`、判题最终完成率 `100%`、无系统失败/死信、端到端 p95 可接受、停压后能在约定时间内排空。优化前后必须用完全相同的题目、代码、RPM、时长和环境复测。

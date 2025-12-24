# MyOJ 压测工程

这套工程使用 JMeter 生成负载，使用 Actuator/Micrometer、Prometheus 和 Grafana 观察微服务与判题链路。普通业务和判题分开压测，避免沙箱的 CPU/进程消耗掩盖 API 容量。

当前部署拓扑中后端和沙箱运行在本机，因此压测结果代表“本机后端 + 公网远程中间件”的混合环境性能，不代表服务器承载后端时的性能上限。

## 1. 前置条件

- 服务器 Docker 已启动 Nacos、MySQL、Redis、RabbitMQ 和 MinIO。
- 本机已启动 Gateway(8101)、User(8102)、Question(8103)、Judge(8104)、Comment(8105) 和 Sandbox(8090)。
- 本机已安装 JMeter 5.6+。
- 使用独立压测库和可销毁的沙箱环境，不要对生产环境运行。

使用本项目的服务器部署脚本时，生成的 `local-dev.env` 已默认设置：

```bash
export LOAD_TEST_CRAWLER_DETECTION_ENABLED=false
```

这只禁用题目详情的压测账号封禁/邮件逻辑，默认值仍为 `true`。

## 2. 服务器监控

Prometheus 和 Grafana 可以在本机 Docker 启动：

```bash
cd load-test/monitoring
# 先把 prometheus.yml 中的 SERVER_PUBLIC_IP 换成中间件服务器公网 IP
docker compose up -d
```

- Prometheus: <http://localhost:9090/targets>
- Grafana: <http://localhost:3000>
- Grafana 看板: `MyOJ / MyOJ Load Test`

## 3. 创建压测账号

```bash
cd load-test
BASE_URL=http://127.0.0.1:8101 ACCOUNT_COUNT=200 ./prepare-accounts.sh
```

脚本会创建 `accounts.csv`。如果已经有账号，可复制 `accounts.csv.example` 并按相同表头填写。账号数量不应少于任何一轮测试的线程数，否则判题测试可能因复用账号而触发 Sentinel 用户级限流。

## 4. 冒烟测试

先使用 1 个线程运行 60 秒：

```bash
HOST=127.0.0.1 THREADS=1 RAMP_UP=1 DURATION=60 QUESTION_ID=1 ./run-jmeter.sh business
```

确认 JMeter 报告中没有登录、JWT、业务断言错误，Grafana 也能看到指标后再升压。

## 5. 普通业务阶梯压测

```bash
HOST=127.0.0.1 THREADS=10  RAMP_UP=30  DURATION=300 QUESTION_ID=1 ./run-jmeter.sh business
HOST=127.0.0.1 THREADS=50  RAMP_UP=60  DURATION=300 QUESTION_ID=1 ./run-jmeter.sh business
HOST=127.0.0.1 THREADS=100 RAMP_UP=60  DURATION=600 QUESTION_ID=1 ./run-jmeter.sh business
HOST=127.0.0.1 THREADS=200 RAMP_UP=120 DURATION=600 QUESTION_ID=1 ./run-jmeter.sh business
```

`business-flow.jmx` 中每个用户只登录一次，然后循环访问题目列表、详情、排行榜、热题、评论和用户统计。

建议停止升压阈值：

- 错误率 >= 1%
- 核心接口 p95 >= 500ms 或 p99 >= 1s
- 服务 CPU 持续 >= 80%
- 出现 Full GC、连接池耗尽或持续积压

## 6. 判题速率压测

`TARGET_RPM` 是每分钟提交数，先从 60 RPM（1 次/秒）开始：

```bash
HOST=127.0.0.1 THREADS=20 TARGET_RPM=60  DURATION=300 QUESTION_ID=1 ./run-jmeter.sh judge
HOST=127.0.0.1 THREADS=30 TARGET_RPM=120 DURATION=300 QUESTION_ID=1 ./run-jmeter.sh judge
HOST=127.0.0.1 THREADS=50 TARGET_RPM=300 DURATION=600 QUESTION_ID=1 ./run-jmeter.sh judge
```

不要直接跳到高速率。当前单个 Question Service 默认每 3 秒最多从 Outbox 取 20 条，超过约 6.7 条/秒后预期会积压。

压测时可在 MySQL 执行 `sql/observe-judge.sql`，并同时观察 Grafana 的 Outbox、RabbitMQ ready/unacked、沙箱 CPU、线程和进程数。

## 7. 结果

每次运行会生成：

```text
load-test/results/<time>-<mode>/
├── results.jtl
├── jmeter.log
└── report/index.html
```

记录每轮的线程数/速率、QPS、p95、p99、错误率、CPU、GC、MySQL 连接、RabbitMQ/Outbox 积压和积压恢复时间。优化后用完全相同的参数复测，再将真实前后数据写入简历。

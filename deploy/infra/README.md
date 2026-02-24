# MyOJ 远程中间件部署

正确拓扑：

```text
本机进程：Gateway、User、Question、Judge、Comment、AI Service
服务器进程：Code Sandbox（systemd，内部使用受限 Docker 容器执行代码）
服务器 Docker：MySQL、Redis、Nacos、RabbitMQ、MinIO
```

中间件部署本身不需要上传 MyOJ 项目。Code Sandbox 由 `myoj-codesandbox/scripts/deploy-server.sh` 单独部署，不属于本目录的 Docker Compose。

本目录只部署服务器中间件，不部署任何后端 JAR、源码或沙箱。后端微服务在本机运行，通过 `CODESANDBOX_URL` 调用服务器沙箱，并通过其他连接变量访问服务器中间件。

## 版本说明

项目本身主要锁定的是 Java 客户端依赖，并没有在 Maven 或 YAML 中锁定所有中间件服务端版本。因此 Docker 版本是兼容性选择，不是“项目原来已经使用的完全相同镜像版本”：

| 中间件 | Docker 版本 | 项目侧情况 |
|---|---|---|
| MySQL | 8.4 | 使用 MySQL JDBC 驱动，未锁定服务端版本 |
| Redis | 7.4 | 使用 Spring Data Redis，未锁定服务端版本 |
| Nacos | 2.5.1 | Spring Cloud Alibaba 2021.0.5.0 客户端，未锁定服务端版本 |
| RabbitMQ | 4.1 | 使用 Spring AMQP，未锁定服务端版本 |
| MinIO | 固定日期版本 | Java SDK 8.5.9，未锁定 MinIO 服务端版本 |

这些版本按当前项目依赖和配置选择，可以正常对接；如果你希望完全复刻以前虚拟机环境，需要提供以前的镜像标签或安装版本，再将 `.env` 中的 `*_IMAGE` 改成对应版本。

## 1. 服务器部署文件

只将 `deploy/infra` 目录中的下列文件复制到服务器，例如 `/opt/myoj-infra/`：

```text
docker-compose.yml
.env.example
deploy.sh
generate-nacos-internal-env.sh
status.sh
backup.sh
```

使用本目录部署中间件时，项目源码、微服务 JAR 和 Code Sandbox 源码都不需要上传；沙箱 JAR 由它自己的部署脚本单独发布。

## 2. 填写配置

在服务器执行：

```bash
cp /opt/myoj-infra/.env.example /opt/myoj-infra/.env
chmod 600 /opt/myoj-infra/.env
vim /opt/myoj-infra/.env
```

至少填写：

```dotenv
BIND_IP=0.0.0.0
SERVER_PUBLIC_IP=你的服务器公网IP

MYSQL_USER=admin
MYSQL_PASSWORD=请替换为独立强密码
MYSQL_ROOT_PASSWORD=请替换为另一组独立强密码
REDIS_PASSWORD=请替换为独立强密码
NACOS_ADMIN_PASSWORD=请替换为独立强密码
RABBITMQ_DEFAULT_PASS=请替换为独立强密码
MINIO_ROOT_PASSWORD=请替换为独立强密码
CODESANDBOX_SECRET_KEY=请替换为足够长的随机HMAC密钥
CODESANDBOX_HOST=你的服务器公网IP
GATEWAY_TRUST_TOKEN=请替换为另一组足够长的随机字符串
AI_CHAT_API_KEY=你的模型服务密钥
AI_CHAT_BASE_URL=https://api.deepseek.com
AI_CHAT_MODEL=deepseek-v4-flash
AI_CHAT_ENABLED=true
AI_CHAT_RETENTION_DAYS=30
AI_CHAT_AGENT_MAX_STEPS=4
AI_CHAT_STREAM_TIMEOUT=30m
AI_AUTHORING_ENABLED=true
AI_AUTHORING_MAX_REPAIR_COUNT=3
AI_AUTHORING_STALE_AFTER=3m
AI_AUTHORING_GRAPH_VERSION=authoring-v1
AI_AUTHORING_PROMPT_VERSION=authoring-v1
AI_AUTHORING_REDIS_DATABASE=1
```

AI Service 提供持久化多轮算法辅导，以及异步、可恢复的 AI 出题任务。出题草稿通过规则与代码沙箱后只会进入
`REVIEW_REQUIRED`，仍需管理员人工确认。若需要辅导端的 `webSearch` 工具，再额外填写
`BAIDU_AI_SEARCH_API_KEY`；不填写不会影响其他工具和出题工作流。

当前配置使用 `admin` 作为 MySQL、RabbitMQ 和 MinIO 的业务账号；`MYSQL_USER` 不能填写 `root`。`root` 账号只由 `MYSQL_ROOT_PASSWORD` 配置。

确认以上配置后执行：

```bash
chmod +x /opt/myoj-infra/*.sh
/opt/myoj-infra/deploy.sh
```

完整部署脚本会等待各中间件健康检查，并生成：

- `.env.internal`：Nacos 内部 JWT 签名密钥，不是登录密码，无需手动填写；
- `local-dev.env`：本机微服务使用的服务器公网连接配置。

## 3. Nacos 为什么出现内部 Token

Nacos 控制台登录仍然只使用：

```text
用户名：nacos
密码：NACOS_ADMIN_PASSWORD
```

`NACOS_AUTH_TOKEN` 是新版 Nacos 在开启鉴权后用于签发 JWT 的内部密钥，不是第三个登录凭据。`generate-nacos-internal-env.sh` 会自动生成并持久化，平时不需要查看或输入。

## 4. 初始化数据库

MySQL 容器启动后，在本机执行项目 SQL，无需把 SQL 文件传到服务器：

```bash
cd /你的本机项目路径/myoj-microservice
mysql -h 服务器公网IP -P 3306 -u root -p < sql/init_myoj.sql
```

只在数据库第一次创建时执行一次。

## 5. 本机运行项目

把服务器生成的配置复制回本机：

```bash
scp 用户名@服务器公网IP:/opt/myoj-infra/local-dev.env ./local-dev.env
```

每个本机微服务启动前加载：

```bash
set -a
source ./local-dev.env
set +a
```

这一步只把服务器连接参数注入本机进程，不会把后端代码上传到服务器。Judge Service 和 AI Service 都会通过 `CODESANDBOX_URL` 调用远程沙箱；AI Service 还会读取模型配置。

生成的配置指向：

```text
MySQL      服务器公网IP:3306
Redis      服务器公网IP:6379
Nacos      服务器公网IP:8848
RabbitMQ   服务器公网IP:5672
MinIO      服务器公网IP:9002
CodeSandbox 服务器公网IP:8090
```

本机微服务统一注册到 `LOCAL_DEV_GROUP`。

## 6. 公网端口

云安全组放行 TCP：

```text
3306, 5672, 6379, 8090, 8848, 9002-9003, 9848-9849, 15672, 15692
```

8090 只需放行给运行 Judge Service 的出口 IP；不要向整个公网开放代码执行入口。`/executeCode` 仍使用时间戳和 HMAC 签名认证。

访问地址：

- Nacos：`http://服务器公网IP:8848/nacos`
- RabbitMQ：`http://服务器公网IP:15672`
- MinIO：`http://服务器公网IP:9003`

## 7. 日常操作

```bash
/opt/myoj-infra/status.sh
docker compose --project-directory /opt/myoj-infra \
  --file /opt/myoj-infra/docker-compose.yml \
  --env-file /opt/myoj-infra/.env \
  --env-file /opt/myoj-infra/.env.internal logs -f --tail=200
docker compose --project-directory /opt/myoj-infra \
  --file /opt/myoj-infra/docker-compose.yml \
  --env-file /opt/myoj-infra/.env \
  --env-file /opt/myoj-infra/.env.internal restart
/opt/myoj-infra/backup.sh
```

`.env`、`.env.internal` 和 `local-dev.env` 都不要提交到 Git。

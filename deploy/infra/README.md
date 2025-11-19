# MyOJ 远程中间件部署

正确拓扑：

```text
本机进程：Gateway、User、Question、Judge、Comment、AI Service、Code Sandbox
服务器 Docker：MySQL、Redis、Nacos、RabbitMQ、Qdrant、MinIO
```

服务器不需要上传 MyOJ 项目，也不需要安装 JDK 或 Maven。只需要 Docker Engine、Docker Compose v2、curl 和 openssl。

本目录只部署服务器中间件，不部署任何后端 JAR、源码或沙箱。AI Service 也在本机运行，通过 `QDRANT_HOST`、`MYSQL_URL`、`REDIS_HOST`、`RABBITMQ_HOST` 和 `NACOS_SERVER_ADDR` 连接服务器。

## 版本说明

项目本身主要锁定的是 Java 客户端依赖，并没有在 Maven 或 YAML 中锁定所有中间件服务端版本。因此 Docker 版本是兼容性选择，不是“项目原来已经使用的完全相同镜像版本”：

| 中间件 | Docker 版本 | 项目侧情况 |
|---|---|---|
| MySQL | 8.4 | 使用 MySQL JDBC 驱动，未锁定服务端版本 |
| Redis | 7.4 | 使用 Spring Data Redis，未锁定服务端版本 |
| Nacos | 2.5.1 | Spring Cloud Alibaba 2021.0.5.0 客户端，未锁定服务端版本 |
| RabbitMQ | 4.1 | 使用 Spring AMQP，未锁定服务端版本 |
| Qdrant | 1.15 | AI Service 通过 Spring AI Qdrant VectorStore 使用 |
| MinIO | 固定日期版本 | Java SDK 8.5.9，未锁定 MinIO 服务端版本 |

这些版本按当前项目依赖和配置选择，可以正常对接；如果你希望完全复刻以前虚拟机环境，需要提供以前的镜像标签或安装版本，再将 `.env` 中的 `*_IMAGE` 改成对应版本。

## 1. 服务器部署文件

只将 `deploy/infra` 目录中的下列文件复制到服务器，例如 `/opt/myoj-infra/`：

```text
docker-compose.yml
.env.example
deploy.sh
deploy-qdrant.sh
generate-nacos-internal-env.sh
status.sh
backup.sh
```

项目源码、微服务 JAR、Code Sandbox 源码都不需要上传。

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
SERVER_PUBLIC_IP=124.221.250.220

MYSQL_USER=admin
MYSQL_PASSWORD=adgjl08642
MYSQL_ROOT_PASSWORD=adgjl08642
REDIS_PASSWORD=adgjl08642
NACOS_ADMIN_PASSWORD=adgjl08642
RABBITMQ_DEFAULT_PASS=adgjl08642
MINIO_ROOT_PASSWORD=adgjl08642
CODESANDBOX_SECRET_KEY=adgjl08642
AI_INTERNAL_TOKEN=请替换为足够长的随机字符串
QDRANT_API_KEY=请替换为另一组足够长的随机字符串
```

当前配置使用 `admin` 作为 MySQL、RabbitMQ 和 MinIO 的业务账号；`MYSQL_USER` 不能填写 `root`。`root` 账号只由 `MYSQL_ROOT_PASSWORD` 配置。

确认以上配置后执行：

```bash
chmod +x /opt/myoj-infra/*.sh
/opt/myoj-infra/deploy.sh
```

如果 MySQL、Redis 等基础设施已经部署，只需要单独部署 RAG 向量库，可以执行：

```bash
/opt/myoj-infra/deploy-qdrant.sh
```

这个单独脚本只读取 `.env` 中的 Qdrant 配置，不依赖 Nacos 的 `.env.internal`，也不会启动或重启其他中间件。

完整部署脚本和单独的 Qdrant 脚本都会等待健康检查，并验证 `QDRANT_API_KEY` 可以访问集合 API。脚本还会生成：

- `.env.internal`：Nacos 内部 JWT 签名密钥，不是登录密码，无需手动填写；
- `local-dev.env`：本机微服务和沙箱使用的公网连接配置。

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

每个微服务和 Code Sandbox 启动前加载：

```bash
set -a
source ./local-dev.env
set +a
```

这一步只把服务器连接参数注入本机进程，不会把后端代码上传到服务器。AI Service 启动时会使用其中的 `QDRANT_HOST`、`QDRANT_GRPC_PORT` 和 `QDRANT_API_KEY` 连接远程 RAG 数据库。

生成的配置指向：

```text
MySQL      服务器公网IP:3306
Redis      服务器公网IP:6379
Nacos      服务器公网IP:8848
RabbitMQ   服务器公网IP:5672
Qdrant     服务器公网IP:6333/6334
MinIO      服务器公网IP:9002
CodeSandbox 127.0.0.1:8090
```

本机微服务统一注册到 `LOCAL_DEV_GROUP`。

## 6. 公网端口

云安全组放行 TCP：

```text
3306, 5672, 6333-6334, 6379, 8848, 9002-9003, 9848-9849, 15672, 15692
```

访问地址：

- Nacos：`http://服务器公网IP:8848/nacos`
- RabbitMQ：`http://服务器公网IP:15672`
- Qdrant：`http://服务器公网IP:6333/dashboard`
- MinIO：`http://服务器公网IP:9003`

Qdrant 的 HTTP/gRPC 端口使用 `QDRANT_API_KEY` 做 API Key 鉴权，AI Service 需要配置同一个 `QDRANT_API_KEY`。生产环境建议只开放给应用服务器安全组，或在 TLS 反向代理后提供公网访问。

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

# MyOJ AI Service

后端同时提供题目生成 Agent 和按“用户 + 题目”隔离的持久化多轮辅导。题目辅导的接口行为参考
`baimao-stu/oj-backend`，实现代码按 MyOJ 微服务、可信网关、题目内部接口和现有代码沙箱协议重新适配。

## 题目辅导接口

所有接口经 Gateway 访问，并使用网关注入的登录用户身份：

```http
POST /api/ai/chat/session/get
POST /api/ai/chat/session/clear
POST /api/ai/chat/message/send
POST /api/ai/chat/message/stream
Authorization: Bearer <jwt>
Content-Type: application/json
```

会话请求：

```json
{"questionId": 1}
```

消息请求：

```json
{
  "questionId": 1,
  "mode": "agent",
  "message": "为什么这个样例不通过？",
  "language": "java",
  "userCode": "public class Main { ... }",
  "latestJudgeResult": "Wrong Answer",
  "testInputs": ["5\n1 2 3 4 5"]
}
```

`mode` 支持 `normal` 和 `agent`。流式接口的 SSE 事件与参考行为一致：`tool`、`delta`、`meta`、`done`、`error`。
Agent 工具包括 `searchWeb`、`submission_analysis`、`testcase_generator`、`sample_error_analyzer`；
适配 MyOJ 后额外支持 `run_user_code`，仅在请求带代码和测试输入时通过代码沙箱执行。

## 题目生成接口

```http
GET /api/ai/create/question?userPrompt=滑动窗口&difficulty=中等
Accept: text/event-stream
Authorization: Bearer <jwt>
```

SSE 事件为 `tool`、`result`、`error`、`done`。

## 数据库与配置

首次升级执行 [`../sql/migration_20260819_ai_chat.sql`](../sql/migration_20260819_ai_chat.sql)。会话、消息、提示词、模型、
禁用规则、敏感词、违规日志和工具限额均持久化到 MySQL；过期会话默认每 30 分钟归档。

必需环境变量：

- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- `AI_CHAT_API_KEY`，以及可选的 `AI_CHAT_BASE_URL`、`AI_CHAT_MODEL`
- `CODESANDBOX_URL`、`CODESANDBOX_SECRET_KEY`
- `GATEWAY_TRUST_TOKEN`

可选变量：`BAIDU_AI_SEARCH_API_KEY`、`AI_CHAT_ENABLED`、`AI_CHAT_RETENTION_DAYS`、
`AI_CHAT_AGENT_MAX_STEPS`、`AI_CHAT_STREAM_TIMEOUT`。

## 沙箱对接

AI Service 不改变沙箱内部实现，只复用当前协议：`POST /executeCode`，请求字段为
`inputList/code/language/executionProfile`，签名是 `HMAC-SHA256(secret, timestamp + "\\n" + exactJsonBody)`，
请求头为 `X-Timestamp` 和 `X-Signature`。题目生成使用 `AI_VALIDATION`，辅导执行使用 `AI_TUTOR`；两者共享同一客户端。

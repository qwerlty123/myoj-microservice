# Gateway Sentinel 动态规则

网关默认从 `application.yml` 加载本地基线规则，无需依赖 Sentinel Dashboard 即可生效。
生产环境需要动态调整时，将本目录两个 JSON 文件分别发布到 Nacos 配置中心：

| Data ID | Group | 文件 |
|---|---|---|
| `myoj-backend-gateway-gw-flow-rules` | `DEFAULT_GROUP` | `myoj-backend-gateway-gw-flow-rules.json` |
| `myoj-backend-gateway-gw-api-rules` | `DEFAULT_GROUP` | `myoj-backend-gateway-gw-api-rules.json` |

发布完成后使用以下 profile 启动 Gateway：

```bash
SPRING_PROFILES_ACTIVE=prod,sentinel-nacos java -jar myoj-backend-gateway.jar
```

如果使用其他 Data ID、Group 或 namespace，可以设置：

```dotenv
SENTINEL_GATEWAY_FLOW_DATA_ID=myoj-backend-gateway-gw-flow-rules
SENTINEL_GATEWAY_API_DATA_ID=myoj-backend-gateway-gw-api-rules
SENTINEL_RULE_GROUP=DEFAULT_GROUP
NACOS_CONFIG_NAMESPACE=
```

启用 `sentinel-nacos` 后，本地规则会自动关闭，避免本地规则与动态数据源同时修改
Sentinel 的全局规则管理器。修改 JSON 阈值时应先压测，并确认多 Gateway 实例下的总容量；
普通 Sentinel 规则的计数器按实例计算。

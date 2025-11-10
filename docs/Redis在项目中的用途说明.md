# MyOJ 项目中 Redis 用途说明

项目里 Redis 分布在 **题目服务（question-service）**、**用户服务（user-service）**、**网关（gateway）** 三个模块，主要做：**缓存**、**限流/计数**、**JWT 黑名单**。下面按模块和用途分开说明。

---

## 一、整体概览

| 模块 | 使用方式 | 主要用途 |
|------|----------|----------|
| **question-service** | RedisTemplate + Redisson | 题目/提交/排行榜/热题缓存、限流与计数、缓存清理 |
| **user-service** | StringRedisTemplate | 用户登出后 Token 黑名单 |
| **gateway** | StringRedisTemplate | 校验请求是否携带已失效（黑名单）Token |

---

## 二、题目服务（question-service）中的 Redis

题目服务同时用了 **Spring 的 RedisTemplate** 和 **Redisson**，一个管缓存，一个管限流和计数。

### 1. 缓存（RedisTemplate）

所有缓存 Key 在 `RedisConstant` 中定义：

| Key 常量 | 含义 | 过期时间 | 使用接口 |
|----------|------|----------|----------|
| `user:submits:{userId}` | 当前用户的题目提交分页列表 | 有结果 1 小时，空结果 5 分钟 | `POST /question_submit/list/page/my` |
| `user:stats:{userId}` | 当前用户的统计（通过数、提交数等） | 1 小时 | `GET /status` |
| `leaderboard` | 全局排行榜列表 | 1 小时 | `GET /leaderboard` |
| `hot:questions` | 热门题目分页列表 | 1 小时 | `POST /hot/list` |

**逻辑简述：**

- 读接口：先查 Redis，命中直接返回；未命中再查库/计算，并写入 Redis。
- 写接口：用户提交代码（`POST /question_submit/do`）成功后，会触发**缓存失效**（见下文「缓存清理」）。

这样排行榜、个人统计、我的提交、热题列表不会每次都打数据库，减轻 DB 压力、加快响应。

### 2. 缓存清理（CacheClearTask + 延迟双删）

数据变更后需要让缓存失效。项目里通过 **CacheClearTask** 做「延迟双删」：

- **第一次删除**：立刻删 Redis 里对应 key。
- **等待一段时间**（如 500ms 或 2s）：给主从/集群同步留时间。
- **第二次删除**：再删一次，防止主从延迟导致旧数据被重新缓存。

在 **QuestionController** 里：

- 用户**提交代码**成功后：
  - `clearUserCache(userId)`：删 `user:stats:{userId}`、`user:submits:{userId}`，延迟 2000ms 双删。
  - `clearLeaderboardCache()`：删 `leaderboard`，延迟 500ms 双删。
  - `clearHotQuestionsCache()`：删 `hot:questions`，延迟 500ms 双删。

这样一旦有新提交，相关用户数据、排行榜和热题缓存都会在短时间内失效并更新。

### 3. 限流（Redisson — RedisLimiterManager）

**RedisLimiterManager** 用 Redisson 的 **RRateLimiter** 做限流：

- `doRateLimit(key)`：每 5 秒最多 2 次请求（按 key）。
- `doRateLimit_genQuestion(key)`：每 1 分钟最多 1 次（按 key）。

当前代码里这两个方法**已实现但未在 Controller 中调用**；题目提交的限流是通过 **Sentinel**（`SphU.entry("questionSubmit", ...)`）做的。若你后续要对「查题」「生成题目」等接口做限流，可以直接调用 `redisLimiterManager.doRateLimit(...)` 或 `doRateLimit_genQuestion(...)`。

### 4. 计数与爬虫检测（Redisson — CountManager）

**CountManager** 用 Redisson 执行 **Lua 脚本**，实现「按时间窗口的计数」：

- Key 形式：`{key}:{时间因子}`，例如按分钟：`user:access:123:时间戳/60`。
- 逻辑：存在则 `INCR`，不存在则 `SET 1` 并设置过期时间，保证计数和过期是原子的。

在 **QuestionController** 里用于**爬虫/滥用检测**（`crawlerDetect`）：

- Key：`user:access:{userId}`，按「1 分钟」一个窗口，过期 180 秒。
- 逻辑：
  - 每分钟访问次数 &gt; 20：视为封号，踢下线并把用户角色改为 `ban`。
  - 每分钟访问次数 = 10：发邮件告警并返回“访问太频繁”提示。

调用位置：**获取题目 VO**（`GET /get/vo`）时，先 `crawlerDetect(loginUser.getId(), request)`，再查题。  
也就是说，Redis 在这里的作用是：**按用户、按分钟累计访问次数**，用于反爬和封号。

---

## 三、用户服务（user-service）中的 Redis

### JWT 登出黑名单（StringRedisTemplate）

用户**登出**（或按 Token 登出）时，需要让该 Token 在过期前都不能再通过校验。做法是把 Token 放进 Redis 黑名单：

- **Key**：`jwt:blacklist:{token}`（去掉 `Bearer ` 后的 token 串）。
- **Value**：固定字符串如 `"logged_out"`。
- **TTL**：Token 剩余有效时间（毫秒），这样黑名单条目会在 Token 自然过期时自动删除，不必手动清理。

写入发生在 **UserServiceImpl** 的：

- `userLogout(request)` 里从 request 取 Token 并加入黑名单；
- `userLogoutBytoken(token)` 里对传入的 token 加入黑名单。

也就是说，**Redis 在 user 服务里只做一件事：记录「已登出 / 已失效的 Token」**，供网关（或其它服务）校验。

---

## 四、网关（gateway）中的 Redis

### Token 黑名单校验（StringRedisTemplate）

**GlobalAuthFilter** 在放行请求前会校验 JWT：

1. 从请求头取 `Authorization`，得到 token。
2. 查 Redis：`stringRedisTemplate.hasKey("jwt:blacklist:" + token)`。
3. 若存在，说明该 Token 已登出/失效，直接返回「Token 已失效」，不放行下游。

这样即使用户还没到 JWT 过期时间，只要登出过，该 Token 就会在网关层被拒绝，**Redis 在这里的作用是：集中存放「已失效 Token」名单，供网关做统一校验**。

---

## 五、配置与依赖小结

- **题目服务**：  
  - 使用 `spring.redis` 配置（与 Redisson 共用）。  
  - 需要 **RedisTemplate**（Spring Data Redis）和 **RedissonClient**（Redisson），因此需要：
    - `spring-boot-starter-data-redis`
    - 不排除 `RedisAutoConfiguration`
    - 以及当前的 Redisson 配置。

- **用户服务 / 网关**：  
  - 若已配置 `spring.redis`，且引入了 `spring-boot-starter-data-redis`，则会有 **StringRedisTemplate**，可直接注入使用。  
  - 需保证与题目服务、网关使用的是**同一套 Redis**（同一实例或同一集群），这样黑名单在 user 写入、在 gateway 读取才能一致。

---

## 六、Redis Key 汇总

| Key 模式 | 模块 | 用途 |
|----------|------|------|
| `user:submits:{userId}` | question-service | 用户题目提交列表缓存 |
| `user:stats:{userId}` | question-service | 用户统计缓存 |
| `leaderboard` | question-service | 排行榜缓存 |
| `hot:questions` | question-service | 热门题目缓存 |
| `user:access:{userId}:{时间因子}` | question-service | 爬虫检测计数（Redisson Lua） |
| 限流 key（由调用方传入） | question-service | Redisson 限流器（当前未在接口中使用） |
| `jwt:blacklist:{token}` | user-service / gateway | JWT 登出黑名单 |

整体上，**Redis 在你项目里主要干三件事：缓存题目与用户相关结果、限流/计数（含爬虫检测）、以及登出后的 JWT 黑名单**；题目服务还通过延迟双删保证缓存更新一致性。

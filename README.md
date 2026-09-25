# API 网关层：版本路由、租户限流与故障归一化

本地运行的 Spring Boot API 网关层。请求依次经过 **版本路由 → 租户限流 → 后端调用 → 失败归一化**，
在并发场景下给出一致且可解释的结果。

## 快速开始

```bash
mvn test          # 运行全部测试
mvn spring-boot:run
```

REST 入口：`POST /gateway/{route}`，请求头 `X-Tenant-Id`（租户）、`X-Api-Version`（接口版本），
请求体为任意字符串负载。内置示例路由 `echo`（`v1` 已弃用、`v2` 当前版本）。

## 版本兼容策略

版本生命周期由配置项 `gateway.versions.<版本号>` 声明，取值为 `ACTIVE` 或 `DEPRECATED`：

| 情形 | 判定 | 行为 |
| --- | --- | --- |
| 版本未登记 | `VERSION_UNSUPPORTED` | 拒绝，原因中列出支持的版本集合 |
| 版本为 `DEPRECATED` | 允许 | 仍路由到该版本实现，响应 `deprecatedVersion=true` 明确标记 |
| 版本为 `ACTIVE` | 允许 | 路由到该版本实现 |
| 版本合法但路由无对应实现 | `ROUTE_UNKNOWN` | 拒绝，**绝不静默落到最新版本** |

每个后端实现是一个 `BackendHandler` Bean，通过 `route()` 与 `version()` 声明自己服务的
路由与版本；`VersionRouter` 按 `route@version` 精确匹配。

## 限流判定规则

- 维度：按 `tenantId` 每租户一个**令牌桶**，租户之间完全隔离。
- 桶参数：容量 `gateway.rate-limit-capacity`，按 `gateway.rate-limit-refill-per-second`（个/秒）匀速补充。
- 放行：桶内令牌 ≥ 1 时取走一个并放行；否则拒绝，错误类别 `RATE_LIMITED`，
  响应携带 `retryAfterMillis`（补足一个令牌所需等待毫秒数）作为可重试提示。
- 并发一致性：每个桶的状态变更（补充 + 取令牌）在同一监视器锁内完成，
  并发下不超发、不漏限（见 `ConcurrentRateLimitTest`：200 并发对容量 20 的桶，恰好放行 20）。
- 额度归还：被限流拒绝的请求不消耗额度；后端处理**失败**的请求通过 `release()`
  归还其额度（不超过桶容量），即失败请求不得占用其本应释放的额度。

## 错误分类（归一化）

所有失败统一映射为 `ErrorCategory`，原始异常细节**不透出**给调用方
（响应中只有稳定语义的消息，异常类型仅记录在服务端日志）：

| 类别 | 语义 | HTTP 状态 |
| --- | --- | --- |
| `VERSION_UNSUPPORTED` | 客户端声明的版本不被支持 | 400 |
| `ROUTE_UNKNOWN` | 该版本下路由不存在 | 400 |
| `RATE_LIMITED` | 租户超限，附 `retryAfterMillis` | 429 |
| `BACKEND_TIMEOUT` | 后端超过 `gateway.backend-timeout-millis` 未返回 | 504 |
| `BACKEND_INTERNAL_ERROR` | 后端内部错误及一切未识别异常 | 502 |
| `BUSINESS_REJECTED` | 后端业务拒绝 | 422 |

归一化映射规则：`BusinessRejectedException` → `BUSINESS_REJECTED`；
`BackendTimeoutException` 或调用超时 → `BACKEND_TIMEOUT`；
`BackendInternalException` 及任何其他异常 → `BACKEND_INTERNAL_ERROR`。

## 配置项（`application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.versions.<version>` | `v1=DEPRECATED, v2=ACTIVE` | 版本生命周期（`ACTIVE`/`DEPRECATED`） |
| `gateway.rate-limit-capacity` | `10` | 每租户令牌桶容量 |
| `gateway.rate-limit-refill-per-second` | `10.0` | 每租户每秒补充令牌数 |
| `gateway.backend-timeout-millis` | `2000` | 后端调用超时（毫秒） |

## 测试

| 测试类 | 覆盖场景 |
| --- | --- |
| `ConcurrentRateLimitTest` | 200 并发下恰好放行容量数、超限请求带重试提示、租户隔离 |
| `VersionRoutingTest` | 版本不支持（可区分原因）、弃用版本可用且被标记、未知路由不静默落到最新版本 |
| `BackendFailureNormalizationTest` | 超时/内部错误/业务拒绝的分类映射、原始异常不透出、失败请求归还额度 |

每个用例的日志均打印 `[test-input]`（请求输入）与 `[test-basis]`（判定依据），
网关组件日志打印 `[version-route]` / `[rate-limit]` / `[gateway]` 判定过程，结论可复现。

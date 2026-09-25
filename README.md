# API 网关层（版本路由 + 租户限流 + 故障归一化）

本地运行的 Spring Boot 网关层，对外提供统一入口 `POST /gw/dispatch`，
在并发场景下保证版本路由、租户限流与后端故障处理的结果一致且可解释。

## 请求与响应

请求体：

```json
{ "tenantId": "tenant-a", "route": "echo", "version": "v2", "payload": "hello" }
```

成功响应（统一信封）：

```json
{ "success": true, "versionUsed": "v2", "deprecatedVersion": false, "data": { ... } }
```

失败响应（统一信封，失败原因可区分）：

```json
{ "success": false, "errorCode": "RATE_LIMITED", "errorReason": "tenant rate limit exceeded",
  "retryable": true, "versionUsed": "v2", "detail": { "tenantId": "tenant-a", "remainingCapacity": 0 } }
```

## 版本兼容策略

- 客户端通过 `version` 字段声明接口版本，当前注册版本为 `v1`、`v2`。
- **已弃用版本仍可用**：`v1` 标记为弃用，请求照常处理，但响应中 `deprecatedVersion=true` 明确标记。
- **不支持的版本被拒绝**：无法识别的版本字符串、或路由未注册该版本，均返回
  `UNSUPPORTED_VERSION`（HTTP 400），`detail.supportedVersions` 给出可用版本列表。
- **未知路由不回落**：路由不存在返回 `UNKNOWN_ROUTE`（HTTP 404），绝不静默落到最新版本。
- 路由与版本的注册关系集中在 `GatewayRoutesConfig`，新增版本只需注册新的 `BackendHandler`。

## 限流判定规则

- 维度：按 `tenantId` 独立计数，租户之间互不影响；缺省租户归入 `anonymous`。
- 算法：固定窗口计数。窗口内已放行数 `<` 租户额度则放行并计数 +1，否则拒绝。
- 并发安全：每个租户的「窗口重置判断 + 计数检查 + 递增」在同一 `synchronized`
  临界区内完成，并发下不超发、不漏限。
- 额度纪律：
  - 版本/路由不支持的请求在限流之前被拒绝，**不占用额度**；
  - 被限流拒绝的请求**从未获得额度**；
  - 后端调用失败/被拒绝的请求立即**归还额度**（`release`），不占用本应释放的额度。
- 限流拒绝返回 `RATE_LIMITED`（HTTP 429），`retryable=true` 提示可重试，
  `detail` 携带租户与剩余额度信息。

## 错误分类（归一化）

后端原始异常不会透出给客户端，统一映射为稳定的错误类别：

| 错误类别 | 触发条件 | HTTP | retryable |
| --- | --- | --- | --- |
| `UNSUPPORTED_VERSION` | 版本字符串无法识别 / 路由不支持该版本 | 400 | false |
| `UNKNOWN_ROUTE` | 路由未注册 | 404 | false |
| `RATE_LIMITED` | 租户窗口内额度耗尽 | 429 | true |
| `BUSINESS_REJECTED` | 后端抛出 `BusinessRejectionException` | 422 | false |
| `BACKEND_TIMEOUT` | 后端抛出 `TimeoutException` | 504 | true |
| `BACKEND_INTERNAL_ERROR` | 其他未识别异常 | 502 | true |

内部错误的对外文案固定为通用语句，原始异常内容（连接串、堆栈等）只记录在服务端日志。

## 配置项（application.properties）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.throttle.default-limit` | 10 | 每租户每窗口默认放行量 |
| `gateway.throttle.window-millis` | 60000 | 限流窗口时长（毫秒） |
| `gateway.throttle.tenant-limits.<tenantId>` | — | 指定租户的单独额度，覆盖默认值 |

## 测试

```bash
mvn test
```

- `VersionRoutingTests`：版本不支持 / 版本弃用标记 / 未知路由不回落。
- `ConcurrentThrottleTests`：50 并发下恰好放行 5 个（不超发不漏限）、
  业务失败归还额度、租户间隔离。
- `FailureNormalizationTests`：超时 / 内部错误 / 业务拒绝映射为可区分类别，
  原始异常不透出。

所有单测日志均打印请求输入（tenantId/route/version/payload）与判定依据
（路由决策、限流计数、归一化结果），失败原因可通过 `errorCode` 区分。

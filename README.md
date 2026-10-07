# API 网关层（版本路由 + 租户限流 + 故障归一化 + 响应时限 + 快速失败）

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
- 算法：固定窗口计数。窗口内仍占用额度的请求数 `<` 租户额度则放行并计数 +1，否则拒绝。
- 并发安全：每个租户的「窗口滚动判断 + 计数检查 + 递增」在同一 `synchronized`
  临界区内完成，并发下不超发、不漏限。
- 额度以**租约（Lease）**形式发放：`tryAcquire` 成功后返回一份与「申请时所在窗口」
  绑定的租约，请求结束（成功/失败/超时）时只能通过该租约归还。
- 额度纪律：
  - 版本/路由不支持的请求在限流之前被拒绝，**不占用额度**；
  - 被限流拒绝的请求**从未获得额度**；
  - 后端调用失败/被拒绝的请求立即**归还额度**（`lease.release()`），不占用本应释放的额度；
  - 超过响应时间上限的请求同样立即归还额度，并发超时场景下不多还、不漏还，
    后续放行量与实际成功量严格对齐；
  - **每份租约至多归还一次**（CAS 幂等）：超时/失败无论同时结束还是乱序结束，
    重复归还都会被忽略，窗口计数不可能被减成负数或减错；
  - **跨窗口归还语义**：归还只作用于租约申请时所在的窗口。请求在窗口已翻到下一段之后
    才超时结束时，额度归还到旧窗口（旧窗口计数随滚动作废），**绝不计入新窗口**，
    因此新窗口的实际放行量不可能被迟到归还抬高——任何一段窗口内实际放行请求数
    都不会超过该租户上限；
  - 熔断快速失败（`CIRCUIT_OPEN`）发生在限流之前，不占用额度。
- 限流拒绝返回 `RATE_LIMITED`（HTTP 429），`retryable=true` 提示可重试，
  `detail` 携带租户与剩余额度信息。

## 响应时间上限

- 每个请求都有响应时间上限：全局默认值 `gateway.timeout.default-millis` 对所有路由生效，
  个别路由可用 `gateway.timeout.route-overrides.<route>` 单独覆盖（覆盖优先于全局默认）。
- 后端调用在独立虚拟线程中执行，网关只在约定时限内等待结果；超过时限未返回即取消执行，
  按 `BACKEND_TIMEOUT`（HTTP 504，retryable=true）结束，**不会无限期挂在网关里**。
- 虚拟线程按任务创建，不受平台线程池容量限制：大批请求同时打到卡住的后端时，
  每一个都能在各自的时限内结束。
- 超时与后端主动抛 `TimeoutException` 走同一条归一化路径，对外错误类别一致、可区分。

## 快速失败（熔断）

- 维度：按「租户 + 路由」独立判定，互不影响。
- 触发：同一租户在同一路由上**连续超时**达到 `gateway.circuit.failure-threshold` 次，
  网关进入 `gateway.circuit.open-millis` 毫秒的保护期；期间任何成功调用都会把连续计数清零。
- 保护期内：请求在限流之前被直接挡下，返回 `CIRCUIT_OPEN`（HTTP 503，retryable=true），
  `detail.retryAfterMillis` 提示多久之后可以再试；**不消耗限流额度**，流量不再打到已卡住的后端。
- **保护期结束后的恢复（半开试探）**：保护期到期后**不会**清空连续超时记录并直接全量放行，
  而是先进入半开试探状态：
  - 只允许 `gateway.circuit.half-open-permits` 个（默认 **1**，极少量）探测请求真正打到后端，
    同一时刻到达的其余请求继续以 `CIRCUIT_OPEN` 快速失败（`detail.halfOpenProbing=true`）；
  - 探测请求**仍超时**（后端还没恢复）：**立即重新进入一轮完整保护期**，
    不需要再重新攒够失败次数，期间成批流量不会被持续压到已卡住的后端上；
  - 探测请求**成功**（后端真正恢复）：熔断器完全闭合，之后该放行的请求照常放行；
  - 探测请求若在时限内返回**非超时结果**（如业务拒绝、内部错误），同样说明后端可达、
    并未卡住，熔断器也会完全闭合（业务结果照常归一化返回）；只有「超时」才视为后端仍卡住；
  - 「保护期 → 半开探测 → 失败重开 / 成功闭合」的循环可重复发生，
    只要后端未恢复，每轮都只有极少量探测请求会触达后端。

## 错误分类（归一化）

后端原始异常不会透出给客户端，统一映射为稳定的错误类别：

| 错误类别 | 触发条件 | HTTP | retryable |
| --- | --- | --- | --- |
| `UNSUPPORTED_VERSION` | 版本字符串无法识别 / 路由不支持该版本 | 400 | false |
| `UNKNOWN_ROUTE` | 路由未注册 | 404 | false |
| `RATE_LIMITED` | 租户窗口内额度耗尽 | 429 | true |
| `CIRCUIT_OPEN` | 连续超时触发快速失败，保护期内挡流 | 503 | true |
| `BUSINESS_REJECTED` | 后端抛出 `BusinessRejectionException` | 422 | false |
| `BACKEND_TIMEOUT` | 后端超过响应时间上限未返回 / 抛出 `TimeoutException` | 504 | true |
| `BACKEND_INTERNAL_ERROR` | 其他未识别异常 | 502 | true |

内部错误的对外文案固定为通用语句，原始异常内容（连接串、堆栈等）只记录在服务端日志。

## 配置项（application.properties）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.throttle.default-limit` | 10 | 每租户每窗口默认放行量 |
| `gateway.throttle.window-millis` | 60000 | 限流窗口时长（毫秒） |
| `gateway.throttle.tenant-limits.<tenantId>` | — | 指定租户的单独额度，覆盖默认值 |
| `gateway.timeout.default-millis` | 2000 | 全局默认响应时间上限（毫秒） |
| `gateway.timeout.route-overrides.<route>` | — | 指定路由的响应时间上限，覆盖全局默认 |
| `gateway.circuit.failure-threshold` | 3 | 触发快速失败所需的连续超时次数 |
| `gateway.circuit.open-millis` | 30000 | 快速失败保护期时长（毫秒），到期后进入半开试探 |
| `gateway.circuit.half-open-permits` | 1 | 半开试探期允许同时打到后端的探测请求数（极少量）；探测超时立即重开，探测成功才闭合 |

## 兼容范围

- **版本维度**：响应时间上限、限流与快速失败（含半开试探）对所有已注册版本
  （含已弃用的 `v1` 与当前 `v2`）一致生效；超时上限与熔断阈值按路由名配置，
  与该路由命中的版本无关。版本不支持 / 未知路由的请求在超时、限流与熔断判定之前
  就被拒绝，行为不变。跨窗口额度归还与租约幂等是限流器内部语义，与版本无关。
- **租户维度**：限流额度（含每窗口上限与租约）、熔断状态（闭合/保护期/半开）都按
  `tenantId` 隔离，互不影响；缺省租户归入 `anonymous`，同样受固定窗口额度、
  超时上限与熔断半开规则约束。某租户的迟到归还只会作废在其自身旧窗口上，
  不会影响本租户的新窗口，更不会影响其他租户。
- **配置兼容**：新增配置项 `gateway.circuit.half-open-permits` 有默认值（1），
  不配置时行为为「全局 2s 超时上限 + 连续 3 次超时熔断 30s + 保护期后单请求试探」；
  既有 `gateway.throttle.*`、`gateway.timeout.*`、`gateway.circuit.*` 配置语义不变。

## 本地验证

```bash
mvn test          # 全量用例（含版本路由、限流、故障归一化回归）
mvn test -Dtest=ResponseTimeoutTests            # 只看响应时间上限
mvn test -Dtest=TimeoutQuotaAccountingTests     # 只看并发超时下的额度账
mvn test -Dtest=CrossWindowQuotaTests           # 只看跨窗口迟到归还不抬高新窗口
mvn test -Dtest=TenantRateLimiterWindowLeaseTests # 只看租约幂等/跨窗口/重复归还
mvn test -Dtest=CircuitBreakerTests             # 只看快速失败与自动恢复
mvn test -Dtest=CircuitBreakerHalfOpenTests     # 只看保护期后半开试探与立即重开
```

手动验证（本地启动后）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
# 正常请求（默认配置 server.port=0 为随机端口，手动验证时显式指定）
curl -X POST localhost:8080/gw/dispatch -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","route":"echo","version":"v2","payload":"hi"}'
```

调整 `gateway.timeout.default-millis` 为较小值（如 1ms）即可观察到
`BACKEND_TIMEOUT`；连续超时达到 `gateway.circuit.failure-threshold` 后可观察到
`CIRCUIT_OPEN` 及 `detail.retryAfterMillis`；等待 `gateway.circuit.open-millis`
后熔断器进入半开试探，此时只有第 1 个请求会真正打到后端：
后端仍超时时立刻再次返回 `CIRCUIT_OPEN`（无需再攒失败次数），
后端恢复时该探测请求成功、随后流量完全恢复。

复现「跨窗口迟到归还」可同时把 `gateway.throttle.window-millis` 调到比
`gateway.timeout.default-millis` 更短（例如窗口 200ms、超时 500ms）：
在窗口一占满额度并打到卡住后端的请求会在窗口二才超时结束，
观察窗口二实际放行量仍严格等于租户上限，不会被这些迟到归还抬高。

## 测试

```bash
mvn test
```

- `VersionRoutingTests`：版本不支持 / 版本弃用标记 / 未知路由不回落。
- `ConcurrentThrottleTests`：50 并发下恰好放行 5 个（不超发不漏限）、
  业务失败归还额度、租户间隔离。
- `FailureNormalizationTests`：超时 / 内部错误 / 业务拒绝映射为可区分类别，
  原始异常不透出。
- `ResponseTimeoutTests`：后端真实卡住（不抛异常、永不返回）时按全局默认/路由覆盖的
  时限结束并返回 `BACKEND_TIMEOUT`；30 个并发卡住请求每一个都按时结束。
- `TimeoutQuotaAccountingTests`：正常与超时请求混杂并发下额度账自洽——
  超时额度恰好归还，后续放行量与归还量一致，不误限也不超发。
- `TenantRateLimiterWindowLeaseTests`：额度租约单元语义——
  同窗口重复归还只生效一次、跨窗口归还不触碰新窗口、多线程并发重复归还计数不错乱、
  计数不会减成负数。
- `CrossWindowQuotaTests`：用「永不返回的后端 + 短窗口（超时必然跨窗口）」复现压测场景——
  旧窗口占满额度的卡住请求在新窗口才超时结束，新窗口实际放行量严格等于上限、
  本该放行的请求不被误限、迟到归还后新窗口剩余额度仍为 0。
- `CircuitBreakerTests`：连续超时触发 `CIRCUIT_OPEN` 并携带 `retryAfterMillis`，
  保护期内不再打到后端、不占额度，保护期结束进入恢复流程，熔断按租户隔离。
- `CircuitBreakerHalfOpenTests`：保护期结束后只放 1 个探测请求；
  后端仍卡住时探测超时立即重新进入保护期（无需再攒次数），半开边界的并发突发只有
  1 个请求触达后端、其余继续快速失败；探测返回非超时结果（业务拒绝）也判定后端可达并闭合；
  后端恢复后探测成功完全闭合；多轮试探可重复。

所有单测日志均打印请求输入（tenantId/route/version/payload）与判定依据
（路由决策、限流计数/窗口、归一化结果、半开探测与重开），失败原因可通过 `errorCode` 区分。

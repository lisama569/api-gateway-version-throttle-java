# API 网关层（版本路由 + 租户限流 + 响应时间上限 + 快速失败 + 故障归一化）

本地运行的 Spring Boot 网关层，对外提供统一入口 `POST /gw/dispatch`，
在并发场景下保证版本路由、租户限流、后端超时/快速失败与故障处理的结果一致且可解释。

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

快速失败（熔断打开）时额外给出「多久后可再试」提示：

```json
{ "success": false, "errorCode": "CIRCUIT_OPEN",
  "errorReason": "backend temporarily unavailable due to repeated timeouts, please retry later",
  "retryable": true, "versionUsed": "v2",
  "detail": { "tenantId": "tenant-a", "route": "echo", "retryAfterMillis": 4123 } }
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
  - 熔断器 OPEN 期间的快速失败不打向后端，**也不占用额度**；
  - 被限流拒绝的请求**从未获得额度**；
  - 放行时发放一个**携带窗口序号（epoch）的许可（Permit）**，后端调用失败/超时后
    通过该许可归还额度：同一许可重复归还只生效一次（**不多还**）；窗口已滚动时
    不会误减新窗口计数（**不跨窗口错还**）；每个被放行的请求最终要么成功占用、
    要么恰好归还一次（**不漏还**）。
- 限流拒绝返回 `RATE_LIMITED`（HTTP 429），`retryable=true` 提示可重试，
  `detail` 携带租户与剩余额度信息。

## 响应时间上限（请求超时）

- 每个请求都有硬性的响应时间上限：后端在时限内不返回，网关即在约定时限附近结束请求，
  归一化为 `BACKEND_TIMEOUT`（HTTP 504，`retryable=true`），**不会无限期挂起**。
- 配置支持两级：全局默认 `gateway.timeout.default-millis`，
  以及按路由覆盖 `gateway.timeout.route-millis.<route>`；路由未配置时回落默认值。
  该上限**按路由生效、与 API 版本无关**：同一路由的 v1/v2 共用同一份超时配置。
- 实现方式：每个请求在独立的虚拟线程上执行后端调用，主流程用 `Future.get(timeout)`
  等待并在超时后中断后端线程。大批请求同时卡住时互不排队，**每一个都在各自时限内结束**。
- 超时与后端直接抛出 `TimeoutException` 的归一化结果相同，客户端可用统一方式处理；
  但测试会区分「真正挂起」与「直接抛异常」两种后端行为。

## 快速失败（租户 + 路由熔断）

- 维度：`tenantId + route`，状态机与计数在该 key 上独立，租户之间、路由之间互不影响。
- 触发：同一租户在同一路由上**连续**超时达到
  `gateway.circuit-breaker.failure-threshold` 次即进入 OPEN 保护期
  （任何非超时结果——成功/业务拒绝/其他错误——都会把连续计数清零）。
- OPEN：保护期（`gateway.circuit-breaker.open-millis`）内请求**直接快速失败**，
  返回 `CIRCUIT_OPEN`（HTTP 503，`retryable=true`），`detail.retryAfterMillis`
  提示多少毫秒后可再试，不再把流量打到已卡住的后端，也不占用限流额度。
- 恢复：保护期到期后的第一个请求被试探性放行（HALF_OPEN）；该请求超时则重新打开保护期，
  得到任何非超时结果则回到 CLOSED，正常流量完全恢复——**自动恢复，无需人工干预**。

## 错误分类（归一化）

后端原始异常不会透出给客户端，统一映射为稳定的错误类别：

| 错误类别 | 触发条件 | HTTP | retryable |
| --- | --- | --- | --- |
| `UNSUPPORTED_VERSION` | 版本字符串无法识别 / 路由不支持该版本 | 400 | false |
| `UNKNOWN_ROUTE` | 路由未注册 | 404 | false |
| `RATE_LIMITED` | 租户窗口内额度耗尽 | 429 | true |
| `BUSINESS_REJECTED` | 后端抛出 `BusinessRejectionException` | 422 | false |
| `BACKEND_TIMEOUT` | 后端在响应时间上限内未返回（真正挂起）/ 抛出 `TimeoutException` | 504 | true |
| `BACKEND_INTERNAL_ERROR` | 其他未识别异常 | 502 | true |
| `CIRCUIT_OPEN` | 同租户同路由连续超时，处于快速失败保护期 | 503 | true |

内部错误的对外文案固定为通用语句，原始异常内容（连接串、堆栈等）只记录在服务端日志。

## 配置项（application.properties）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.throttle.default-limit` | 10 | 每租户每窗口默认放行量 |
| `gateway.throttle.window-millis` | 60000 | 限流窗口时长（毫秒） |
| `gateway.throttle.tenant-limits.<tenantId>` | — | 指定租户的单独额度，覆盖默认值 |
| `gateway.timeout.default-millis` | 10000 | 全局默认响应时间上限（毫秒）；`application.properties` 中设为 3000 |
| `gateway.timeout.route-millis.<route>` | — | 指定路由的响应时间上限，覆盖全局默认值 |
| `gateway.circuit-breaker.failure-threshold` | 5 | 同租户同路由连续超时多少次后进入快速失败 |
| `gateway.circuit-breaker.open-millis` | 5000 | 快速失败保护期时长（毫秒），到期自动试探恢复 |

## 兼容性说明

- **版本维度**：响应时间上限按路由配置，对该路由注册的所有版本（当前 v1/v2）一视同仁；
  熔断与限流只区分租户（+路由），不区分版本。v1 仍为「已弃用但可用」，
  新能力不会改变版本路由与弃用标记的既有行为。
- **租户维度**：超时上限对所有租户相同（按路由配置，不按租户）；限流额度与熔断状态
  严格按租户隔离。未携带 `tenantId` 的请求归入 `anonymous`，同样适用全部规则。
- **灰度/关闭**：把熔断阈值调大即可在效果上关闭快速失败；超时上限应始终配置为正数，
  未配置时使用代码内安全默认值 10s。

## 测试

```bash
mvn test
```

- `VersionRoutingTests`：版本不支持 / 版本弃用标记 / 未知路由不回落。
- `ConcurrentThrottleTests`：50 并发下恰好放行 5 个（不超发不漏限）、
  业务失败归还额度、租户间隔离。
- `FailureNormalizationTests`：超时 / 内部错误 / 业务拒绝映射为可区分类别，
  原始异常不透出。
- `BackendStabilityTests`：后端稳定性，共 9 个用例：
  - 后端**真正挂起**（永久 `await` 而非抛异常）时在全局默认时限内结束并给出
    `BACKEND_TIMEOUT`，且归还额度；
  - 单路由超时覆盖优先于全局默认；
  - 24 并发全部打到挂起后端：恰好 limit 个超时、其余限流，墙钟时间不串行叠加，
    每个请求都贴近自己的时限结束，额度一分不少归还；
  - 2 成功 + 2 超时混合批次后剩余额度精确为 3，再放 3 个成功、第 6 个被限流；
  - 许可重复归还幂等，不多还；
  - 窗口滚动后才归还的旧许可不会污染新窗口计数（不跨窗口错还）；
  - 连续超时到阈值后进入 `CIRCUIT_OPEN`（不打后端、不占额度、带 `retryAfterMillis`），
    保护期后试探成功自动恢复；
  - 熔断按租户 + 路由隔离；
  - HALF_OPEN 试探仍超时则重新打开保护期。

所有单测日志均打印请求输入（tenantId/route/version/payload）与判定依据
（路由决策、限流计数、超时耗时、熔断状态、归一化结果），失败原因可通过 `errorCode` 区分。

## 本地手工验证

启动应用后，`POST /gw/dispatch` 的响应时间上限由 `gateway.timeout.*` 控制。
由于内置 echo 后端会立即返回，超时/熔断路径建议以单测为准复现
（`BackendStabilityTests` 中用永久阻塞的 handler 模拟真正卡住的后端）：

```bash
# 只跑稳定性用例，观察 [stability] 日志中的输入、耗时与判定依据
mvn test -Dtest=BackendStabilityTests

# 全量回归
mvn test
```

可在 `application.properties` 调小 `gateway.timeout.default-millis`、
`gateway.circuit-breaker.failure-threshold` 与 `open-millis` 后，
通过单测或注入一个阻塞式 `BackendHandler` 观察 504 与 503（带 `retryAfterMillis`）。

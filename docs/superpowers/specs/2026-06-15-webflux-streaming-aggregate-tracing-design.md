# wow-webflux 流式 Aggregate Tracing 设计补充

## 背景

`wow-webflux` 架构优化已经把普通 `Flux<T>` 响应迁移为 streaming JSON array，并为
aggregate tracing 增加了 `headVersion`、`tailVersion` 和 `limit` 窗口语义。
当前生产 handler 仍有一处结构性折中：`AggregateTracingHandlerFunction.handle` 会先
`collectList()` 完整物化单聚合事件历史，再计算窗口并输出 `Flux<StateEvent<ObjectNode>>`。

这保住了状态正确性，但没有完全达到目标架构中 “WebFlux adapter 优先 Reactor-first”
的要求。下一步目标是移除这处全量事件列表物化，让 tracing replay 本身也成为流式编排。

## 目标

- 移除 `AggregateTracingHandlerFunction.handle` 中的 `collectList()`。
- 保持现有 HTTP API、query 参数、响应 JSON shape、SSE 行为和 `ERROR_CODE` header 兼容。
- 默认 full history tracing 以事件流为输入，边 replay 边 emit。
- `headVersion`/`tailVersion` 显式窗口以事件流为输入，边 replay 边只 emit 窗口内状态。
- `limit` tail-window 语义保持状态正确：必须 replay 全历史，但只保留最后 N 个输出状态。
- Handler 继续保持薄编排：request context -> event store Flux -> tracing replay -> response strategy。
- 不改变 `EventStore` 接口，不要求存储层支持 range 查询。

## 非目标

- 不引入 EventStore range API。
- 不改变 aggregate tracing 的公开路由、OpenAPI schema 或默认返回完整历史的行为。
- 不把 tracing replay 逻辑下沉到 `wow-core` 或存储模块。
- 不在这一步更新 checked-in benchmark 报告。
- 不解决长历史下必须 replay 前缀才能得到正确状态的根本成本；这属于存储快照或 range-query 能力的后续问题。

## 设计

新增一个 WebFlux adapter 内部的 tracing replay 单元，命名为
`AggregateTracingReplay`，放在 `me.ahoo.wow.webflux.route.state` 包下。

它负责把：

```text
StateAggregateMetadata<S>
StateAggregateFactory
Flux<DomainEventStream>
TracingRange
```

转换成：

```text
Flux<StateEvent<ObjectNode>>
```

`AggregateTracingHandlerFunction` 不再关心 replay 细节，只负责：

1. 从 `ServerRequest` 构建 `WowWebRequestContext`。
2. 调用 `eventStore.load(context.aggregateId)` 得到 `Flux<DomainEventStream>`。
3. 使用 replay 单元生成 `Flux<StateEvent<ObjectNode>>`。
4. 调用现有 `toServerResponse(request, exceptionHandler)` 输出 streaming JSON array。

## Range 计算

当前 `TracingPolicy.range(request, totalVersion)` 依赖 `totalVersion`。移除全量列表后，
`totalVersion` 不能提前获得，因此 replay 单元需要把 range 计算拆成两类：

- 显式 `headVersion`/`tailVersion`：不依赖最终总版本，可在订阅前确定。
- `limit` tail-window 或默认 full history：需要知道流中实际经过的版本。

为保持 `TracingPolicy` 职责清晰，新增一个只描述请求参数的内部数据结构
`TracingRequest`：

```text
headVersion: Int?
tailVersion: Int?
limit: Int?
```

`TracingRequest` 只在 `wow-webflux` 内部使用，不作为新的公开 API 承诺。

## Replay 策略

### Full History

没有 query 参数时，逐条 source event stream，并在每条事件 source 后 emit 当前状态快照。
不需要缓存全部事件或全部输出。

### Explicit Range

当请求包含 `headVersion` 或 `tailVersion` 时：

- replay 从第一条事件开始，以保证状态正确；
- event version 大于 `tailVersion` 后可以停止后续 source；
- 只在 `event.version >= headVersion` 时 emit；
- 如果请求范围非法，继续使用现有错误映射返回错误响应。

### Tail Limit

当请求只包含 `limit`，并且没有显式 `headVersion` 时：

- replay 必须从第一条事件开始，以得到最终窗口内每个状态的正确前缀状态；
- 不保留完整事件列表；
- 使用大小为 `limit` 的有界 ring buffer 保存最后 N 个 `StateEvent<ObjectNode>`；
- 上游完成后再按原始顺序 emit ring buffer 内容；
- `limit = 0` 继续表示空输出；负数 limit 继续作为非法参数处理。

这仍然会保留最后 N 个状态快照，但不会保留全部事件历史。相较当前 `collectList()`，
内存上界从完整 `DomainEventStream` 历史变为最多 N 个输出状态。

## 错误语义

- 参数非法错误应发生在 response body 写出前，继续走 `RequestExceptionHandler`。
- replay 过程中如果事件 source 或状态转换失败，streaming JSON array 已开始后只能终止写出；
  这与现有 streaming response error boundary 一致。
- 空事件流返回空 JSON array。

## 兼容性

- 保留现有 `StateAggregateMetadata.trace(List<DomainEventStream>)` helper，避免破坏 benchmark 或测试调用。
- 新增 `trace(Flux<DomainEventStream>, ...)` helper 时仅供 WebFlux 内部使用，不要求外部调用方迁移。
- `AggregateTracingHandlerFunctionFactory` 构造器保持兼容。
- HTTP 行为不变：默认仍返回完整 tracing history，窗口参数仍返回窗口内状态。

## 测试策略

采用 TDD，先写失败测试再实现：

- `handle` 不再对 `eventStore.load(...)` 结果做全量 `collectList()`：用一个无法被一次性收集或会暴露过度请求的测试 Flux 验证 handler 可以先产生 `ServerResponse`。
- Full history replay 输出和旧 helper 一致。
- Explicit range replay 会 replay 前缀但只 emit 请求窗口。
- Tail limit replay 只 emit 最后 N 个状态，并保持状态正确。
- 空事件流返回空数组。
- `rg -n "collectList\\(" wow-webflux/src/main/kotlin` 不再命中 aggregate tracing handler。

## 验证

实现完成后至少运行：

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.*AggregateTracing*"
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest"
./gradlew --console=plain :wow-benchmarks:compileJmhKotlin
rg -n "collectList\\(" wow-webflux/src/main/kotlin
```

如果改动影响 benchmark helper，再追加运行：

```bash
./gradlew --console=plain :wow-benchmarks:benchmarkSmoke
```

## 后续演进

这一步只移除 WebFlux adapter 的全量事件列表物化。长历史 aggregate tracing 的更深层优化
仍需要事件存储 range 查询、状态快照恢复点、或 tracing 专用 projection 支持。那些能力跨越
`wow-webflux` 模块边界，应作为独立设计推进。

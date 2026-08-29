# Task 3 报告：聚合级 Gateway、around Context 与 ObjectNode masking

## 状态

已完成 Task 3 的原子切换：`QueryGateway` 绑定单个聚合与单个 Backend，五类操作共用一条 around FilterChain；查询链中的文档结果统一为 Jackson 3 `ObjectNode`，typed 结果仅在全部结果 Filter/mask 完成后物化；旧 QueryService、Tail Filter、DynamicDocument masker 与对象级 masking 已删除。

由于 `wow-query` 的测试编译直接依赖 `project(":wow-tck")`，删除 QueryService API 后两份旧 TCK 会阻断 `:wow-query:check`。经任务负责人明确批准，本任务额外把它们最小重命名并迁移为 Backend spec；未增加 Task 4 的 Gateway contract test，也未扩展 Backend TCK 覆盖。

## TDD 证据

### RED：聚合级 Gateway 公共合同

命令：

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.QueryGatewayApiTest" --stacktrace
```

相关输出：

```text
QueryGatewayApiTest > gateway should be aggregate bound without exposing handler contract() FAILED
Expecting value to be true but was false

QueryGatewayApiTest > gateway should expose the five object-node operations and typed variants() FAILED
java.lang.NoSuchMethodException: me.ahoo.wow.query.QueryGateway.single(me.ahoo.wow.api.query.ISingleQuery)

2 tests completed, 2 failed
BUILD FAILED
```

失败原因与预期一致：旧 Gateway 未绑定聚合，且查询方法仍要求 `NamedAggregate` 参数。

### RED：原子删除后的直接依赖缺口

命令：

```bash
./gradlew :wow-query:compileTestKotlin --stacktrace
```

相关输出：

```text
test/wow-tck/.../EventStreamQueryServiceSpec.kt: Unresolved reference 'EventStreamQueryService'
test/wow-tck/.../SnapshotQueryServiceSpec.kt: Unresolved reference 'SnapshotQueryService'
:wow-tck:compileKotlin FAILED
```

这证明保留旧 QueryService 兼容桥并非可接受解法；按批准范围把两份直接依赖的 TCK 最小迁移为 Backend spec。

### GREEN：聚焦合同

命令：

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.*" --tests "me.ahoo.wow.query.snapshot.*" --tests "me.ahoo.wow.query.event.*" --tests "me.ahoo.wow.query.filter.*" --tests "me.ahoo.wow.query.mask.*" --stacktrace
```

相关输出：

```text
> Task :wow-query:test
BUILD SUCCESSFUL
```

覆盖：typed/dynamic 共用 `QueryType` 与 around 链、五类 Backend 转发、模型 Filter 精确选择、retry/repeat/并发订阅的 Context 与 ObjectNode identity 隔离、request/backend/result/mask/typed conversion 错误边界、handler suppressed/self-suppressed 规则、Snapshot/EventStream single/list/paged masking、COUNT/AGGREGATION 不 masking、非法 envelope fail-closed。

### GREEN：模块检查

命令：

```bash
./gradlew :wow-query:check --stacktrace
```

相关输出：

```text
> Task :wow-query:test
> Task :wow-query:check
BUILD SUCCESSFUL
```

直接依赖的 TCK 也单独验证：

```bash
./gradlew :wow-tck:check --stacktrace
```

相关输出：

```text
> Task :wow-tck:test
> Task :wow-tck:check
BUILD SUCCESSFUL
```

## 实现与文件

- `QueryGateway.kt`：公共 API 改为聚合级 `NamedAggregateDecorator`；`AbstractQueryGateway` 构造一条模型筛选后的 FilterChain，私有 Backend lambda 为唯一 terminal；每次订阅创建新 Context；raw Publisher、typed conversion 与 ErrorHandler 均位于同一错误边界。
- `SnapshotQueryGateway.kt` / `EventStreamQueryGateway.kt`：分别绑定 `SnapshotQueryBackend` / `EventStreamQueryBackend`；Snapshot 接收已构造的参数化 `JavaType`，EventStream 直接使用 `DomainEventStream` 目标类型。
- `QueryContext.kt` / `QueryType.kt` / `QueryFilter.kt`：仅保留五个操作类型；SINGLE/LIST/PAGED/AGGREGATION 的原始 Publisher 固定为 ObjectNode；通用 QueryFilter 不再声明 `@FilterType`。
- Snapshot/EventStream 专用 Filter：保留精确 Gateway `@FilterType`，删除 Tail Filter；mask Filter 在 terminal 之后只重写 raw Publisher。
- `ObjectNodeMasker.kt` / `CompositeObjectNodeMasker.kt` / `ObjectNodeMaskerRegistry.kt`：替代 DynamicDocument masker，按 materialized `NamedAggregate` 注册和顺序组合 Snapshot/EventStream 专用 masker。
- `SnapshotStates.kt`：动态 state 通过 `ObjectNode.path("state")` 取得，并拒绝缺失或非 ObjectNode state。
- Snapshot/Event Query DSL：执行接收者改为对应聚合级 Gateway，移除每次调用的 `NamedAggregate` 参数。
- 删除 QueryService、旧 Factory/Routing、Tail、DynamicDocument masking、DataMasking 及旧兼容测试。
- `SnapshotQueryBackendSpec.kt` / `EventStreamQueryBackendSpec.kt`：从旧 QueryService spec 最小迁移，直接验证 raw Backend；这是为满足 `wow-query` 的直接测试依赖而获准扩展的范围。

## 类型物化与错误边界决策

- Backend terminal 只依据五个 `QueryType` 调用构造时绑定的 Backend，并把原始 Publisher 写入 Context；不接收 Factory，不参与 Filter 排序或 Bean 发现。
- typed `single/list/paged` 在 raw Filter/mask Publisher 之后直接调用仓库现有 `ObjectNode.convert(JavaType)`；没有新增 converter 类型。
- dynamic 与 typed 使用相同 `SINGLE/LIST/PAGED`；区别仅是最终是否转换。
- Snapshot/EventStream mask 对每个 raw ObjectNode 执行一次；COUNT 与 AGGREGATION 在 registry lookup 前直接跳过。
- 错误边界覆盖 chain invocation 与完整结果 Publisher。ErrorHandler 正常结束后仍传播原始错误；handler 抛出不同错误时附加为 suppressed；同一实例不会 self-suppress。
- masker 破坏 envelope 时 dynamic 返回实际 mask 输出，typed conversion 失败并经过同一 ErrorHandler，未恢复字段或绕过 mask。

## 自审

- 未新增 QueryRouter、GatewayFactory、第二条 FilterChain、ResultConverter、兼容桥或依赖。
- Backend terminal 为 `AbstractQueryGateway` 私有方法引用；没有 Filter/Bean，也没有请求时 Backend 路由。
- `Mono.defer` / `Flux.defer` 位于每个公开操作外层；retry、repeat、并发测试以 identity 而非结构 equality 验证 Context/ObjectNode 隔离。
- request Filter 正序、result Filter 逆序；Backend terminal 在两者之间写入原始 Publisher。
- static scan 在 `wow-query/src/main`、`wow-query/src/test` 与迁移后的 `test/wow-tck/src/main` 中未发现 QueryService、DynamicDocument、Tail、DataMasking 或 dynamic QueryType 遗留。
- `git diff --check` 通过；`wow-query` 与 `wow-tck` detekt/check 通过。
- Task 2 Backend API 未做任何修改。

## 关注项

- MongoDB、Elasticsearch、Spring、Starter、WebFlux 等下游模块仍引用旧 QueryService/TCK 名称，按任务边界留给后续任务迁移；因此本提交只证明 `:wow-query:check` 与直接依赖的 `:wow-tck:check`，不声称全仓构建通过。
- 两份 TCK 本次只做最小 raw Backend 迁移，Task 4 仍需按计划审查并深化 Backend/Gateway 合同覆盖。

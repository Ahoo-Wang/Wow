# wow-webflux Runtime Contract 重构设计

## 背景

`wow-openapi` 已经完成 Route Contract Kernel 重构，`wow-schema` 也完成 Schema Contract Kernel 重构。当前 `wow-webflux` 是下游运行时承接点：它读取 `RouterSpecs.toRouteCatalog().routes`，把 `HttpRouteContract` 转换成 Spring WebFlux `RouterFunction<ServerResponse>`，并通过各类 `HttpRouteHandlerFunctionFactory` 创建具体处理器。

这一层现在可以工作，也已经通过 RESTful API 兼容验证，但代码形态仍停留在“适配新 contract”的阶段：

- `RouterFunctionBuilder` 同时负责遍历 contract、构造 Spring predicate、查找 factory、创建 handler。
- 多数 handler factory 重复执行 `HttpRouteHandlerMetadata` 类型检查与 metadata 解包。
- `wow-spring-boot-starter` 负责收集 `WebFluxRouteModule` 与 standalone factory，但 runtime registry 的覆盖规则、诊断信息和装配边界还可以更清楚。
- `wow-webflux` 直接依赖 `wow-openapi` 的 contract 类型，这个依赖可以接受，但必须把它控制在 runtime contract 边界上，不让 OpenAPI 渲染细节渗透到 WebFlux 执行路径。

## 目标

本轮目标是把 `wow-webflux` 整理为清晰的 REST runtime contract 运行层。

1. 保持 RESTful API 兼容：路径、HTTP method、accept、handler key、route id、请求解析与响应语义不变。
2. 收敛 `HttpRouteContract -> RouterFunction` 的转换责任，让 route materialization 的边界更清楚。
3. 抽出 handler factory 的共享 contract 解包逻辑，减少重复的 `requireAggregateHandlerMetadata` / `requireCommandHandlerMetadata` 样板代码。
4. 明确 factory registry 的职责：注册、覆盖、查找、错误诊断，不承载具体业务 handler 构造。
5. 让 `wow-spring-boot-starter` 继续只负责装配，不把 WebFlux runtime 行为继续推到 auto-configuration 里。

## 非目标

1. 不改变 RESTful API 合约。
2. 不追求 Kotlin/JVM public API 兼容；如果 public 类型阻碍清洁架构，可以直接改。
3. 不重构 `wow-openapi` contributor 结构，除非 `wow-webflux` 的 runtime contract 发现必须回补极小字段。
4. 不做性能专项优化；如果顺手减少重复解析可以接受，但不把 benchmark 作为本轮主线。
5. 不改变 command/event/state/snapshot/query 的业务语义。

## 设计原则

### Contract 是输入，不是 OpenAPI 渲染模型

`wow-webflux` 可以依赖 `HttpRouteContract`、`HttpRouteHandlerMetadata`、`BuiltInHttpRouteHandlerKeys` 这些 contract 类型，但不应该依赖 OpenAPI schema/render/contributor 的实现细节。运行时只关心：

- 路由如何匹配请求。
- 哪个 handler key 对应哪个 handler factory。
- handler 构造需要哪些 aggregate/command metadata。
- handler 如何处理请求、异常和响应。

### Route materialization 独立成层

现有 `RouterFunctionBuilder` 应拆成更明确的构造链：

- contract validation：检查 method、accept、handler factory 是否存在。
- predicate construction：把 `HttpRouteContract` 转换成 Spring `RequestPredicate`。
- handler resolution：用 handler key 与 metadata 创建 `HandlerFunction<ServerResponse>`。
- router assembly：按 catalog 顺序注册 route。

这样可以单独测试每个失败场景，也能防止未来 route 排序、predicate 规则或 factory 解析继续堆到一个类里。

### Handler factory 以 metadata 类型表达职责

大量 factory 现在重复：

```kotlin
metadata.requireAggregateHandlerMetadata(handlerKey).aggregateRouteMetadata.aggregateMetadata
```

或：

```kotlin
metadata.requireCommandHandlerMetadata(handlerKey)
```

应引入轻量抽象或辅助构造器，例如：

- aggregate metadata based factory support
- command metadata based factory support
- no metadata global factory support

这些抽象只解决 contract 解包与错误诊断，不吞并具体 handler 的构造逻辑。具体 handler 仍留在 command/event/state/snapshot/query 各自包内。

### Registry 只负责 registry

`RouteHandlerFunctionRegistrar` 应保持简单，但需要更明确：

- 初始化时合并 module factories 与 standalone factories。
- 后注册覆盖先注册的规则应可测试、可诊断。
- 缺失 handler key 的错误信息要包含 route id、method、path、handler key。
- 不在 registry 中创建 Spring predicate，也不感知业务 metadata。

### Starter 只做装配

`wow-spring-boot-starter` 的 `WebFluxAutoConfiguration` 可以随着 runtime API 调整构造方式，但它不应承载 runtime 决策。WebFlux route modules 继续提供 factory 集合，starter 只负责按 Spring order 合并并注入。

## 拟调整范围

### wow-webflux

核心调整范围：

- `route/RouterFunctionBuilder.kt`
- `route/RouteHandlerFunctionRegistrar.kt`
- `route/RouteHandlerFunctionFactory.kt`
- `route/HttpRouteHandlerMetadataSupport.kt`
- command/event/state/snapshot/query/global/wait 下的 `*HandlerFunctionFactory`
- 相关 route tests 与 contract tests

可新增的内部类型：

- `HttpRouteMaterializer` 或等价命名：负责单条 contract materialization。
- `HttpRoutePredicateFactory` 或等价命名：负责 Spring predicate 构造。
- `AggregateRouteHandlerFunctionFactorySupport`：聚合 metadata 解包。
- `CommandRouteHandlerFunctionFactorySupport`：命令 metadata 解包。
- `GlobalRouteHandlerFunctionFactorySupport`：无 metadata 或通用 metadata 场景。

命名以实际代码落点为准，避免为了抽象而抽象。

### wow-spring-boot-starter

只允许配套改动：

- `WebFluxAutoConfiguration` 注入新的 runtime builder/materializer 类型。
- `WebFluxRouteModule` 或各 route module 的 factory 暴露方式若必须调整，可以改，但保持 Spring bean 行为兼容。
- 保留现有 auto-configuration 测试并补充覆盖 factory 覆盖顺序。

### wow-openapi

默认不改。只有当 `wow-webflux` 证明 contract 缺少运行时必需字段时，才做最小补充，并同步 OpenAPI snapshot。

## 实施分段

### Phase 1：锁定 REST runtime 兼容基线

- 复查现有 `TestHttpRouteContracts`、`RouterFunctionBuilderTest`、`RouteHandlerFunctionRegistrarContractTest`。
- 补充必要断言：route 顺序、缺失 handler key 诊断、metadata 类型错误诊断。
- 确认 OpenAPI snapshot 仍覆盖路径、method、accept 和 route id。

### Phase 2：抽出 route materialization

- 从 `RouterFunctionBuilder` 中抽出单条 route 的 predicate 与 handler resolution。
- 保持 `RouterFunctionBuilder.build()` 的外部行为不变。
- 用单元测试覆盖合法 contract 与错误 contract。

### Phase 3：抽出 handler factory support

- 先从重复最密集的 aggregate metadata factory 入手。
- 再处理 command metadata factory。
- 最后处理 global/no metadata factory。
- 每一类抽象只下沉 metadata 解包和错误信息，不移动业务 handler 行为。

### Phase 4：清理 starter 装配边界

- 根据新 runtime API 调整 `WebFluxAutoConfiguration`。
- 保持 module factory 与 standalone factory 的覆盖规则。
- 保持 `@Order(Ordered.HIGHEST_PRECEDENCE)` 语义不变。

### Phase 5：债务扫描与兼容验证

- 删除仅为过渡存在的 helper 或兼容壳。
- 扫描 `TODO`、`FIXME`、`legacy`、`@Deprecated`、无用 public 类型。
- 用 REST/OpenAPI/WebFlux/starter 测试矩阵验证。

## 测试与验证

本轮最低验证命令：

```bash
./gradlew :wow-webflux:check
./gradlew :wow-openapi:check
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" --tests "me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfigurationTest"
```

最终合并前使用强制重跑：

```bash
./gradlew --rerun-tasks :wow-webflux:check :wow-openapi:check
./gradlew --rerun-tasks :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" --tests "me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfigurationTest"
git diff --check origin/main..HEAD
```

RESTful API 兼容判断：

- `wow-openapi` snapshot 不发生非预期变化。
- `wow-webflux` route contract tests 通过。
- `wow-spring-boot-starter` 的 WebFlux/OpenAPI auto-config tests 通过。
- 如 OpenAPI snapshot 有变化，必须解释是否为排序/描述变化，不能改变 path/method/accept 的兼容语义。

## 风险

### Handler key 与 metadata 类型错配

风险：抽象 factory support 时可能把 aggregate metadata、command metadata 或 global metadata 搞混。

控制：每种 metadata support 都要有错配测试，错误信息包含 handler key 与 expected metadata type。

### Route 顺序变化

风险：REST 路由匹配顺序可能影响路径变量路由与命令路由优先级。

控制：继续以 `RouteCatalog` 的排序结果为唯一输入顺序，`RouterFunctionBuilder` 不重新排序。

### Starter 覆盖规则变化

风险：module factory 与 standalone factory 的覆盖顺序被改坏，影响用户自定义 handler。

控制：保留并强化现有 standalone override module factory 测试。

### OpenAPI 与 WebFlux 运行时分叉

风险：OpenAPI 展示的 contract 与 WebFlux 实际注册 route 不一致。

控制：WebFlux runtime 必须从同一个 `RouterSpecs.toRouteCatalog()` 输入构建，不维护第二套路由定义。

## 预期结果

完成后，`wow-webflux` 应形成更清楚的三层结构：

1. contract materialization：从 `HttpRouteContract` 到 Spring route。
2. handler factory support：从 contract metadata 到具体 handler 构造参数。
3. domain-specific handler：command/event/state/snapshot/query/global/wait 的业务处理。

这样 `wow-openapi` 负责定义 REST 合约，`wow-schema` 负责 schema 生成，`wow-webflux` 负责运行时执行，`wow-spring-boot-starter` 负责装配。四者边界会比当前更稳定，后续重构 starter 或 query 时不会再把 REST contract 和运行时细节搅在一起。

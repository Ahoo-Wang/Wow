# wow-openapi 重构设计

日期：2026-10-09。状态：已确认（2026-10-09，§7），已实施（P0–P4，见 §9）。破坏性部分随 9.6.0 发布。

依据：对 `wow-openapi`（`main` 代码 6.4k 行、测试 4.5k 行、ABI 114 个公开类）及其调用方 `wow-webflux`、`wow-apiclient`、`wow-spring-boot-starter`、`wow-view-store-starter` 做的只读审查，基于 `main` `797cbfd67`。文中路径都相对 `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/`。

## 1. 第一性原理：这个模块是什么

`wow-openapi` 做两件事，而且只做这两件：

1. **路由目录**：服务暴露哪些 HTTP 路由——方法、路径、参数、请求体、响应、由哪个 handler 处理。`wow-webflux` 按它分派请求，view-store 按它关闭路由。它是 REST 协议的唯一事实来源。
2. **文档渲染**：把路由目录渲染成 OpenAPI 3.1 文档，生成并登记 JSON Schema 组件。

另有一套被多方引用的**线格式词汇**：请求头名、路径后缀、批处理路径变量、`BatchResult`、BI 脚本请求/响应。它们是 REST 协议本身，`wow-apiclient` 只需要它们。

必须守住的不变量：

1. **REST 与 OpenAPI 输出不变。** 路由（路径、方法、route id、handler key）和生成的 OpenAPI 文档是契约（v9 冻结，`@ahoo-wang/wow-generator` 按 route id 查找操作）。重构必须让 `example-domain-openapi.snapshot.json` 与 `example-domain-contract.snapshot.json` 保持不变；只有明确的缺陷修复可以改变输出。
2. **路由目录不生成 Schema。** 只提供路由、不提供文档的服务不承担 Schema 生成。
3. **文档渲染可被并发、重复调用。** 结果与调用次数、调用线程无关。

## 2. 诊断

| # | 根因 | 证据 | 后果 |
|---|---|---|---|
| D1 | **契约不是纯数据** | `RouteContributor` 的两个方法都接收 `OpenAPIComponentContext`（`catalog/RouteContributor.kt`）；贡献者一边构造契约一边向上下文注册组件；契约里嵌着上下文生成的 swagger `Schema`（`HttpSchema.Raw(schema(...))`，`contributor/QueryContractComponentSupport.kt`） | 为了"路由时不生成 Schema"，造了空实现的 `RoutingComponentContext`，`RouterSpecs` 维护两套目录并用 `documentedRouteCatalogLazy.isInitialized()` 选择（`RouterSpecs.kt:60`），`finish()` 调两次（`:133`、`:135`） |
| D2 | **路由阶段仍会生成 Schema** | 聚合查询请求体在交给上下文之前就调用 `aggregatedFieldsSchema`，它阻塞等待 `InferredQuerySchemaSource` → `JsonQueryModelSource`，即每个聚合一次完整的 JSON Schema 生成（`QueryComponent.kt:195`、`:56`） | 不变量 2 被破坏，`open-api.md` 的说法不成立。P0（#4057）已修复 |
| D3 | **渲染修改共享状态** | `mergeOpenAPIFromCatalog` 在同一个 `DefaultOpenAPIComponentContext` 上渲染与 `finish()`，没有同步 | **已复现**：4 线程同时渲染同一 `RouterSpecs`，40 次中 31 次 `ConcurrentModificationException`，另 4 次输出与基准不同；单线程重复渲染结果一致。Springdoc 每个文档资源有锁，默认单文档不触发；多分组文档或其他调用方会触发 |
| D4 | **组件 key 写两遍** | 每个 `xxxRef()` 先调用注册函数、丢弃返回值，再手写同一个 key（如 `contributor/CommonContractComponentSupport.kt:37`）；`"wow.command.SimpleWaitSignal"`、`"wow.configuration.WowMetadata"` 依赖 Schema 命名策略；`"wow.$SPACE_ID"` 与 `Wow.WOW_PREFIX` 并存；`COMPONENTS_*_REF` 在 `render/OpenApiRenderer.kt:221` 重复定义 | key 靠人保持一致，命名策略一变引用就断 |
| D5 | **线格式词汇放在文档模块** | `CommandComponent.Header`、`CommonComponent.Header`、`BatchComponent.PathVariable`、`RouteSuffixes`、`BuiltInHttpRoutePaths`、`BatchResult`、`contract/bi/*` 被 webflux、apiclient、starter 引用；`wow-apiclient` 只用到其中的常量 | `wow-apiclient` 为几组常量传递依赖 swagger-core、wow-schema（victools）、wow-query、spring-web |
| D6 | **聚合路由样板三份** | `snapshotRoute`、`eventRoute`、`stateRoute` 结构相同，每个路由函数重复传三个上下文参数；四个聚合贡献者 1478 行 | 加一个查询路由要改多处，阅读成本高 |
| D7 | **死代码与无效 SPI** | `QueryComponent.Response.pagedListEventStreamResponse`、`loadEventStreamResponse`、`ApiResponseBuilder.listContent` 无调用方；`HttpSchema.Long/Boolean/Formatted/Unspecified` 从不产生；`pathSummary/pathDescription` 从不单独赋值；`RouterSpecs.mergeOpenAPI` 是无人调用的别名；`RouteContributor.order/id` 与 `RouteContributors.sort` 不影响输出（目录按自己的比较器重新排序），`category` 只用来区分全局与聚合 | 公开面大于实际契约 |
| D8 | **组件上下文职责混杂** | `OpenAPIComponentContext.componentSchema` 的默认实现把只读 Map 强转为可变 Map 再捕获 `UnsupportedOperationException`（`context/OpenAPIComponentContext.kt:68`）；组件定义在旧包 `aggregate.command`/`aggregate.event`，契约辅助在 `contributor.*` | 同一关注点两处安放 |

做得好的部分不动：`RouteCatalog` 的校验与分派顺序（`RouteTemplate`）、`AggregateRouteMetadata`/`CommandRouteMetadata` 的解析、`RouteIdSpec` 的 route id 规则、9.5.0 刚整理过的 `schema/` 包。

## 3. 目标架构

```
wow-rest-contract   me.ahoo.wow.rest          （新模块，只依赖 wow-api）
├── CommandHeaders / WowHeaders                请求、响应头名
├── RoutePaths / RouteSuffixes / RouteVariables 内置路径、后缀、路径变量名
├── BatchResult
└── bi/  BiScriptRequest / BiScriptResponse / … / BiScriptHeaders

wow-openapi         me.ahoo.wow.openapi
├── contract/   HttpRouteContract、HttpSchema、HttpComponent（纯数据，不接触上下文）
├── catalog/    RouteContributor、RouteCatalog、RouteTemplate
├── contributor/ 内置贡献者；aggregate/ 用声明式路由表
├── component/  内置组件定义（internal）：错误响应、命令头参数、查询请求体……
├── metadata/   AggregateRouteMetadata、CommandRouteMetadata（不变）
├── render/     OpenApiRenderer（internal）：解析契约里的类型与组件引用
├── context/    OpenAPIComponentContext（组件登记 + Schema 生成）
└── schema/     不变
```

依赖方向：`wow-apiclient → wow-rest-contract`，`wow-webflux → wow-openapi → wow-rest-contract`。

### 3.1 纯契约（解决 D1、D4）

- `RouteContributor` 只描述路由：

  ```kotlin
  interface RouteContributor {
      fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> = emptyList()
      fun contributeAggregate(
          currentContext: NamedBoundedContext,
          aggregateRouteMetadata: AggregateRouteMetadata<*>
      ): List<HttpRouteContract> = emptyList()
  }
  ```

  去掉 `id`、`order`、`category` 与 `RouteContributors`（D7）。`RouterSpecs` 对每个贡献者调用 `contributeGlobal`，再对每个启用路由的聚合调用 `contributeAggregate`。
- 组件以有类型的引用出现在契约里：`HttpComponent<T>(key, create: OpenAPIComponentContext.() -> T)`，按 key 判等。`HttpParameter`、`HttpHeader`、`HttpRequestBody`、`HttpResponse` 的 `componentRef: String` 改为 `component: HttpComponent<…>?`。key 只在组件定义处出现一次。
- `HttpSchema` 保留 `String`、`Integer`、`Object`、`Array`、`TypeRef`、`Raw`：
  - `TypeRef(type, typeArguments: List<TypeRef>)` 支持嵌套泛型，`PagedList<MaterializedSnapshot<S>>` 这类响应不再在构造契约时调用 `resolveType`/`schema`；
  - `Raw` 只用于与上下文无关的静态 Schema（聚合查询的行、SSE 包装）；
  - 删除 `ComponentRef(key)`：`SimpleWaitSignal`、`WowMetadata` 改用 `TypeRef`，引用由命名策略产生；删除从不产生的四个分支（D7）。
- 删除 `RoutingComponentContext` 与两套目录：路由目录只构建一次，构建过程不接触 Schema。

### 3.2 渲染（解决 D3）

`RouterSpecs.mergeOpenAPI(openAPI)` 是唯一的合并入口（`mergeOpenAPIFromCatalog` 并入）。渲染器遍历目录：遇到组件引用时以其 key 登记一次（同 key 的不同组件报错），遇到 `TypeRef` 时生成 Schema，最后 `finish()` 并把组件合入文档。

文档只渲染一次：启动时由 `buildDocumentation()`（或第一次 `mergeOpenAPI`）在实例锁内完成，结果缓存为模板。之后每次 `mergeOpenAPI` 只合并模板的副本：path item、operation 及其参数、请求体、响应、请求头与组件（parameters/headers/requestBodies/responses）都复制，只有 Schema 实例共享。合并不生成 Schema、不阻塞、不增长内存，可并发调用；调用方的定制器修改 operation 与组件不会互相影响，修改 Schema 前需自行复制。

### 3.3 声明式聚合路由（解决 D6）

每个聚合一个作用域对象 `AggregateRouteScope(currentContext, aggregateRouteMetadata)`，统一产生路径、route id、参数、tags 与 summary。快照与事件流的查询路由写成表：每行给出 handler key、资源名、操作名、summary、路径后缀、`Accept`、请求体与响应，再按 tenant/owner 变体展开。路由的产生顺序、id 与路径不变，由契约快照证明。

### 3.4 线格式词汇（解决 D5）

新模块 `wow-rest-contract`（包 `me.ahoo.wow.rest`，只依赖 `wow-api`）收纳 §3 所列常量与 DTO。路径变量名（`id`、`tenantId`、`ownerId`、`version`、`createTime`、`headVersion`……）在 `RouteVariables` 中定义，`RouteSuffixes` 不再引用 `wow-core` 的 `MessageRecords`。

`wow-apiclient` 改为依赖 `wow-rest-contract`，不再依赖 `wow-openapi`。`wow-openapi` 以 `api` 依赖 `wow-rest-contract`，其调用方不需要改依赖声明。新模块按发布模块的要求提交首个 ABI dump，并加入 `wow-bom`。

### 3.5 公开面（解决 D7、D8）

- 删除 D7 所列死代码。
- `CommandComponent`、`CommonComponent`、`BatchComponent`、`QueryComponent`、`EventComponent` 中生成组件的扩展函数移入 `component/`，设为 `internal`；`PathBuilder`、`Https`、`Tags`、`OpenAPIExtensions`、`RouteCatalogBuilder`、`OpenApiRenderer` 设为 `internal`。
- `OpenAPIComponentContext.componentSchema` 改为抽象成员，删除强转。
- 保持公开：`ApiResponseBuilder`、`RequestBodyBuilder`（`HttpComponent.response`/`requestBody` 工厂函数的接收者，即组件 DSL）、`RouterSpecs`、`RouteContributor`、`RouteCatalog`、`contract/*`、`metadata/*`、`BuiltInHttpRouteHandlerKeys`、`OpenAPIComponentContext`、`schema/` 的公开类型、`BoundedContextSchemaNameConverter`（`ServiceLoader` 加载）。

## 4. 兼容性

- **REST、OpenAPI 输出**：不变（不变量 1）。
- **Kotlin ABI**：破坏，随 9.6.0 发布，PR 标记 `!`，不留二进制垫片：`RouteContributor` 签名与属性、`HttpRouteContract` 及其部件的字段、`HttpSchema` 分支、`RouterSpecs.mergeOpenAPIFromCatalog`、§3.5 转为 internal 的类型、线格式词汇的新位置。
- **应用源码**：应用可能直接引用的请求头常量保留一个弃用周期：`CommandComponent.Header`、`CommonComponent.Header` 的常量改为指向 `CommandHeaders`、`WowHeaders` 的 `@Deprecated` 别名，`me.ahoo.wow.openapi.BatchResult` 改为弃用的 `typealias`，登记在 `docs/compat-debt.md`，v10 删除。其余线格式词汇（路径、后缀、BI DTO）只被框架模块使用，直接迁移。
- **自定义 `RouteContributor`**：去掉 `componentContext` 参数与三个属性；需要组件的贡献者在契约中使用 `HttpComponent`。迁移指南给出前后对照。

## 5. 实施阶段

每个阶段一个 PR，两个快照不变，`:wow-openapi:check` 及直接调用方的测试在本地通过。

| 阶段 | 内容 | 版本 |
|---|---|---|
| P0 | 路由阶段不再推断查询字段（D2，#4057） | 补丁安全，随 9.6.0 |
| P1 | 纯契约、`HttpComponent`、渲染器负责组件、删除 `RoutingComponentContext` 与双目录、`mergeOpenAPI` 串行（D1、D3、D4） | 9.6.0 |
| P2 | `AggregateRouteScope` 与声明式路由表、`component/` 包、删除死代码（D6、D7、D8） | 9.6.0 |
| P3 | 新模块 `wow-rest-contract`，apiclient 改依赖，弃用别名与 compat-debt 条目（D5） | 9.6.0 |
| P4 | 公开面收缩（§3.5），文档与迁移指南 | 9.6.0 |

P1 → P2 → P4 依次进行；P3 与 P1 并行（只在 `CommandComponent.Header` 等常量定义处相交）。发布前由两位新的审查者整体审查（正确性/回归；架构/API/兼容/文档）。

## 6. 不做的事

- 不改路由、route id、handler key、OpenAPI 输出。
- 不改 `schema/`（9.5.0 已重构）与 `RouteCatalog` 的分派顺序算法。
- 不把 `CommandRouteMetadata.decode`（运行时把路径、请求头变量写入命令体）移到 `wow-webflux`：它与路由元数据同源，移动只增加跨模块耦合。
- 不在本次处理 `x-wow-query-fields` 只使用推断来源、不读取应用配置的查询 Schema 来源的问题；它属于查询能力的语义，需另行讨论。

## 7. 决定

2026-10-09 用户确认（「按你推荐」）：

1. 接受 9.6.0 的 Kotlin ABI 破坏。
2. 线格式词汇拆为独立模块（方案 B），使 `wow-apiclient` 不再依赖 `wow-openapi`。
3. 先提交本设计文档，再分阶段实施；P0 单独提交。

## 8. 9.6.0 发布说明条目

- 修复：路由目录构建不再推断查询字段（P0）；文档渲染可并发调用（P1）。
- Breaking：`RouteContributor` 签名；路由契约模型；`RouterSpecs.mergeOpenAPIFromCatalog` → `mergeOpenAPI`；`wow-openapi` 公开面收缩；线格式词汇迁入 `wow-rest-contract`。
- 弃用：`CommandComponent.Header`、`CommonComponent.Header`、`me.ahoo.wow.openapi.BatchResult`（v10 删除）。
- 依赖：`wow-apiclient` 改为依赖 `wow-rest-contract` 与 `wow-query`，不再带来 `wow-openapi`、`wow-schema`、swagger-core/annotations、victools jsonschema 模块（编译）与 `wow-models`（运行时）；用到它们的客户端（包括依赖 `wow-openapi` 注册的 `BoundedContextSchemaNameConverter` 的 Springdoc BFF）需自行声明。
- 增量：`GET /wow/metadata` 的 `wow.openapi` 上下文多列出作用域 `me.ahoo.wow.rest`。
- 行为变化：渲染错误（如同类同 key 的不同组件）在存在 Springdoc 时让启动失败，而不是第一次 `/v3/api-docs` 请求失败；文档尚未渲染时在事件循环线程上首次 `mergeOpenAPI` 失败并提示 `buildDocumentation()`；合并结果共享 `Schema` 实例，原始 `/v3/api-docs` 中 `components` 的 key 顺序变化（JSON 等价）。
- REST、OpenAPI、线格式不变，9.5.x 与 9.6.0 节点可共处一个集群。

## 9. 实施记录

- P0（#4057）：路由目录构建不再推断查询字段。
- P1（#4060）：`RouteContributor` 去掉 `componentContext`、`id`、`order`、`category`；契约部件以 `component: HttpComponent<…>?` 引用组件，内置组件在 `component/` 包（internal）中各定义一次，`CommonComponent` 等对象只留常量；`HttpSchema.TypeRef(type, typeArguments)` 嵌套，删除 `ComponentRef`、`Long`、`Boolean`、`Formatted`、`Unspecified` 与 `pathSummary`/`pathDescription`；删除 `RoutingComponentContext`、双目录、`RouteContributors`、`RouteCategory`、`mergeOpenAPIFromCatalog`；`HttpComponent` 只能由工厂函数（`parameter`/`header`/`requestBody`/`response`）创建，渲染器以 key 登记，同 key 不同组件报错；文档只渲染一次（`buildDocumentation()` 或第一次 `mergeOpenAPI`，加锁），之后每次 `mergeOpenAPI` 合并其副本（path item、operation 及其部件与组件复制，只共享 Schema 实例），不再生成 Schema、不阻塞请求线程、不增长内存。两个快照不变；4 线程 × 5 轮并发渲染与单线程结果一致。
- P2（#4062）：`contributor/aggregate/AggregateRouteScope`（internal）统一产生聚合路由的路径、route id、聚合参数、tags 与 tenant/owner summary；快照、事件流、状态路由写成 `AggregateRoute` 行，查询路由表按 `tenantOwnerVariants` 展开，route id 与 summary 的作用域写法由 `ScopeNaming` 按路由族固定为已发布的写法（快照/事件 `TENANT_OWNER`，状态 `TENANT_ID_ONLY`）；命令路由复用同一作用域。删除 `AggregateRouteContractSupport`。四个聚合贡献者加支持文件由 1499 行降到 1050 行（快照、事件、状态 1091 → 596）。路径变量名改用 `RouteVariables`（wow-openapi、wow-webflux 的 main 代码），`MessageRecords` 只用于消息体字段。删除无调用方的 `ApiResponseBuilder.listContent`；`ApiResponseBuilder`、`RequestBodyBuilder` 的其余方法是组件 DSL，保留。`QueryComponent`、`EventComponent`、`CommonComponent.Response` 的常量移入 `component/` 的 internal 对象（`CommonComponent.Header`、`CommandComponent.Header` 弃用别名保留）。两个快照不变；逐条比对重构前后各贡献者产出的契约（含 handler key、summary、参数顺序），完全一致，只有聚合内命令路由的相对顺序可能不同：它在 `main` 上本就不确定（`registeredCommands` 按 order 排序，同序的保持以 `Class` 为键的 `HashMap` 的顺序），本次未改变它，`RouteCatalog` 会重新排序，没有任何东西依赖它。
- P3（#4059）：新模块 `wow-rest-contract`（`me.ahoo.wow.rest`，只依赖 `wow-api`）收纳 `CommandHeaders`、`WowHeaders`、`RoutePaths`、`RouteSuffixes`、`RouteVariables`、`BatchResult` 与 `bi/` DTO。`CommandComponent.Header`、`CommonComponent.Header` 的常量与 `me.ahoo.wow.openapi.BatchResult` 保留为弃用别名（compat-debt 条目「Wow 9.5 REST Header Names And `BatchResult` In `wow-openapi`」），其余声明直接迁移。Schema 名由新模块的 `META-INF/wow-metadata.json` 固定：把 `me.ahoo.wow.rest` 并入 `wow.openapi` 上下文，组件名仍是 `wow.openapi.BatchResult`、`wow.openapi.BiScriptRequest` 等，OpenAPI 快照不变。`wow-apiclient` 改为依赖 `wow-rest-contract`，并以 api 依赖 `wow-query`（此前经 `wow-openapi` 传递得到，查询 DSL 仍对调用方可见），不再传递引入 `wow-openapi`。`GET /wow/metadata` 的 `wow.openapi` 上下文因此多列出作用域 `me.ahoo.wow.rest`（增量）。
- P4（#4063）：公开面收缩。逐个核对 ABI dump 中的公开声明在仓库内（各模块 main 与 test、文档、`wow-benchmarks`、`example`、`compensation`、`view-store`）的使用者：只在 `wow-openapi` 内使用的改为 `internal`——`Https`、`PathBuilder`、`RouteIdSpec`、`Tags`、`OpenAPIExtensions`、`RouteCatalogBuilder`、`OpenApiRenderer`、`DefaultOpenAPIComponentContext`、两个元数据解析器、`BoundedContextSchemaNameConverter` 的 companion，以及状态、命令门面、命令等待、全局 ID、元数据五个内置贡献者；被 Starter 或其他模块测试使用、但不面向应用的标 `@InternalWowApi`——命令、快照、事件、BI 脚本四个贡献者；`DefaultRouteContributors` 保持公开（不经 Spring 构建 `RouterSpecs` 的应用用 `DefaultRouteContributors.all()` 加自己的贡献者）。其他模块测试中的 `Https` 常量改为字符串字面量，文档示例同样改写。ABI dump 的公开类由 78 个降到 53 个，只有删除行。`spring-web` 依赖仍被 `CommandRouteMetadataParser`（`UriTemplate`）使用，保留。两个快照不变。
- 发布前审查修复（`fix(openapi)!: pre-release review fixes for the wow-openapi refactor`）：两位审查者的结论。`HttpComponentContext` 改为独立接口，只暴露 `inline`、`schema`、`arraySchema`、`resolveType`、`componentSchema`、`ref`（不能登记组件或 `finish()`），渲染器提供适配；`RouterSpecs.componentContext` 改为 private；`HttpComponent` 按 kind 与 key 判等。同类同 key 的组件改为在 `finish()` 之后比较：此前比较发生在 Schema 生成完成之前（占位 `$ref`），只在生成的 Schema 上不同的两个组件不会被拒绝；重复的组件构建时不再覆盖已登记的组件。契约快照加入 `handlerKey`（只有新增字段，OpenAPI 快照不变）。文件改名 `QueryContracts.kt`、`BuiltInHttpRouteHandlerKeys.kt`；迁移指南补全 #4059 的迁移表、`wow-apiclient` 依赖变化与 `BatchComponent` 的删除；`docs/compat-debt.md` 「Held Until v10」记录状态路由 `ScopeNaming.TENANT_ID_ONLY`。

后续（不在 9.6.0）：

- `ViewStoreOpenApi`（`wow-view-store-starter`，9.6.1 完成）：没有改为 `RouteContributor`。路由目录就是分发表（`RouterFunctionBuilder` 为每条契约找 `HttpRouteHandlerFunctionFactory`，Starter 也把 Spring 中的贡献者并入目录），视图存储的路由由它自己的 `RouterFunction`（`@Order(0)`，先于 Wow 的路由）分发，并入目录会改变分发；渲染器还会给 operation 写空的 `description`，并按 `DocumentTemplate` 的方式合并组件与标签，文档输出会变。改为与 `RouterSpecs` 相同的“启动时生成一次”：Schema 在 Customizer Bean 创建时生成（`render()`，加锁的 `lazy`），之后每次 customize 只围绕这些 Schema 新建 path item 与 operation，不再生成 Schema；未生成就在非阻塞线程上调用时报错。视图存储 Starter 与独立服务的 `/v3/api-docs` 前后逐字节相同（除随机端口的 `servers`）。
- ~~为自定义贡献者公开内置的通用组件（错误响应、错误码头等），使其不必复制定义。~~ 已完成（9.6.1，`feat(openapi): public built-in components and symmetric component builders`）：`contract/WowComponents` 公开内置路由使用的同一批实例——错误响应 `badRequestResponse`、`notFoundResponse`、`requestTimeoutResponse`、`tooManyRequestsResponse`、`unsupportedMediaTypeResponse`，错误码头 `errorCodeHeader` 与其组件 `errorCodeHeaderComponent`，请求头参数 `spaceIdHeaderParameter`，路径参数 `idPathParameter`、`tenantIdPathParameter`、`ownerIdPathParameter`、`versionPathParameter`；引用它们不会触发同 key 检查。`CommonComponents` 仍为 internal。
- ~~`ApiResponseBuilder` 与 `RequestBodyBuilder` 的 DSL 对称~~ 已完成（同一 PR）：新增 `ApiResponseBuilder.extension(name, value)` 与 `RequestBodyBuilder.required(Boolean)`；两者都有 `description`、`content`、`extension`，`header` 只属于响应（Swagger 的 `RequestBody` 没有头）。媒体类型参数名（`mediaTypeName` 与 `name`）保持不变：改名会破坏以命名实参调用的代码。

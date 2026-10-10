---
title: OpenAPI
description: 基于生成元数据发布 Wow 路由合同，并明确区分编译期元数据、运行时 WebFlux 路由、Schema 与客户端。
---

# OpenAPI

`wow-openapi` 为 Wow 路由合同构建 OpenAPI 3.1 operations 与 components。完整链路是：

```text
Wow 注解
  -> KSP: META-INF/wow-metadata.json
  -> MetadataSearcher: 运行时聚合元数据
  -> RouterSpecs / RouteCatalog
       -> WebFlux RouterFunction
       -> OpenAPI paths 与 components
  -> API 客户端或外部客户端生成器
```

共享 `RouteCatalog` 是关键边界：运行时 WebFlux Handler 与 OpenAPI renderer 消费同一套路由合同。KSP 不会生成可运行 HTTP 服务；存在 OpenAPI 也不能证明后端、查询能力、认证策略或客户端部署已经可用。

生成的路径模板可能重叠而不相等：路径以 `count` 结尾的命令会生成 `POST …/{resource}/{id}/count`，它同样匹配查询路径 `POST …/{resource}/snapshot/count`。按 catalog 顺序命令排在前面，查询因此永远无法到达。WebFlux 路由改为按 catalog 的分发顺序（`RouteCatalog.dispatchRoutes`）尝试：若一个模板匹配的路径都能被另一个模板匹配，较窄的模板先尝试；其余情况保持 catalog 顺序，因此原本能到达某条路由的请求不会改投他处。同一方法下仅变量名不同的两个模板匹配完全相同的路径，catalog 会在启动时拒绝并指出这两条路由。

## 安装

`wow-openapi` 提供路由合同与 Schema components：

::: code-group

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-openapi")
```

```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-openapi'
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-openapi</artifactId>
    <version>${wow.version}</version>
</dependency>
```

:::

Spring Boot 应用使用 Starter 和 WebFlux runtime。仅当服务需要发布 `/v3/api-docs` 或 Swagger UI 时再添加 Springdoc：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter")
implementation("me.ahoo.wow:wow-webflux")
implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
```

`OpenAPIAutoConfiguration` 创建 `RouterSpecs`；`WebFluxAutoConfiguration` 把目录物化为 `RouterFunction`；`WowOpenApiCustomizer` 把同一目录合并到 Springdoc。`wow.openapi.enabled=false` 禁用 Springdoc 定制，不会关闭 WebFlux 路由目录本身。构建路由目录时不生成任何 JSON Schema；只有提供文档时（存在 Springdoc 且 `wow.openapi.enabled` 不为 `false`，在启动时）才生成 schema 与组件，不提供文档的服务不承担 schema 生成开销。

`RouterSpecs` 只把目录渲染为文档一次：启动时由 `buildDocumentation()`（或第一次 `RouterSpecs.mergeOpenAPI(openAPI)`）完成，这次渲染生成 Schema，可能阻塞。之后每次 `mergeOpenAPI` 合并该文档的副本：不生成 Schema、不阻塞，可以并发调用（例如多个 Springdoc 分组），并得到自己的 path item、operation 与组件，定制器修改它们不会影响其他文档。只有 `Schema` 实例是共享的：修改 Schema 前请先复制。文档尚未渲染时在事件循环线程上调用 `mergeOpenAPI` 会失败，并提示先调用 `buildDocumentation()`。

自定义路由通过 `RouteContributor` Bean 添加。贡献者只返回路由合同，合同是纯数据：不生成 Schema，也不登记组件。请求体或响应类型用 `HttpSchema.TypeRef` 引用（嵌套泛型用 `typeArguments`），可复用的参数、请求头、请求体或响应用 `HttpComponent`（`HttpComponent.parameter`、`header`、`requestBody`、`response`）引用：key 只写一次，渲染器构建并登记一次。构建函数的 `context`（`HttpComponentContext`）负责生成 Schema（`schema`、`arraySchema`、`resolveType`、`componentSchema`），并用 `ref` 引用另一个组件；它不能自行登记组件。同一类组件的 key 必须唯一：同类同 key 的两个组件实例都会被构建，Schema 生成完成后，除非两者构建出的组件（包括其引用的 Schema）相等，否则渲染失败。路由的 `handlerKey` 指向处理它的 `HttpRouteHandlerFunctionFactory` Bean（来自 `wow-webflux`）；缺少该 Bean 时路由器在启动时失败：

```kotlin
val reportResponse = HttpComponent.response("example.ReportResponse") { context ->
    description("Report")
    header(WowHeaders.ERROR_CODE, context.ref(WowComponents.errorCodeHeaderComponent))
    content(schema = context.schema(Report::class.java))
}

@Bean
fun reportRouteContributor(): RouteContributor = object : RouteContributor {
    override fun contributeGlobal(currentContext: NamedBoundedContext) = listOf(
        HttpRouteContract(
            routeId = "example.report.get",
            method = "GET",
            path = "/report",
            handlerKey = "example.report",
            responses = listOf(
                HttpResponse("200", component = reportResponse),
                WowComponents.badRequestResponse,
                WowComponents.notFoundResponse,
            ),
        )
    )
}

@Bean
fun reportHandlerFunctionFactory(reportService: ReportService): HttpRouteHandlerFunctionFactory =
    object : NoMetadataRouteHandlerFunctionFactorySupport("example.report") {
        override fun create(contract: HttpRouteContract) = HandlerFunction { _ ->
            ServerResponse.ok().body(reportService.report(), Report::class.java)
        }
    }
```

内置路由共用的组件通过 `WowComponents`（`me.ahoo.wow.openapi.contract`）公开：错误响应 `badRequestResponse`、`notFoundResponse`、`requestTimeoutResponse`、`tooManyRequestsResponse` 与 `unsupportedMediaTypeResponse`（带 `DefaultErrorInfo` 响应体与 `Wow-Error-Code` 响应头的 `HttpResponse`），响应头 `errorCodeHeader` 及其组件 `errorCodeHeaderComponent`（在自己的响应中用 `context.ref` 引用），请求头参数 `spaceIdHeaderParameter`，以及路径参数 `idPathParameter`、`tenantIdPathParameter`、`ownerIdPathParameter` 与 `versionPathParameter`。它们就是内置路由使用的实例，自定义路由引用它们即与内置路由共用 `wow.BadRequest`、`wow.Wow-Error-Code` 等组件，无需在同一 key 下重新定义。构建器中，`ApiResponseBuilder` 可设置 `description`、`header`、`content` 与 `extension`，`RequestBodyBuilder` 可设置 `description`、`required`、`content` 与 `extension`。

合同的方法、状态码与媒体类型都是普通字符串（`"GET"`、`"200"`、`"application/json"`）。`wow-openapi` 的公开 API 是 `RouterSpecs`、`RouteContributor`、`RouteCatalog`、`me.ahoo.wow.openapi.contract` 中的合同类型（含 `BuiltInHttpRouteHandlerKeys` 与 `HttpComponent`）、路由元数据（`aggregateRouteMetadata()`、`commandRouteMetadata()`）、`DefaultRouteContributors`（内置路由，自行构建 `RouterSpecs` 时与自己的贡献者组合）、`OpenAPIComponentContext`、内置共用组件 `WowComponents`、组件构建器 `ApiResponseBuilder` 与 `RequestBodyBuilder`、`OpenAPISchemaBuilder` 与 `BoundedContextSchemaNameConverter`。渲染器、目录构建器与内置路由背后的辅助类型是 internal；内置贡献者对象为 internal 或标为 `@InternalWowApi`，供 Spring Boot Starter 使用，不属于 API。

包含 Wow 注解的模块仍需应用 KSP 与 `wow-compiler`，并确保生成的 `META-INF/wow-metadata.json` 位于服务运行时 classpath。不要手写或提交生成资源。

## Swagger-UI

Swagger UI 是 Springdoc 应用特性，不属于路由合同本身。匹配的 Starter 存在且已启用时，Springdoc 页面通常位于 `/swagger-ui.html`，JSON 文档位于 `/v3/api-docs`。

![Swagger-UI](/images/compensation/open-api.png)

准确路径、方法、参数、媒体类型、operation ID 与 component 引用应以 JSON 文档为准。截图不是合同证据。

## 聚合资源归属

聚合元数据会合并 `@AggregateRoute`、聚合策略（`@Spaced`、`@AggregateOwner`、静态租户）、命令级 `@CommandRoute`、tenant 元数据以及生成的命令/事件类型。资源归属影响路径形状，但不代表调用方授权。

路由目录还会根据 `@AggregateRoute(enabled = false)` 决定是否发布该聚合。这不会移除 HTTP 之外的命令处理或存储行为。

## RESTful URL PATH Spec

聚合路由的通用形状是：

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

默认路由从 resource name 开始。Wow 不会在本地路径前自动添加限界上下文 alias。客户端代码不应根据命名约定拼接路径，应检查生成 OpenAPI。

路由声明了 `{tenantId}`、`{ownerId}` 或 `{id}` 时，取值只来自路径段：不会改读 `Command-Tenant-Id`、`Command-Owner-Id`、`Command-Aggregate-Id` 请求头；路径段解码后为空白（如 `%20`）时返回 `400`，错误码 `IllegalArgument`，不会退回请求头，也不会当作默认租户。未声明该变量的路由仍读取请求头（静态租户始终生效）。

### 请求身份

每条路由在构建路由器时一次性决定每个身份事实从哪里取：看它的契约声明了哪些路径变量，以及聚合的静态租户、拥有者策略和 space。命令、查询、单点读取、快照重建和事件补偿读的是同一个绑定，所有路由遵循同一套规则：

| 事实 | 来源（取第一个适用的） |
| --- | --- |
| 租户 | 静态租户（忽略租户请求头） → `{tenantId}` → `Command-Tenant-Id` |
| 拥有者 | `{ownerId}` → 拥有者即聚合 ID（`OwnerPolicy.AGGREGATE_ID`）时的 `{id}` → `Command-Owner-Id` |
| 聚合 ID | 拥有者即聚合 ID：`{ownerId}` → `{id}` → `Command-Owner-Id` → `Command-Aggregate-Id`；否则 `{id}` → `Command-Aggregate-Id` |
| Space | 仅 spaced 聚合：`Wow-Space-Id` → 请求头别名（CoSec 的 `CoSec-Space-Id`） |
| 请求 ID | `Command-Request-Id` → 请求头别名（CoSec 的 `CoSec-Request-Id`） |
| 操作人 | 已认证的 Principal |

空白请求头视为没有。命令体的 `@TenantId` / `@OwnerId` / `@AggregateId` 仍然优先，与任何 `CommandGateway` 调用方一样。

自 9.3.0 起，与路由已确定的租户或拥有者相矛盾的请求返回 `400`，错误码 `IllegalArgument`：`Command-Tenant-Id` 与 `{tenantId}` 路径段不同；`Command-Owner-Id` 与 `{ownerId}` 路径段（或拥有者即聚合 ID 时的 `{id}`）不同；命令体的 `@TenantId` 与静态租户或 `{tenantId}` 路径段不同，或 `@OwnerId` 与拥有者路径段不同。此前请求体会悄悄胜出，请求头被忽略。发给带静态租户聚合的租户请求头仍被忽略。值相同或不给值（不带该请求头或请求体属性）都可以。只有在拥有者即聚合 ID、拥有者取自 `{id}` 的聚合上，请求体中空白的 `@OwnerId` 才算不给值（命令的拥有者即其聚合 ID，与 9.2 相同）；对 `{ownerId}` 路径段，或空白 `@TenantId` 对静态租户或 `{tenantId}`，空白的请求体值属于矛盾。路由没有确定的事实，仍是请求体优先于请求头。 受影响的情况：客户端或网关在每个请求上都发送同一个全局 `Command-Owner-Id` 或 `Command-Tenant-Id` 请求头，它与其他拥有者或租户路由上的 `{ownerId}` / `{tenantId}` 矛盾（路径已给出该事实时去掉该请求头）；客户端把非 ASCII 的 ID 在路径和请求头或请求体中都做了百分号编码：服务端会解码路径段，但不会解码请求头或请求体的值，两者因此不同（请求头或请求体发送未编码的值，或者不发送）。

拥有者即聚合 ID 的聚合，路由只写了 `{id}`、没写 `{ownerId}` 时，命令的聚合和拥有者现在都取自路径；9.3.0 之前 `Command-Owner-Id` 请求头会把二者都替换掉。这类路由上的读取（例如加载事件流）不按这个推导出的拥有者过滤：ID 已经确定了聚合，而进程内创建的聚合可能存的是空白拥有者；与 `{id}` 一致的 `Command-Owner-Id` 仍会像以前一样收窄结果。

### 租户资源

动态 tenant 聚合的默认命令/状态路由会添加 `tenant/{tenantId}` 前缀；快照查询贡献者还会保留基础路由并增加 tenant 作用域变体。tenant 路径数据会传入运行时 Handler 与查询重写，但应用仍需把它绑定到已认证 Principal，并显式保护无作用域查询路由。

### 空间资源

启用 spaced 的路由会声明 `Wow-Space-Id` 请求头。Space 不增加路径段。在 spaced 聚合的路由上，该请求头参与命令上下文和查询作用域，但不构成身份认证。未启用 spaced 的聚合的路由既不声明也不读取它。全局命令门面对所有命令都声明该请求头（目标聚合只能从请求得知），但仅当目标聚合启用 spaced 时才采用它。

### 拥有者资源

`@AggregateOwner(OwnerPolicy.ALWAYS)` 会在默认 owned 路由添加 `owner/{ownerId}` 并保留 resource ID；快照与事件流查询同时发布基础和 owner 作用域变体；若聚合还具有动态 tenant，另发布同时收窄到二者的 `tenant/{tenantId}/owner/{ownerId}` 变体：

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders")
@AggregateOwner(OwnerPolicy.ALWAYS)
class Order(private val state: OrderState)
```

`AGGREGATE_ID` 使用 owner ID 作为 aggregate ID，并省略独立 resource-ID 段：

```kotlin
@AggregateRoot
@StaticTenantId
@AggregateRoute(resourceName = "cart")
@AggregateOwner(OwnerPolicy.AGGREGATE_ID)
class Cart(private val state: CartState)
```

查询 Schema 路由是例外：`/{aggregate}/snapshot/schema`、`/{aggregate}/event/schema` 描述查询模型，因此没有 tenant/owner 路径变体；spaced 聚合的公共合同仍可能声明 `Wow-Space-Id`。

## 全局路由

全局合同独立于聚合路由贡献：

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/wow/command/send` | API Client 使用的通用命令入口 |
| `POST` | `/wow/command/wait` | 等待信号接收端点 |
| `GET` | `/wow/metadata` | 已加载 Wow 元数据 |
| `POST` | `/wow/bi/script` | BI 同步脚本生成（仅当 classpath 上有 `wow-bi`） |
| `GET` | `/wow/id/global` | 全局 ID 生成 |

发布上述任一路由都不会自动保护它。应用必须配置认证、授权、限流和网络暴露策略。

### 获取 Wow 元数据

`GET /wow/metadata` 返回由运行时 classpath 编译资源汇总得到的 `WowMetadata`，可用于诊断缺失的注解模块：

```shell
curl 'http://localhost:8080/wow/metadata' \
  -H 'accept: application/json'
```

代表性响应形状：

```json
{
  "contexts": {
    "example-service": {
      "alias": "example",
      "scopes": ["me.ahoo.wow.example.api"],
      "aggregates": {
        "order": {
          "scopes": ["me.ahoo.wow.example.api.order"],
          "type": "me.ahoo.wow.example.domain.order.Order",
          "tenantId": null,
          "id": null,
          "commands": ["me.ahoo.wow.example.api.order.CreateOrder"],
          "events": ["me.ahoo.wow.example.api.order.OrderCreated"]
        }
      }
    }
  }
}
```

该响应能证明运行时已加载元数据，但不能证明每条生成路由都已物化；下一道门禁应检查 `/v3/api-docs` 或路由目录。

### 生成 BI 同步脚本

`POST /wow/bi/script` 为当前本地聚合生成 ClickHouse 同步与展开 SQL。classpath 上有 `wow-bi`（直接添加，或请求 Starter 的 `bi-support` capability）且 `wow.bi.script.enabled` 不为 `false` 时，路由和 OpenAPI operation 存在；否则两者都不存在。启用路由不会自动授权。

端点要求 `application/json` 请求体。`{}` 表示使用服务端选项执行 `DEPLOY`。请求字段包含部署覆盖、`operation` 和 `replayFromEarliestConfirmed`；`previousManifest` 不属于合同。提供 `topology` 时必须提供 `topology.mode`；`STANDALONE` 拒绝 cluster 对象，`CLUSTER` 只接受 cluster `name` 与 `installation` 覆盖。

请求可以降低 `maxExpansionDepth`，但不能超过服务端上限。长度限制同时适用于服务端配置和非 null 覆盖：`database`、`consumerDatabase` 为 128，`timezone` 64，`topicPrefix` 128，`kafkaBootstrapServers` 4096，cluster `name`/`installation` 为 128。超限服务端值使启动失败，超限请求返回 `400`。

| 状态 | 合同 |
|---|---|
| `200 application/sql` | SQL 文本；`Wow-BI-Diagnostic-Count` 给出省略的诊断数量 |
| `200 application/json` | SQL、destructive 标记、诊断与相同计数请求头 |
| `400` | 请求体错误、无效覆盖/拓扑或不满足 RESET 前置条件 |
| `406` | 没有可接受表示；`Wow-Error-Code: NotAcceptable` |
| `415` | 缺少/不支持 content type；`Wow-Error-Code: UnsupportedMediaType` |
| `500` | 未预期生成失败 |
| `502` / `503` / `504` | catalog 不一致 / inspection 不可用 / 超时 |

`RESET` 要求 `replayFromEarliestConfirmed=true`、服务端已配置 `consumerGroupNamespace` 且 inspector 可用。`DEPLOY` 与 `RESET` 不迁移数据库、consumer-group namespace 或 topology。旧 `GET` 方法没有路由。

```shell
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

展开语义参见[商业智能](./bi)，服务端选项参见[BI 脚本配置](./configuration#bi-脚本配置)。

### 生成全局 ID

```shell
curl 'http://localhost:8080/wow/id/global' \
  -H 'accept: text/plain'
```

```text
0U2MNGBQ0001001
```

返回值是文本。除非明确依赖单独的 CosId 合同，否则客户端应把其布局视为不透明。

## 聚合路由规范

路由目录根据聚合元数据贡献 command、state、event、snapshot 和 query 路由。常用查询后缀如下：

| 方法 | 后缀 | 请求 / 响应 |
|---|---|---|
| `GET` | `snapshot/schema` | Snapshot 能力描述（`QueryModelDescriptor`），带 ETag |
| `GET` | `event/schema` | EventStream 能力描述（`QueryModelDescriptor`），带 ETag |
| `POST` | `snapshot/single` | `SingleQuery` -> 物化快照 |
| `POST` | `snapshot/single/state` | `SingleQuery` -> 仅状态 |
| `POST` | `snapshot/list` / `list/state` | `ListQuery` -> 数组或 SSE |
| `POST` | `snapshot/paged` / `paged/state` | `PagedQuery` -> `PagedList` |
| `POST` | `snapshot/cursor` / `cursor/state` | `CursorQuery` -> 完整快照 / state-only `CursorPage` |
| `POST` | `event/cursor` | `CursorQuery` -> EventStream `CursorPage` |
| `POST` | `snapshot/count` | `FilterExpression` -> 精确计数 |
| `POST` | `snapshot/aggregation` | `AggregationQuery` -> 动态行或 SSE |

查询合同分为三个独立层次：

1. 通用 query component schemas 定义规范请求 JSON 形状。
2. 每个聚合专用 query request-body component 引用一个通用 Schema，并公开静态 `x-wow-query-fields`；其 enum 由 system fields 与 `InferredQuerySchemaSource` 推断字段组成。
3. 运行时 `snapshot/schema` 与 `event/schema` 路由发布 HTTP 入口的能力描述，由合并后的 schema 与后端已证明的能力派生。

`x-wow-query-fields` 是 request-body component 上的 OpenAPI 设计时元数据，不会作为 JSON 请求属性嵌入，也不表示后端能力。

`CursorQuery` component 的请求字段是 `filter`、`projection`、`sort`、`size` 与可选 `cursor`，不含 `pagination`；`CursorPage` 只有 `list` 与 nullable `nextCursor`，不含 total。上述 cursor route 只声明 `application/json`，没有 SSE cursor 合同。

`wow-apiclient` 包含手工维护的 Wow 命令与快照 CoApi 接口。[`wow-generator`](./typescript/generated-client.md) 从已发布 OpenAPI 生成 TypeScript 客户端，其他工具也可以为其他语言生成客户端。客户端生成位于 OpenAPI 下游：KSP 元数据不会生成这些客户端，重新生成客户端也不会改变服务端字段语义。OpenAPI 合同变化后必须审阅生成 diff。

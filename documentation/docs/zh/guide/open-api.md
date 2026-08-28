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

`OpenAPIAutoConfiguration` 创建 `RouterSpecs`；`WebFluxAutoConfiguration` 把目录物化为 `RouterFunction`；`WowOpenApiCustomizer` 把同一目录合并到 Springdoc。`wow.openapi.enabled=false` 禁用 Springdoc 定制，不会关闭 WebFlux 路由目录本身。

包含 Wow 注解的模块仍需应用 KSP 与 `wow-compiler`，并确保生成的 `META-INF/wow-metadata.json` 位于服务运行时 classpath。不要手写或提交生成资源。

## Swagger-UI

Swagger UI 是 Springdoc 应用特性，不属于路由合同本身。匹配的 Starter 存在且已启用时，Springdoc 页面通常位于 `/swagger-ui.html`，JSON 文档位于 `/v3/api-docs`。

![Swagger-UI](/images/compensation/open-api.png)

准确路径、方法、参数、媒体类型、operation ID 与 component 引用应以 JSON 文档为准。截图不是合同证据。

## 聚合资源归属

聚合元数据会合并 `@AggregateRoute`、命令级 `@CommandRoute`、tenant 元数据以及生成的命令/事件类型。资源归属影响路径形状，但不代表调用方授权。

路由目录还会根据 `@AggregateRoute(enabled = false)` 决定是否发布该聚合。这不会移除 HTTP 之外的命令处理或存储行为。

## RESTful URL PATH Spec

聚合路由的通用形状是：

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

默认路由从 resource name 开始。Wow 不会在本地路径前自动添加限界上下文 alias。客户端代码不应根据命名约定拼接路径，应检查生成 OpenAPI。

### 租户资源

动态 tenant 聚合的默认命令/状态路由会添加 `tenant/{tenantId}` 前缀；快照查询贡献者还会保留基础路由并增加 tenant 作用域变体。tenant 路径数据会传入运行时 Handler 与查询重写，但应用仍需把它绑定到已认证 Principal，并显式保护无作用域查询路由。

### 空间资源

启用 spaced 的路由会声明 `Wow-Space-Id` 请求头。Space 不增加路径段。该请求头参与命令上下文和查询作用域，但不构成身份认证。

### 拥有者资源

`AggregateRoute.Owner.ALWAYS` 会在默认 owned 路由添加 `owner/{ownerId}` 并保留 resource ID；快照查询同时发布基础和 owner 作用域变体：

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders", owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)
```

`AGGREGATE_ID` 使用 owner ID 作为 aggregate ID，并省略独立 resource-ID 段：

```kotlin
@AggregateRoot
@StaticTenantId
@AggregateRoute(resourceName = "cart", owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState)
```

查询 Schema 路由是例外：`/{aggregate}/snapshot/schema`、`/{aggregate}/event/schema` 及各自的 `/refresh` 描述查询模型，因此没有 tenant/owner 路径变体；spaced 聚合的公共合同仍可能声明 `Wow-Space-Id`。

## 全局路由

全局合同独立于聚合路由贡献：

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/wow/command/send` | API Client 使用的通用命令入口 |
| `POST` | `/wow/command/wait` | 等待信号接收端点 |
| `GET` | `/wow/metadata` | 已加载 Wow 元数据 |
| `POST` | `/wow/bi/script` | BI 同步脚本生成 |
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

`POST /wow/bi/script` 为当前本地聚合生成 ClickHouse 同步与展开 SQL。路由和 OpenAPI operation 默认存在，`wow.bi.script.enabled=false` 会同时移除两者。启用路由不会自动授权。

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

路由目录根据聚合元数据贡献 command、state、event、snapshot 和 query 路由。常用快照查询后缀如下：

| 方法 | 后缀 | 请求 / 响应 |
|---|---|---|
| `GET` | `snapshot/schema` | 运行时 `QueryModelSchemaMetadata` |
| `POST` | `snapshot/schema/refresh` | 刷新后的查询模型 Schema |
| `GET` | `event/schema` | 运行时 EventStream `QueryModelSchemaMetadata` |
| `POST` | `event/schema/refresh` | 刷新后的 EventStream 查询模型 Schema |
| `POST` | `snapshot/single` | `SingleQuery` -> 物化快照 |
| `POST` | `snapshot/single/state` | `SingleQuery` -> 仅状态 |
| `POST` | `snapshot/list` / `list/state` | `ListQuery` -> 数组或 SSE |
| `POST` | `snapshot/paged` / `paged/state` | `PagedQuery` -> `PagedList` |
| `POST` | `snapshot/count` | `FilterExpression` -> 精确计数 |
| `POST` | `snapshot/aggregation` | `AggregationQuery` -> 动态行或 SSE |

查询合同分为三个独立层次：

1. 通用 query component schemas 定义规范请求 JSON 形状。
2. 每个聚合专用 query request-body component 引用一个通用 Schema，并公开静态 `x-wow-query-fields`；其 enum 由 system fields 与 `JsonQuerySchemaSource` 推断字段组成。
3. 运行时 `snapshot/schema` 与 `event/schema` 路由分别发布合并后的 `QueryModelSchemaMetadata` 与后端已证明能力。

`x-wow-query-fields` 是 request-body component 上的 OpenAPI 设计时元数据，不会作为 JSON 请求属性嵌入，也不表示后端能力。

`wow-apiclient` 包含手工维护的 Wow 命令与快照 CoApi 接口。Fetcher 等外部工具可以从已发布 OpenAPI 生成其他客户端。客户端生成位于 OpenAPI 下游：KSP 元数据不会生成这些客户端，重新生成客户端也不会改变服务端字段语义。OpenAPI 合同变化后必须审阅生成 diff。

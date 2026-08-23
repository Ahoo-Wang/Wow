---
title: WebFlux
description: Spring WebFlux 扩展，自动注册命令路由处理函数。
---

# WebFlux

_WebFlux_ 扩展提供了对 _Spring WebFlux_ 的支持，依赖 `wow-openapi` 模块生成的路由规范，自动注册命令路由处理函数，实现声明式的 REST API。

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-webflux")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-webflux'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-webflux</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## 自动路由注册

WebFlux 扩展自动为所有命令生成 REST API 端点：

### 路由模式

支持多种路由模式：

#### 聚合路由模式
```kotlin
@StaticTenantId
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState)

// 生成路由: POST /owner/{ownerId}/cart/add_cart_item
```

#### 拥有者路由模式
```kotlin
@AggregateRoot
@AggregateRoute(
    resourceName = "sales-order",
    spaced = true,
    owner = AggregateRoute.Owner.ALWAYS,
)
class Order(private val state: OrderState)

@CommandRoute(action = "")
@CreateAggregate
data class CreateOrder(/* ... */)

// 生成路由: POST /tenant/{tenantId}/owner/{ownerId}/sales-order
// Wow-Space-Id 是可选请求头；省略时使用默认 space。
```

### HTTP 方法映射

| 命令注解                             | HTTP 方法 | 默认路径                    |
|----------------------------------|---------|-------------------------|
| `@CreateAggregate`               | POST    | `/{resource}`           |
| `@CommandRoute(method = POST)`   | POST    | `/{resource}/{command}` |
| `@CommandRoute(method = PUT)`    | PUT     | `/{resource}/{command}` |
| `@CommandRoute(method = DELETE)` | DELETE  | `/{resource}/{command}` |

## 配置

::: tip 前置条件
`WebFluxProperties` 与 `WebFluxAutoConfiguration` 位于 `wow-spring-boot-starter`，不在 `wow-webflux` 中。需要同时引入 `wow-spring-boot-starter`（或请求 `webflux-support` capability）与 `wow-webflux`，这些属性才会绑定并启用自动配置。
:::

- 配置类：[WebFluxProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxProperties.kt)
- 前缀：`wow.webflux.`

| 名称 | 数据类型 | 默认值 | 描述 |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | 是否启用 WebFlux 扩展（路由注册） |
| `global-error.enabled` | `Boolean` | `true` | 是否安装全局异常处理器，将错误映射为统一的 `ErrorInfo` 响应 |
| `batch.concurrency` | `Int` | `1` | 单次批量执行中并发处理的最大请求数 |
| `batch.prefetch` | `Int` | `1` | 批量请求处理的预取窗口 |
| `query.max-list-size` | `Int` | `1000` | HTTP 列表查询 limit 与分组快照聚合结果行上限；`0` 关闭 HTTP 上限并恢复列表 `limit=0`，聚合仍受公共 10,000 硬上限约束 |
| `query.max-page-size` | `Int` | `100` | HTTP 分页查询的最大页大小；`0` 关闭上限 |
| `query.max-page-window` | `Long` | `10000` | HTTP 分页查询允许的最大 `index * size`；`0` 关闭上限 |
| `query.max-condition-nodes` | `Int` | `64` | HTTP FilterExpression 节点数上限；`0` 关闭上限 |
| `query.max-condition-values` | `Int` | `1000` | HTTP 集合过滤器的值数量上限；`0` 关闭上限 |
| `query.max-aggregation-elements` | `Int` | `3` | HTTP 快照聚合 Elements 深度上限；`0` 关闭 HTTP 上限，公共硬上限 5 仍生效 |
| `query.max-aggregation-metrics` | `Int` | `32` | 单个 HTTP 快照聚合的 metrics 上限；`0` 关闭 HTTP 上限，公共硬上限 64 仍生效 |
| `query.allow-expensive-operators` | `Boolean` | `false` | 是否允许 HTTP 查询使用高成本过滤器、无过滤 count/paged/aggregation、Elements 展开及 metric 排序 |
| `query.idle-timeout` | `Duration` | `10s` | 等待下一条结果或完成的最长时间；普通 JSON 数组在提交响应前缓冲，SSE 保持流式；`0s` 关闭超时 |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
    batch:
      concurrency: 4
      prefetch: 4
    query:
      max-list-size: 1000
      max-page-size: 100
      max-page-window: 10000
      max-condition-nodes: 64
      max-condition-values: 1000
      max-aggregation-elements: 3
      max-aggregation-metrics: 32
      allow-expensive-operators: false
      idle-timeout: 10s
```

使用 `wow-spring-boot-starter` 时，WebFlux 作为 `webflux-support` 特性能力包含在内。全局异常处理器默认启用；仅当你提供自己的 `WebExceptionHandler` 时才需关闭。
Reactor Context 通过 `writeRawRequest(request)` 携带 WebFlux `ServerRequest` 时都会启用护栏，包括内置路由和自定义 HTTP Handler；程序内注入的查询服务和非 WebFlux 请求上下文保持原行为。快照聚合的 HTTP 请求、成本与失败合同参见[快照 Elements 聚合](../query.md#快照-elements-聚合)。

## 等待计划集成

WebFlux 扩展支持通过 HTTP 头指定等待计划：

```http
POST /owner/cart-123/cart/add_cart_item
Content-Type: application/json
Command-Wait-Stage: PROCESSED
Command-Wait-Timeout: 30000

{
  "productId": "product-456",
  "quantity": 2
}
```

### 支持的等待计划

全部六个命令阶段都可作为等待计划（前置条件与语义参见 [命令网关](../command-gateway.md#等待计划)）：

- `SENT`：命令已被总线接受
- `PROCESSED`：聚合已执行命令
- `SNAPSHOT`：快照处理已完成；`version_offset` 可能跳过写入
- `PROJECTED`：读模型投影已更新（按函数匹配）
- `EVENT_HANDLED`：外部事件处理器已完成（按函数匹配）
- `SAGA_HANDLED`：Saga 已完成事件处理（按函数匹配）

当请求头包含 `Accept: text/event-stream` 时，处理器通过 SSE 按阶段流式返回 `CommandResult` 事件，而不是返回单个 JSON 响应。

## 错误处理

WebFlux 扩展提供统一的错误响应格式：

```json
{
  "errorCode": "VALIDATION_ERROR",
  "errorMsg": "Product not found",
  "requestId": "req-123"
}
```

响应的 HTTP 状态码由错误推导：Wow 的 `ErrorInfoCapable` / `ErrorInfo` 异常以及 Spring 的 `ErrorResponse` 自带状态码；绑定与校验错误映射为 `400`；`IllegalArgumentException`/`IllegalStateException` 映射为 `400`；`TimeoutException` 映射为 **408**（`REQUEST_TIMEOUT`）；`FileNotFoundException` 映射为 `404`；否则回退为 **500**。仅特殊错误如 `BiDeploymentInspectionException.Timeout` 映射为 **504**（网关超时）。响应头 `Wow-Error-Code` 携带 Wow 的 `errorCode`，便于程序化处理。

## OpenAPI 集成

自动生成 OpenAPI 文档：

```yaml
paths:
  /owner/{ownerId}/cart/add_cart_item:
    post:
      summary: "Add item to cart"
      parameters:
        - name: ownerId
          in: path
          required: true
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AddCartItem'
```

## 性能优化

### 响应式处理

所有端点都使用响应式编程：

```kotlin
@RestController
class CustomController(
    private val commandGateway: CommandGateway
) {

    @PostMapping("/custom/{id}")
    fun customCommand(@PathVariable id: String): Mono<CommandResult> {
        val command = CustomCommand(id = id).toCommandMessage()
        return commandGateway.sendAndWait(
            command,
            CommandWait.processed(command.commandId)
        )
    }
}
```

Wow 的处理器保持非阻塞。编解码、Reactor Netty 资源和服务端超时应通过对应的 Spring Boot 能力配置；Wow WebFlux 扩展自身不定义连接池或会话超时属性。

## 监控和调试

### 请求日志

```yaml
logging:
  level:
    me.ahoo.wow.webflux: DEBUG
```

Wow 运行时的专用指标由可观测性集成提供，而不是由 `wow-webflux` 自动采集。支持的埋点参见 [OpenTelemetry](./opentelemetry)。

## 最佳实践

1. **使用等待计划**: 根据业务需求选择合适的等待计划
2. **错误处理**: 实现全局异常处理器
3. **安全**: 启用认证和授权检查
4. **可观测性**: 需要 Wow 运行时链路和指标时引入 OpenTelemetry capability
5. **运行时调优**: 在应用边界配置 Reactor Netty 与 Spring Boot 服务端限制

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
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState)

// 生成路由: POST /cart/{cartId}/add_cart_item
```

#### 拥有者路由模式
```kotlin
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)

// 生成路由: POST /order/owner/{ownerId}/create_order
```

### HTTP 方法映射

| 命令注解                             | HTTP 方法 | 默认路径                    |
|----------------------------------|---------|-------------------------|
| `@CreateAggregate`               | POST    | `/{resource}`           |
| `@CommandRoute(method = POST)`   | POST    | `/{resource}/{command}` |
| `@CommandRoute(method = PUT)`    | PUT     | `/{resource}/{command}` |
| `@CommandRoute(method = DELETE)` | DELETE  | `/{resource}/{command}` |

## 配置

- 配置类：[WebFluxProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxProperties.kt)
- 前缀：`wow.webflux.`

| 名称 | 数据类型 | 默认值 | 描述 |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | 是否启用 WebFlux 扩展（路由注册） |
| `global-error.enabled` | `Boolean` | `true` | 是否安装全局异常处理器，将错误映射为统一的 `ErrorInfo` 响应 |
| `batch.concurrency` | `Int` | `1` | 单次批量执行中并发处理的最大请求数 |
| `batch.prefetch` | `Int` | `1` | 批量请求处理的预取窗口 |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
    batch:
      concurrency: 4
      prefetch: 4
```

使用 `wow-spring-boot-starter` 时，WebFlux 作为 `webflux-support` 特性能力包含在内。全局异常处理器默认启用；仅当你提供自己的 `WebExceptionHandler` 时才需关闭。

## 等待计划集成

WebFlux 扩展支持通过 HTTP 头指定等待计划：

```http
POST /cart/123/add_cart_item
Content-Type: application/json
Command-Wait-Stage: PROCESSED
Command-Wait-Timeout: 30000

{
  "productId": "product-456",
  "quantity": 2
}
```

### 支持的等待计划

全部六个命令阶段都可作为等待计划（前置条件与语义参见 [命令网关](../command-gateway.md#wait-plans)）：

- `SENT`：命令已被总线接受
- `PROCESSED`：聚合已执行命令
- `SNAPSHOT`：聚合快照已持久化
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

响应的 HTTP 状态码由错误推导：Wow 的 `ErrorInfoCapable` / `ErrorInfo` 异常以及 Spring 的 `ErrorResponse` 自带状态码；绑定与校验错误映射为 `400`；`IllegalArgumentException`/`IllegalStateException` 映射为 `400`；`TimeoutException` 映射为 `504`；`FileNotFoundException` 映射为 `404`；`TimeoutException` 映射为 **408**（`REQUEST_TIMEOUT`）。仅特殊错误如 `BiDeploymentInspectionException.Timeout` 映射为 **504**（网关超时）。否则回退为 **500**。响应头 `Wow-Error-Code` 携带 Wow 的 `errorCode`，便于程序化处理。

## OpenAPI 集成

自动生成 OpenAPI 文档：

```yaml
paths:
  /cart/{cartId}/add_cart_item:
    post:
      summary: "Add item to cart"
      parameters:
        - name: cartId
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

### 连接池配置

```yaml
spring:
  codec:
    max-in-memory-size: 10MB
  webflux:
    session:
      timeout: 30m
```

## 监控和调试

### 请求日志

```yaml
logging:
  level:
    me.ahoo.wow.webflux: DEBUG
```

### 性能指标

自动收集以下指标：
- 请求延迟和吞吐量
- 错误率统计
- 等待计划使用情况

## 最佳实践

1. **使用等待计划**: 根据业务需求选择合适的等待计划
2. **错误处理**: 实现全局异常处理器
3. **安全**: 启用认证和授权检查
4. **监控**: 配置适当的日志级别和指标收集
5. **性能**: 合理配置连接池和超时时间

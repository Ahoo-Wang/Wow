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

## 等待策略集成

WebFlux 扩展支持通过 HTTP 头指定等待策略：

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

### 支持的等待策略

- `SENT`: 命令发送完成
- `PROCESSED`: 命令处理完成
- `PROJECTED`: 事件投影完成
- `SNAPSHOT`: 快照创建完成

## 错误处理

WebFlux 扩展提供统一的错误响应格式：

```json
{
  "errorCode": "VALIDATION_ERROR",
  "errorMsg": "Product not found",
  "requestId": "req-123"
}
```

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
        return commandGateway.sendAndWait(
            CustomCommand(id = id),
            WaitStrategy.PROCESSED
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
- 等待策略使用情况

## 最佳实践

1. **使用等待策略**: 根据业务需求选择合适的等待策略
2. **错误处理**: 实现全局异常处理器
3. **安全**: 启用认证和授权检查
4. **监控**: 配置适当的日志级别和指标收集
5. **性能**: 合理配置连接池和超时时间
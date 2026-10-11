# Wow API

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)

`wow-api` 是 Wow 的公共契约层，定义命令、事件、消息、聚合标识、查询模型和领域建模注解。

## 何时使用

- 在 API 或领域契约模块中声明命令、事件、值对象、路由和限界上下文。
- 在不同模块之间共享 `CommandMessage`、`DomainEvent`、`AggregateId`、`Header` 或查询 DTO。

如果需要命令分发、事件溯源、Saga 或投影运行时，请使用 `wow-core`；Spring Boot 应用通常从 `wow-spring-boot-starter` 选择能力。

## 依赖

Maven 坐标：`me.ahoo.wow:wow-api`。推荐用 Wow BOM 对齐版本：

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-api")
}
```

## 公开边界

公开 API 位于 `me.ahoo.wow.api.*`，主要包括：

- `annotation`：聚合、命令、事件、Saga、投影和路由注解；
- `command`、`event`、`messaging`、`modeling`：跨模块消息与领域身份契约；
- `query`：查询请求、过滤、排序、分页与聚合模型。

本模块不包含 Dispatcher、EventStore 实现、Broker/数据库适配器、HTTP 路由或 Spring 自动配置。添加该依赖不会安装运行时基础设施。

## 最小示例

下面的命令与事件缩减自仓库中的[购物车 API](../example/example-api/src/main/kotlin/me/ahoo/wow/example/api/cart/AddCartItem.kt)：

```kotlin
import me.ahoo.wow.api.annotation.AllowCreate

@AllowCreate
data class AddCartItem(
    val productId: String,
    val quantity: Int = 1,
)

data class CartItem(
    val productId: String,
    val quantity: Int = 1,
)

data class CartItemAdded(
    val added: CartItem,
)
```

`@AllowCreate` 允许该命令在目标聚合不存在时创建聚合；聚合如何处理命令属于领域实现，不属于 `wow-api`。

## 验证

```bash
./gradlew :wow-api:check
```

## 继续阅读

- [聚合建模](../documentation/docs/zh/guide/modeling.md)
- [命令网关](../documentation/docs/zh/guide/command-gateway.md)
- [模块依赖](../documentation/docs/zh/guide/advanced/module-dependencies.md)

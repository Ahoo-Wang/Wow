# wow-test

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-test)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-test)

`wow-test` 提供基于 JUnit 动态测试的 Given → When → Expect DSL，用于验证聚合和无状态 Saga 的领域行为。

## 何时使用

- 用 `AggregateSpec` 验证命令决策、领域事件和事件溯源后的状态。
- 用 `SagaSpec` 验证输入事件产生的命令。
- 在测试中注入领域服务，或从已验证状态创建分支场景。

## 依赖

Maven 坐标：`me.ahoo.wow:wow-test`，应只加入测试配置：

```kotlin
dependencies {
    testImplementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    testImplementation("me.ahoo.wow:wow-test")
}
```

Kotlin 断言使用该模块已提供的 FluentAssert 扩展：`me.ahoo.test.asserts.assert`。

## 公开边界

公开 API 位于 `me.ahoo.wow.test.*`，入口是 `AggregateSpec`、`SagaSpec` 及其 DSL。聚合规格默认使用内存 EventStore，Saga 规格默认使用内存 CommandBus。聚合规格可以验证已建模的领域生命周期转换；当前 `CartSpec` 就覆盖了 `DefaultDeleteAggregate` 和 `DefaultRecoverAggregate`。

这些规格证明领域决策、事件、溯源状态或 Saga 命令，不证明 KSP 产物、Spring 装配、HTTP 路由、真实 Broker/数据库、真实存储恢复、进程重启、生产基础设施恢复或鉴权。

## 最小示例

下面的场景缩减自当前[购物车规格](../../example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartSpec.kt)：

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        whenCommand(AddCartItem(productId = "productId", quantity = 1)) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
        }
    }
})
```

事件断言验证命令决策，状态断言验证事件已由 sourcing 函数应用。

## 验证

```bash
./gradlew :wow-test:check
```

## 继续阅读

- [领域测试套件](../../documentation/docs/zh/guide/test-suite.md)
- [Wow 应用测试](../../documentation/docs/zh/guide/application-testing.md)
- [聚合建模](../../documentation/docs/zh/guide/modeling.md)

# 测试套件

*单元测试*是确保代码质量且符合预期业务需求的重要手段，但在传统架构中，单元测试往往是一项相当困难的任务，因为你需要考虑数据库连接、事务管理、数据清理等问题。

使用 _Wow_ 框架，你将会发现基于 _Given->When->Expect_ 模式的测试套件，使得单元测试变得异常简单。
你只需关注领域模型是否符合预期，而无需为数据库连接等问题烦恼。

:::tip
在实际应用中，我们将领域模型的单元测试覆盖率下限阈值设置为 **85%**，也是可以轻松实现的。
在没有刻意要求的情况下，开发人员甚至自觉地将覆盖率提升至 **95%**。
因此，每次提交代码都变得轻松自在，因为你确信你的代码经过了充分的测试，并且真正意义上从单元测试中获得了收益。
:::

在研发同级别的项目中，我们的测试团队在系统 _API_ 测试中发现，基于 Wow 框架的项目，其 _BUG_ 数仅为传统架构项目的 **1/3**。

- Given: 先前的领域事件，用于初始化聚合根状态。
- When：当前执行的命令，用于触发聚合根状态变更。
- Expect：期望的结果，用于验证聚合根状态变更是否符合预期。

![Test Coverage](../public/images/getting-started/test-coverage.png)

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
testImplementation("me.ahoo.wow:wow-test")
```
```groovy [Gradle(Groovy)]
testImplementation 'me.ahoo.wow:wow-test'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-test</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```
:::

## 测试聚合根

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            val ownerId = generateGlobalId()
            val addCartItem = AddCartItem(
                productId = "productId",
                quantity = 1,
            )
            givenOwnerId(ownerId)
            whenCommand(addCartItem) {
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState {
                    items.assert().hasSize(1)
                }
                expectStateAggregate {
                    ownerId.assert().isEqualTo(ownerId)
                }
                fork {
                    val removeCartItem = RemoveCartItem(
                        productIds = setOf(addCartItem.productId),
                    )
                    whenCommand(removeCartItem) {
                        expectEventType(CartItemRemoved::class)
                    }
                }
                fork {
                    whenCommand(DefaultDeleteAggregate) {
                        expectEventType(DefaultAggregateDeleted::class)
                        expectStateAggregate {
                            deleted.assert().isTrue()
                        }

                        fork {
                            whenCommand(DefaultDeleteAggregate) {
                                expectErrorType(IllegalAccessDeletedAggregateException::class)
                            }
                        }
                        fork {
                            whenCommand(DefaultRecoverAggregate) {
                                expectNoError()
                                expectStateAggregate {
                                    deleted.assert().isFalse()
                                }
                                fork {
                                    whenCommand(DefaultRecoverAggregate) {
                                        expectErrorType(IllegalStateException::class)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
)
```

## 测试 Saga

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        whenEvent(
            event = mockk<OrderCreated> {
                every {
                    items
                } returns listOf(orderItem)
                every {
                    fromCart
                } returns true
            },
            ownerId = ownerId
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
    on {
        name("NotFromCart")
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        whenEvent(
            event = mockk<OrderCreated> {
                every {
                    items
                } returns listOf(orderItem)
                every {
                    fromCart
                } returns false
            },
            ownerId = generateGlobalId()
        ) {
            expectNoCommand()
        }
    }
})
```

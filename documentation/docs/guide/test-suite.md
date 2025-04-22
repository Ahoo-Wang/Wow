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
class CartTest {

    @Test
    fun addCartItem() {
        val ownerId = generateGlobalId()
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>(ownerId)
            .givenOwnerId(ownerId)
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                it.items.assert().hasSize(1)
            }.expectStateAggregate {
                it.ownerId.assert().isEqualTo(ownerId)
            }
            .verify()
    }

    @Test
    fun givenStateWhenAdd() {
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .givenState(CartState(generateGlobalId()), 1)
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                it.items.assert().hasSize(1)
            }
            .verify()
    }

    @Test
    fun addCartItemIfSameProduct() {
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = CartItem(
                        productId = addCartItem.productId,
                        quantity = 1,
                    ),
                ),
            )
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartQuantityChanged::class.java)
            .expectState {
                it.items.assert().hasSize(1)
                it.items.first().quantity.assert().isEqualTo(2)
            }
            .verify()
    }

    @Test
    fun addCartItemIfUnCreated() {
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>()
            .given()
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                it.items.assert().hasSize(1)
            }
            .expectStateAggregate {
                it.version.assert().isEqualTo(1)
            }
            .verify()
    }

    @Test
    fun addCartItemGivenMax() {
        val events = buildList {
            for (i in 0..99) {
                add(
                    CartItemAdded(
                        added = CartItem(
                            productId = "productId$i",
                            quantity = 1,
                        ),
                    ),
                )
            }
        }.toTypedArray()
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(*events)
            .whenCommand(addCartItem)
            .expectErrorType(IllegalArgumentException::class.java)
            .expectState {
                it.items.assert().hasSize(MAX_CART_ITEM_SIZE)
            }
            .verify()
    }

    @Test
    fun removeCartItem() {
        val removeCartItem = RemoveCartItem(
            productIds = setOf("productId"),
        )
        val added = CartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = added,
                ),
            )
            .whenCommand(removeCartItem)
            .expectEventType(CartItemRemoved::class.java)
            .expectState {
                it.items.assert().isEmpty()
            }
            .verify()
    }

    @Test
    fun changeQuantity() {
        val changeQuantity = ChangeQuantity(
            productId = "productId",
            quantity = 2,
        )
        val added = CartItem(
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = added,
                ),
            )
            .whenCommand(changeQuantity)
            .expectEventType(CartQuantityChanged::class.java)
            .expectState {
                it.items.assert().hasSize(1)
                it.items.first().quantity.assert().isEqualTo(changeQuantity.quantity)
            }
            .verify()
    }

    @Test
    fun onCreateThenDeleteThenRecover() {
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>()
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                it.items.assert().hasSize(1)
            }
            .verify()
            .then()
            .whenCommand(DefaultDeleteAggregate)
            .expectEventType(DefaultAggregateDeleted::class.java)
            .expectStateAggregate {
                it.deleted.assert().isTrue()
            }.verify()
            .then()
            .whenCommand(DefaultDeleteAggregate::class.java)
            .expectErrorType(IllegalAccessDeletedAggregateException::class.java)
            .verify()
            .then()
            .whenCommand(DefaultRecoverAggregate)
            .expectStateAggregate {
                it.deleted.assert().isFalse()
            }.verify()
            .then()
            .whenCommand(DefaultRecoverAggregate)
            .expectErrorType(IllegalStateException::class.java)
            .verify()
    }
}

```

## 测试 Saga

```kotlin
class CartSagaTest {

    @Test
    fun onOrderCreated() {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        sagaVerifier<CartSaga>()
            .whenEvent(
                event = mockk<OrderCreated> {
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
                ownerId = ownerId
            )
            .expectCommand<RemoveCartItem> {
                it.aggregateId.id.assert().isEqualTo(ownerId)
                it.body.productIds.assert().hasSize(1)
                it.body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
            .verify()
    }
}
```

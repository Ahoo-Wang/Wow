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
internal class OrderTest {

    @Test
    private fun createOrder() {
        val tenantId = GlobalIdGenerator.generateAsString()
        val customerId = GlobalIdGenerator.generateAsString()

        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }.map { it.quantity }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }
        aggregateVerifier<Order, OrderState>(tenantId = tenantId)
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS, false))
            .expectEventCount(1)
            .expectEventType(OrderCreated::class.java)
            .expectStateAggregate {
                assertThat(it.aggregateId.tenantId, equalTo(tenantId))
            }
            .expectState {
                assertThat(it.id, notNullValue())
                assertThat(it.customerId, equalTo(customerId))
                assertThat(it.address, equalTo(SHIPPING_ADDRESS))
                assertThat(it.items, equalTo(orderItems))
                assertThat(it.status, equalTo(OrderStatus.CREATED))
            }
            .verify()
    }

    @Test
    fun createOrderGivenEmptyItems() {
        val customerId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Order, OrderState>()
            .inject(mockk<CreateOrderSpec>(), "createOrderSpec")
            .given()
            .`when`(CreateOrder(customerId, listOf(), SHIPPING_ADDRESS, false))
            .expectErrorType(IllegalArgumentException::class.java)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                assertThat(it.initialized, equalTo(false))
            }.verify()
    }

    /**
     * 创建订单-库存不足
     */
    @Test
    fun createOrderWhenInventoryShortage() {
        val customerId = GlobalIdGenerator.generateAsString()
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }
                    /*
                     * 模拟库存不足
                     */
                    .map { it.quantity - 1 }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }

        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS, false))
            /*
             * 期望：库存不足异常.
             */
            .expectErrorType(InventoryShortageException::class.java)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                assertThat(it.initialized, equalTo(false))
            }.verify()
    }
}
```

## 测试 Saga

```kotlin
class CartSagaTest {

    @Test
    fun onOrderCreated() {
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
        )
        sagaVerifier<CartSaga>()
            .`when`(
                mockk<OrderCreated> {
                    every {
                        customerId
                    } returns "customerId"
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
            )
            .expectCommandBody<RemoveCartItem> {
                assertThat(it.id, equalTo("customerId"))
                assertThat(it.productIds, hasSize(1))
                assertThat(it.productIds.first(), equalTo(orderItem.productId))
            }
            .verify()
    }
}
```

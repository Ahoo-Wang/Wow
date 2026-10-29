---
title: 测试服务
description: 在测试的 JVM 中经 HTTP 测试整个服务：主题在内存中、对着容器里的真实存储，以及对照它提交的 Manifest。
outline: deep
---

# 测试服务

领域测试检查决定和处理器（[测试领域代码](./testing)）。服务测试检查其余部分：宿主能用服务的模块和配置构建出服务、它的路由能回答、它的 Saga 和定时能运行，以及它能在生产要用的存储上工作。本页在三个层次上测试 eShop 的 ordering 服务。

| 层次 | 运行 | 证明 |
| --- | --- | --- |
| 进程内 | 服务运行在测试的 JVM 里，使用内存后端，主题在内存中 | 装配、路由、Saga、等待、定时器 |
| 真实存储 | 同上，但使用容器里的 PostgreSQL 和 ClickHouse | schema、查询、Sink |
| 契约 | 构建合并出的 Manifest 对照提交的基线 | 没有改变破坏客户端，基线记录了每处改变 |

## 在测试中运行服务 {#run-the-service-in-the-test}

服务测试用 `wow-test-spring-boot` 的 `wowServer<App>(profiles…)` 启动应用，使用随机端口和回环地址，并经它的 `http` 客户端与它通信：

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#start -->

```kotlin
private val server = wowServer<OrderingServer>(
    "dev",
    properties = mapOf(
        "wow.timers.poll-interval" to "200ms",
        "eshop.ordering.payment-grace-period" to "2s",
    ),
    sources = listOf(InMemoryOrderingTopics::class.java),
)
```

- 这个 fixture 把两个端口都绑定在回环网卡上的空闲端口。内部端口只绑定在回环网卡上、又没有存储 Bean 时，节点使用内存后端。
- 它缩短节点关闭时等待的超时（`wow.cluster.drain-timeout`、`wow.subscription.stop-timeout`），让测试类之间的关闭很快；`properties` 加上测试自己的属性，这里是 `wow.timers.poll-interval=200ms`，让定时命令及时触发。
- 应用自己的设置缩短测试要等待的东西：这里的支付宽限期是两秒，而不是三十分钟。

ordering 的 Saga 会发送 inventory 的命令，所以节点需要命令传输。测试给它内存中的主题，而不是 Kafka：

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#topics -->

```kotlin
@Configuration(proxyBeanMethods = false)
class InMemoryOrderingTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics)
}
```

`wow.transport=memory` 从配置做到同样的事，用于在一台机器上试用服务。

然后测试像客户端那样驱动服务，并等待异步发生的事情。库存已确认却没有支付的订单，在它的定时命令到期时会取消自己：

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#grace -->

```kotlin
@Test
fun `an order not paid within its grace period should cancel itself`() {
    submit("order-unpaid")
    eventually("order-unpaid awaits validation") { status("order-unpaid") == "AWAITING_VALIDATION" }
    post("/eshop-ordering/order/order-unpaid/confirm-order-stock", """{"productId":"mug"}""")
    post("/eshop-ordering/order/order-unpaid/confirm-order-stock", """{"productId":"cap"}""")
    status("order-unpaid").assert().isEqualTo("STOCK_CONFIRMED")
    eventually("order-unpaid cancelled") { status("order-unpaid") == "CANCELLED" }
    state("order-unpaid")["cancelReason"].asString().assert().contains("payment grace period")
    val late = post("/eshop-ordering/order/order-unpaid/pay-order", """{"paymentId":"p-2","amount":29.00}""")
    late.status.assert().isEqualTo(CONFLICT)
    late.body!!["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-status")
}
```

## 在真实存储上运行 {#run-it-on-real-stores}

内存后端不提供快照查询，也不写数仓。测试这些功能，要让服务运行在存储本身上，由 Testcontainers 启动它们。插件为模块注册了单独的测试套件 `integrationTest`，不属于 `check`，这样单元测试保持快速，也不需要 Docker：

<!-- sample: examples/eshop/ordering/ordering-server/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingAnalyticsTest.kt#containers -->

```kotlin
private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
private val clickhouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }
private val server = wowServer<OrderingServer>(
    "postgres",
    "clickhouse",
    "dev",
    properties = mapOf(
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "wow.clickhouse.endpoint" to clickhouse.httpUrl,
        "wow.clickhouse.username" to clickhouse.username,
        "wow.clickhouse.password" to clickhouse.password,
        "wow.log.partitions" to 4,
    ),
    sources = listOf(InMemoryOrderingTopics::class.java),
)
```

- 应用的 `postgres` profile 定义了它的 `DataSource`，所以宿主选择 PostgreSQL；`clickhouse` profile 打开数仓。
- 存储在启动时创建它们的 schema（`wow.ddl: auto`），所以一个全新的容器不需要别的东西。
- `wow.log.partitions=4` 让日志保持很小：测试不需要 64 个分区。

## 检查契约 {#check-the-contract}

Gradle 插件 `me.ahoo.wow.service` 从服务的模块合并出它的 Manifest，并在 `checkWowManifest`（`check` 的一部分）中与提交的基线 `manifest/baseline.json` 比较。破坏客户端的改变会让构建失败，并列出每个破坏性改变及其路径；基线尚未记录的改变、缺少基线也会让构建失败。有意的改变之后，`updateWowManifestBaseline` 记录新的基线（不含服务的版本）；把它与改变一起提交，这样评审时就能看到客户端会看到什么。什么算破坏性改变，见[一份契约](../concepts/contract#compatibility)。

## 跨服务测试 {#test-across-services}

相互调用的服务以同样的方式一起测试：在测试的 JVM 中启动每个服务，让一个指向另一个的内部端口，再驱动整个流程。eShop 的 `:eshop-e2e` 模块就是这样运行五个服务的，它的集成测试还在容器中基于 Kafka 和 PostgreSQL 运行它们。

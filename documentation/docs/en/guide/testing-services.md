---
title: Test a Service
description: Test a whole service in its JVM over HTTP, with its topics in memory, against real stores in containers, and against its committed Manifest.
outline: deep
---

# Test a Service

Domain tests check the decisions and the processors ([Test Domain Code](./testing)). A service test checks the rest: that the host builds the service from its modules and its configuration, that its routes answer, that its sagas and schedules run, and that it works on the stores it will use in production. This page tests the eShop's ordering service at three levels.

| Level | Runs | Proves |
| --- | --- | --- |
| In process | The service in the test's JVM, on the memory backend, topics in memory | The assembly, the routes, the sagas, the waits, the timers |
| On real stores | The same, on PostgreSQL and ClickHouse in containers | The schema, the queries, the sinks |
| The contract | The build's merged Manifest against the committed baseline | No change breaks a client within its major version |

## Run the service in the test

A service test starts the application with `SpringApplicationBuilder`, on random ports and loopback addresses, and talks to it over HTTP:

<!-- sample: example/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#start -->

```kotlin
private val context = SpringApplicationBuilder(
    OrderingServer::class.java,
    InMemoryOrderingTopics::class.java
).profiles("dev").run(
    "--spring.main.banner-mode=off",
    "--server.port=0",
    "--server.address=127.0.0.1",
    "--wow.internal.port=0",
    "--wow.internal.host=127.0.0.1",
    "--wow.cluster.drain-timeout=1s",
    "--wow.subscription.stop-timeout=2s",
    "--wow.wait.max-timeout=15s",
    "--wow.timers.poll-interval=200ms",
    "--eshop.ordering.payment-grace-period=2s",
)
```

- `--server.port=0` and `--wow.internal.port=0` take free ports. On a loopback-only internal port and without a store bean, the node runs on the memory backend.
- Short timeouts (`wow.cluster.drain-timeout`, `wow.subscription.stop-timeout`) keep the shutdown between test classes quick, and `wow.timers.poll-interval=200ms` fires scheduled commands promptly.
- The application's own settings shorten what the test waits for: here the payment grace period, two seconds instead of thirty minutes.

The ordering's sagas send the inventory's commands, so the node needs a command transport. The test gives it topics in memory instead of Kafka:

<!-- sample: example/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#topics -->

```kotlin
@Configuration(proxyBeanMethods = false)
class InMemoryOrderingTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics)
}
```

`wow.transport=memory` does the same from configuration, for trying a service out on one machine.

A test then drives the service as a client would, and waits for what happens asynchronously. An order whose stock is confirmed and that is not paid cancels itself when its scheduled command is due:

<!-- sample: example/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#grace -->

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
    late.first.assert().isEqualTo(CONFLICT)
    late.second["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-status")
}
```

## Run it on real stores

The memory backend serves no snapshot queries and writes no warehouse. A test of those runs the service on the stores themselves, started by Testcontainers. The eShop keeps such tests in a source set of their own, `integrationTest`, so the unit tests stay fast and need no Docker:

<!-- sample: example/eshop/ordering/ordering-server/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingAnalyticsTest.kt#containers -->

```kotlin
private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
private val clickhouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }
private val context = SpringApplicationBuilder(OrderingServer::class.java, InMemoryOrderingTopics::class.java)
    .profiles("postgres", "clickhouse", "dev")
    .run(
        "--spring.main.banner-mode=off",
        "--spring.datasource.url=${postgres.jdbcUrl}",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
        "--wow.clickhouse.endpoint=${clickhouse.httpUrl}",
        "--wow.clickhouse.username=${clickhouse.username}",
        "--wow.clickhouse.password=${clickhouse.password}",
        "--wow.log.partitions=4",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--wow.wait.max-timeout=15s",
    )
private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
```

- The application's `postgres` profile defines its `DataSource`, so the host chooses PostgreSQL; the `clickhouse` profile turns the warehouse on.
- The stores create their schemas when they start (`wow.ddl: auto`), so a fresh container needs nothing else.
- `wow.log.partitions=4` keeps a small log: a test does not need 64 partitions.

## Check the contract

The Gradle plugin `me.ahoo.wow` merges the service's Manifest from its modules and compares it with the committed baseline, `manifest/baseline.json`, in `checkManifestCompatibility`, part of `check`. A change that breaks a client within the baseline's major version fails the build, listing each breaking change and its path. After an intended change, `updateManifestBaseline` records the new baseline; commit it with the change, so its review shows what the clients will see. [The Manifest](../concepts/contract#compatibility) explains what counts as breaking.

## Test across services

Services that call each other are tested together the same way: start each in the test's JVM, point one at the other's internal port, and drive the flow. The eShop's `:eshop-e2e` module runs the five services so, and in containers on Kafka and PostgreSQL in its integration tests.

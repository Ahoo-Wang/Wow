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
| The contract | The build's merged Manifest against the committed baseline | No change breaks a client, and the baseline records every change |

## Run the service in the test

A service test starts the application with `wowServer<App>(profiles…)` from `wow-test-spring-boot`, on random ports and loopback addresses, and talks to it over HTTP through its `http` client:

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

- The fixture binds both ports on the loopback interface at free ports. On a loopback-only internal port and without a store bean, the node runs on the memory backend, which serves the snapshot and events queries from memory unless `wow.query.memory.enabled` is `false`.
- It shortens the timeouts a node waits on at its close (`wow.cluster.drain-timeout`, `wow.subscription.stop-timeout`), so the shutdown between test classes is quick; `properties` add the test's own, here `wow.timers.poll-interval=200ms`, which fires scheduled commands promptly.
- The application's own settings shorten what the test waits for: here the payment grace period, two seconds instead of thirty minutes.

The ordering's sagas send the inventory's commands, so the node needs a command transport. The test gives it topics in memory instead of Kafka:

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

`wow.transport=memory` does the same from configuration, for trying a service out on one machine.

A test then drives the service as a client would, and waits for what happens asynchronously. An order whose stock is confirmed and that is not paid cancels itself when its scheduled command is due:

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

## Run it on real stores

The memory backend answers the snapshot and events queries from memory, by the in-memory engine, and writes no warehouse. A test of a store's schema, of its own query engine and of the warehouse runs the service on the stores themselves, started by Testcontainers. The plugins give a module a test suite of its own for them, `integrationTest`, outside `check`, so the unit tests stay fast and need no Docker:

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

- The application's `postgres` profile defines its `DataSource`, so the host chooses PostgreSQL; the `clickhouse` profile turns the warehouse on.
- The stores create their schemas when they start (`wow.ddl: auto`), so a fresh container needs nothing else.
- `wow.log.partitions=4` keeps a small log: a test does not need 64 partitions.

## Check the contract

The Gradle plugin `me.ahoo.wow.service` merges the service's Manifest from its modules and compares it with the committed baseline, `manifest/baseline.json`, in `checkWowManifest`, part of `check`. A change that breaks a client within the baseline's compatibility line, the service's major version, fails the build, listing each breaking change and its path, and so does a change the baseline does not record yet, or a missing baseline. After an intended change, `updateWowManifestBaseline` records the new baseline, with the line instead of the version; it refuses a break within the line. Commit it with the change, so its review shows what the clients will see. [The Manifest](../concepts/contract#compatibility) explains what counts as breaking.

## Test across services

Services that call each other are tested together the same way: start each in the test's JVM, point one at the other's internal port, and drive the flow. The eShop's `:eshop-e2e` module runs the five services so, and in containers on Kafka and PostgreSQL in its integration tests.

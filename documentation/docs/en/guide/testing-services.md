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
)
```

- The fixture binds both ports on the loopback interface at free ports. On a loopback-only internal port and without a store bean, the node runs on the memory backend, which serves the snapshot and events queries from memory unless `wow.query.memory.enabled` is `false`.
- It shortens the timeouts a node waits on at its close (`wow.cluster.drain-timeout`, `wow.subscription.stop-timeout`), so the shutdown between test classes is quick; `properties` add the test's own, here `wow.timers.poll-interval=200ms`, which fires scheduled commands promptly.
- The application's own settings shorten what the test waits for: here the payment grace period, two seconds instead of thirty minutes.

The ordering's sagas send the inventory's commands, so the node needs a command transport. A test server needs nothing for it: its internal port is on the loopback interface, so the node runs for development, and `wow.transport=auto` keeps its command topics and published streams in its JVM unless the application configures a transport. A test that writes to the topics before the node starts, such as another context's published stream a saga reads from its beginning, or servers that share topics, gives them with `topics`:

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TestTopicsTest.kt#topics -->

```kotlin
@Test
fun `servers given the same topics should share them, which outlive each`() {
    val topics = InMemoryTopics(4)
    wowServer<BankServer>("dev", topics = topics).use { first ->
        first.bean<InMemoryTopics>().assert().isSameAs(topics)
        runBlocking { first.commands(Caller.ANONYMOUS).wait(OpenAccount("Bea"), "bea") }
        wowServer<BankServer>("dev", topics = topics).use { second ->
            second.bean<SourceTopics>().source.assert().isSameAs(topics)
        }
    }
    eventually("the account's stream published") { topics.messages(ACCOUNTS).size == 1 }
}
```

- The node takes the topics it is given in place of its own, with `wow.transport=memory`, and leaves them open when it closes, so they outlive each server that shares them.
- `server.bean<InMemoryTopics>()` is a node's topics, given or its own, to read what its sagas sent and its streams published.

A test then drives the service with its typed clients ([Send and read as a caller](#send-and-read-as-a-caller)): it waits to the end of a saga's chain where a wait plan reaches it, and polls for what none does. An order whose stock is confirmed and that is not paid cancels itself when its scheduled command is due:

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#grace -->

```kotlin
@Test
fun `an order not paid within its grace period should cancel itself`() {
    submit("order-unpaid")
    confirm("order-unpaid", "mug", "cap")
    order("order-unpaid").status.assert().isEqualTo(OrderStatus.STOCK_CONFIRMED)
    // The order cancels itself by a scheduled command of its own, which no command of the test's waits for.
    eventually("order-unpaid cancelled") { order("order-unpaid").status == OrderStatus.CANCELLED }
    order("order-unpaid").cancelReason.assert().contains("payment grace period")
    rejected { orders().wait(PayOrder("p-2", BigDecimal("29.00")), "order-unpaid") }
        .code.assert().isEqualTo("eshop-ordering.order.wrong-status")
}
```

## Send and read as a caller

The server's typed clients send the commands of the service's api module and read its states, as a caller, with no path and no JSON in the test: `commands(caller)` is a `CommandClient` and `queries(caller)` the caller's `Queries`, both on the server's public port. They are `wow-client`'s, the client a service calls its peers with, in user mode. The caller goes as the development identity headers (`Wow-User`, `Wow-Owner`, `Wow-Roles`, `Wow-Tenant`, `Wow-App`: `UserCredential.DEVELOPMENT`), which the host reads in a development profile when the application has no `CallerResolver` and CoSec is off ([Who the caller is](./http#who-the-caller-is)), so the test starts the server in one, `dev`. The quickstart's bank service, tested so:

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TypedClientsTest.kt#typed-clients -->

```kotlin
@Test
fun `the typed clients should send commands and read states as the caller`() {
    val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
    val commands = server.commands(alice)
    runBlocking {
        commands.wait(OpenAccount("Alice"), "alice").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.wait(Deposit(100), "alice").version.assert().isEqualTo(2)

        val account = server.queries(alice).snapshots(AccountState::class.java).state("alice")!!
        account.state.balance.assert().isEqualTo(100L)
        account.operator!!.id.assert().isEqualTo("alice")

        val rejected = assertThrows<CommandRejectedException> { commands.wait(Withdraw(150), "alice") }
        rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
    }
}
```

- `wait` answers with the result the command's wait plan ends with, which may wait to the end of a saga's chain. A rejection, by the aggregate or at admission (an invalid or unknown command, a forbidden caller), is a `CommandRejectedException` with its error code; any other problem a `WowProblemException`.
- A system command goes the same way, by its class, to the aggregate type the request names: a lifecycle command (`CommandRequest.of(DeleteAggregate(), id, type = …)`), a snapshot rebuild (`RebuildSnapshot.of(ids)`), a job's command (`DisableJob`, to `<context>.schedule` by the job's name).
- A server that authenticates its callers otherwise, behind CoSec or with a `CallerResolver` of its own, takes the credential it reads: `commands(caller, credential)` and `queries(caller, credential)`, a `UserCredential` that writes the caller as that server's headers.
- What no wait plan sees, a scheduled command that fires or a call another service received, a test polls with `eventually("what") { condition }`, every 100 milliseconds for at most 20 seconds by default. A condition that throws, an assertion among them, has not held yet; the failure says what was not reached, with the `detail` given (the services' output, say) and the condition's last failure as its cause.
- `server.http` stays for the wire itself: a route's shape, a header, a problem's members. A request names its caller as the typed clients do (`server.http.post(path, json, caller = alice)`, the development identity headers). `Answer.problem` reads an `application/problem+json` answer as a typed `Problem`: its status, code and violations, the rejected command's result, the aggregate's version, and what a retry may do; `Answer.result` reads a command's result, a success's or a problem's.

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TypedClientsTest.kt#problem -->

```kotlin
@Test
fun `a problem the server answered should be typed`() {
    server.http.post("/bank/account/carol/open-account", """{"owner":"Carol"}""").status.assert().isEqualTo(200)
    val invalid = server.http.post("/bank/account/carol/deposit", """{"amount":0}""").problem!!
    invalid.status.assert().isEqualTo(400)
    invalid.code.assert().isEqualTo("wow.command.invalid")
    val violation = invalid.violations.single()
    violation.path.assert().isEqualTo("amount")
    violation.code.assert().isEqualTo("positive")

    val rejected = server.http.post("/bank/account/carol/withdraw", """{"amount":1}""").problem!!
    rejected.status.assert().isEqualTo(409)
    rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
    rejected.result!!.status.assert().isEqualTo(CommandResult.Status.REJECTED)
    rejected.version.assert().isEqualTo(1)
    rejected.retry.assert().isEqualTo(Retry.NONE)
}
```

## Fake the services it calls

A saga that calls another service synchronously, a command to that service's internal port, is tested without the service: `fakePeer(context)` stands in for it, built from the contract its api module carries on the test's class path, and `wowServer(…, peers = listOf(peer))` points the server at it (`wow.peers.<context>.url`). The fake records each call it takes: the command, decoded to its class, the service that sent it, and the root command the call acts for, with its tenant, scope and operator. The basket's checkout calls the ordering so:

<!-- sample: fragment -->

```kotlin
fakePeer("eshop-ordering").use { ordering ->
    wowServer<BasketServer>("dev", peers = listOf(ordering)).use { basket ->
        val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
        runBlocking { basket.commands(alice).wait(CheckoutBasket, "alice") }
        val call = ordering.called<SubmitOrder>()
        call.service.assert().isEqualTo("eshop-basket-service")
        call.operator!!.id.assert().isEqualTo("alice")
        call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        (call.command as SubmitOrder).items.single().productId.assert().isEqualTo("mug")
    }
}
```

- `called<C>()` takes the first call of the command class `C` that it has not given back yet, waiting at most 20 seconds; `calls` lists them all.
- The fake answers each call as `answer { call -> … }` says. `PeerAnswer.succeeded(id, version)`, the default, commits the command at version 1 to the call's aggregate, or to a new one for a creation that names none. `PeerAnswer.rejected(code, message)` answers with the status the peer's contract gives the code: its error tables, else 422 for a domain rejection. `PeerAnswer.timedOut()` answers 504 `wow.wait.timeout`, which the caller may send again with the same request id.
- It refuses a request that names no service (`Wow-Service`) or no root command (`Wow-Call`) with 403 `wow.auth.forbidden`, as a peer's internal port does.

## Run it on real stores

The memory backend answers the snapshot and events queries from memory, by the in-memory engine, and writes no warehouse. A test of a store's schema, of its own query engine and of the warehouse runs the service on the stores themselves, started by Testcontainers. `me.ahoo.wow.service` registers a test suite of its own for them, `integrationTest`, which `check` does not run, so the unit tests stay fast and need no Docker: its sources go under `src/integrationTest/kotlin`, and `./gradlew integrationTest` runs them. The suite runs on JUnit Jupiter, against the module's classes and everything `testImplementation` gives; the plugin adds nothing else, so the build script declares what the tests need, Testcontainers among them:

```kotlin
testing {
    suites {
        named<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
                implementation("org.testcontainers:testcontainers-postgresql")
            }
        }
    }
}
```

The build script runs after its plugins, so it configures their suite (`named`) instead of registering one: a second suite of that name fails the build. A build that sets up its integration tests before the plugin applies, as a suite or a source set named `integrationTest` (from a convention plugin applied first, or from the root build), keeps them as they are, and the plugin registers nothing.

The tests start the containers and run the service on them:

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
)
```

- The application's `postgres` profile defines its `DataSource`, so the host chooses PostgreSQL; the `clickhouse` profile turns the warehouse on.
- The stores create their schemas when they start (`wow.ddl: auto`), so a fresh container needs nothing else.
- `wow.log.partitions=4` keeps a small log: a test does not need 64 partitions.

## Check the contract

The Gradle plugin `me.ahoo.wow.service` merges the service's Manifest from its modules and compares it with the committed baseline, `manifest/baseline.json`, in `checkWowManifest`, part of `check`. A change that breaks a client within the baseline's compatibility line, the service's major version, fails the build, listing each breaking change and its path, and so does a change the baseline does not record yet, or a missing baseline. After an intended change, `updateWowManifestBaseline` records the new baseline, with the line instead of the version; it refuses a break within the line. Commit it with the change, so its review shows what the clients will see. [The Manifest](../concepts/contract#compatibility) explains what counts as breaking.

## Test across services

Services that call each other are tested together the same way: start each in the test's JVM, point one at the other's internal port, and drive the flow. The eShop's `:eshop-e2e` module runs two services at a time so; its suite of all five runs each service in a JVM of its own, on PostgreSQL, Kafka and ClickHouse in containers.

---
title: Testing a V10 Service
description: The aggregateSpec test DSL in the kernel's test fixtures, saga and processor tests, binding index tests and server tests for Wow V10.
outline: deep
---

# Testing a V10 Service

## Where the test DSL lives

The aggregate test DSL ships in the **test fixtures of the kernel**, in the package `me.ahoo.wow.kernel.test`:

```kotlin
dependencies {
    testImplementation(testFixtures("me.ahoo.wow:wow-kernel:$wowVersion"))
    // Inside the Wow repository: testImplementation(testFixtures(project(":kernel")))
}
```

It is not the 9.x `wow-test` module, and its API is not 9.x's `AggregateSpec<A, S>` class to extend: it is a function, `aggregateSpec`, that a JUnit test calls.

## `aggregateSpec`

```kotlin
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    tenantId: String = "tenant",
    start: Instant = DEFAULT_START, // 2026-01-01T00:00:00Z
    block: AggregateSpec<S>.() -> Unit,
)
```

It runs the model on the real kernel with an in-memory log, for one aggregate (`model.type.ref(tenantId, id)`). Build the model with the factory the compiler generated, giving it the context and stub services:

```kotlin
class OrderSpec {
    private val model = orderModel("example", StubPricingService(mapOf("apple" to BigDecimal("2.50"))))

    @Test
    fun `a paid order should refuse a new address`() = aggregateSpec(model, id = "order-1") {
        given(orderCreated)
        given(OrderPaid(total, paid = true))
        whenCommand(ChangeAddress(office)) { expectRejected(OrderRejections.WRONG_STATUS) }
    }
}
```

A JUnit test function must return `Unit`. `aggregateSpec` returns `Unit`, so `fun test() = aggregateSpec(...)` is fine; a test whose expression body returns a value is skipped silently.

### Arranging and acting

| Call | Does |
| --- | --- |
| `given(vararg events, scope = null)` | Commits the events as one stream, as if an earlier command had produced them; `scope` leaves the aggregate in that owner and space |
| `whenCommand(command, requestId, expectedVersion, scope) { … }` | Sends the command body through the kernel, from a caller in `scope`, and checks its outcome |
| `advanceTime(duration) { … }` | Moves the clock on and sends the aggregate each command it scheduled for itself that is due by then; the block checks the last one |
| `now`, `ref`, `timer(key)` | The spec's time, the aggregate, and a timer it keeps |

Time stands still at `start` until `advanceTime` moves it: commit times and the due times of scheduled commands are read from it.

### Expectations

| Expectation | Passes when |
| --- | --- |
| `expectEvents(vararg events)` | The command committed exactly these events, in order |
| `expectEventTypes(vararg types)` | It committed events of exactly these classes, in order |
| `expectUnchanged()` | It was accepted and changed nothing (`Decision.NoOp`) |
| `expectRejected(code)` | It was rejected with this code |
| `expectState { … }` | The block's checks hold on the state after the committed events |
| `expectScope(scope)` | The committed stream leaves the aggregate in this scope |
| `expectScheduled(key, command, at, after, target)` | It scheduled this command under `key` |
| `expectScheduleCancelled(key)` | It cancelled the schedule `key` |
| `outcome` | The raw `CommandOutcome` (`Committed`, `Unchanged`, `Rejected`, …), for what the expectations do not cover, such as `(outcome as CommandOutcome.Committed).results` |

After a command that changed nothing (`expectUnchanged()`), `expectState` checks the state the aggregate is in.

The kernel checks that deciding never changes the state: a command function or after hook that assigns state fails the specification, even though the events are right.

### Scheduled commands

```kotlin
@Test
fun `an unpaid order should cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { expectScheduled("pay-timeout", CancelOrder("unpaid"), after = 30.minutes) }
    advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
}
```

## Testing sagas, projections and reactors

A processor is a plain class: call its handler with a `StateEvent` and the kernel's context for one execution.

```kotlin
private fun event(events: List<Any>) = StateEvent(
    ref = AggregateType("example", "order").ref("tenant", "order-1"),
    version = 1,
    commandId = "command-1",
    requestId = "request-1",
    commandType = "example.order.CreateOrder",
    commitTime = 1L,
    events = events,
    state = OrderState("order-1"),
)

@Test
fun `a placed order should reserve its stock`() {
    val event = event(listOf(orderCreated))
    val context = ExecutionSagaContext(ExecutionId("OrderSaga.onOrderCreated", event.ref, event.version), event)
    val commands = OrderSaga().onOrderCreated(event, context)
    // Handling the commit again derives the same requests.
    OrderSaga().onOrderCreated(event, context).map { it.requestId }.assert().isEqualTo(commands.map { it.requestId })
}
```

`ExecutionSagaContext`, `ExecutionProjectContext` and `ExecutionReactContext` (`me.ahoo.wow.kernel`) are the contexts the runtime gives a handler. For an `@OnRejected` handler, take its binding from the generated index and `rejectionContextOf`:

```kotlin
val saga = ModelProvider.load().single().processors(Dependencies.NONE).single { it.name == "OrderSaga.onOrderCreated" }
val onRejected = saga.rejectionHandlerOf(ReserveStock::class.java)!!
val compensation = onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected))
```

## Testing the binding index and the fragment

A test of the domain module can load what the compiler generated: `ModelProvider.load()` finds the module's binding index, `provider.models(Dependencies.of(stubs…))` builds its models, and `provider.fragment` names the fragment resource to read and check (its routes, events, errors, subscriptions). The shop's [`ShopDomainModelProviderTest`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/shop/shop-domain/src/test/kotlin/me/ahoo/wow/example/shop/domain/ShopDomainModelProviderTest.kt) does this.

## Testing the server

Start the application on the memory backend with random ports and call it over HTTP:

```kotlin
val context = SpringApplicationBuilder(CatalogServer::class.java, TestCallers::class.java)
    .run("--server.port=0", "--wow.internal.port=0", "--wow.internal.host=127.0.0.1", "--wow.backend=memory")
```

- Give the test a `CallerResolver` bean that reads a test header, so each request has an owner (the shop's `HeaderCallers` reads `X-User`).
- On the memory backend no query backend serves the snapshots, so their query routes answer 404. A test that queries them puts the in-memory reference backend on its class path: `testImplementation(testFixtures("me.ahoo.wow:wow-query:$wowVersion"))`.
- A service whose sagas send commands to another context does not start without a command transport: give the test Kafka in a container, or in-memory topics (see [Sending a command to another context](./programming-model.md#sending-a-command-to-another-context)).
- Lower `wow.cluster.drain-timeout`, `wow.subscription.stop-timeout` and `wow.wait.max-timeout` to make the context close quickly.
- Tests against PostgreSQL, MongoDB, Kafka or ClickHouse run in containers (Testcontainers), in the module's `integrationTest` source set.

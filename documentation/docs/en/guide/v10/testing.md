---
title: Testing a V10 Service
description: The aggregateSpec, sagaSpec, projectionSpec and reactorSpec test DSL of wow-test, binding index tests and server tests for Wow V10.
outline: deep
---

# Testing a V10 Service

## Where the test DSL lives

The test DSL ships in the V10 module **`wow-test`**, in the package `me.ahoo.wow.test`:

```kotlin
dependencies {
    testImplementation("me.ahoo.wow:wow-test:$wowVersion")
    // Inside the Wow repository: testImplementation(project(":test-dsl"))
}
```

It keeps the artifact name of the 9.x `wow-test` module, but not its API: it is not 9.x's `AggregateSpec<A, S>` class to extend, it is a set of functions a JUnit test calls, `aggregateSpec` for an aggregate, and `sagaSpec`, `projectionSpec` and `reactorSpec` for a processor.

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
| `givenState(state, version = 1, scope = Scope.NONE)` | Starts the aggregate in this state at this version, as if its first `version` streams had left it there; the first step of a spec |
| `givenState(version = 1, scope) { … }` | The same, with the aggregate's initial state changed by the block |
| `whenCommand(command, requestId, expectedVersion, scope) { … }` | Sends the command body through the kernel, from a caller in `scope`, and checks its outcome |
| `advanceTime(duration) { … }` | Moves the clock on and sends the aggregate each command it scheduled for itself that is due by then; the block checks the last one, and fails when none was due. Without a block it never fails |
| `expectNothingDue(within)` | Moves the clock on by `within`, and fails when a command the aggregate scheduled for itself is due by then (a cancelled schedule, say); nothing is sent |
| `ref(name)` | Marks the current point, for a later `fork(from = name)` |
| `fork(name, from = null) { … }` | Runs the block on a copy of the aggregate as it is now, or as it was at the ref `from` |
| `now`, `ref`, `timer(key)` | The spec's time, the aggregate, and a timer it keeps |

Time stands still at `start` until `advanceTime` moves it: commit times and the due times of scheduled commands are read from it.

### Expectations

| Expectation | Passes when |
| --- | --- |
| `expectEvents(vararg events)` | The command committed exactly these events, in order |
| `expectEventTypes(vararg types)` | It committed events of exactly these classes, in order, given as `KClass` (`OrderPaid::class`) or `Class` |
| `expectEvent<E> { … }` | It committed exactly one event, an `E`, and the block's checks hold on it |
| `expectUnchanged()` | It was accepted and changed nothing (`Decision.NoOp`) |
| `expectRejected(code) { … }` | It was rejected with this code; the optional block checks the `Rejection`, its `message` say |
| `expectError<T> { … }` | Its decide function threw a `T` (a failed `require`, say); the optional block checks the exception |
| `expectNoError()` | Its decide function threw nothing and the command was not rejected |
| `expectState { … }` | The block's checks hold on the state after the committed events |
| `expectScope(scope)` | The committed stream leaves the aggregate in this scope |
| `expectScheduled(key, command, at, after, target)` | It scheduled this command under `key` |
| `expectScheduleCancelled(key)` | It cancelled the schedule `key` |
| `outcome`, `error` | The raw `CommandOutcome` (`Committed`, `Unchanged`, `Rejected`, …), for what the expectations do not cover, such as `(outcome as CommandOutcome.Committed).results`; and what the decide function threw |

After a command that committed nothing (`expectUnchanged()`, `expectRejected(…)` or `expectError<…>()`), `expectState` checks the state the aggregate is in.

A rejection is a value (`Decision.reject`), checked by its code; an exception the decide function throws is a failure, checked by its type. An exception that no `expectError` checks fails the spec as it was thrown.

The kernel checks that deciding never changes the state: a command function or after hook that assigns state fails the specification, even though the events are right.

### Starting from a state

`givenState` starts the aggregate where a long history would have left it, without replaying that history (9.x's `givenState`). The commands after it are decided on that state, and their streams continue from its version:

```kotlin
@Test
fun `a payment given as succeeded should be refunded`() = aggregateSpec(model) {
    givenState(version = 2) {
        onSourcing(PaymentRequested(order, amount))
        onSourcing(PaymentSucceeded(transaction))
    }
    whenCommand(RefundPayment("refused"), expectedVersion = 2) { expectEvents(PaymentRefunded("refused")) }
}
```

The state is copied through its JSON, as a snapshot would be: it must serialize, and changing the object afterwards changes nothing.

### Forks

Scenarios that share a prefix fork from it instead of repeating it. Each fork runs on its own copy of the aggregate (its log, timers and clock), so what it does stays in the fork, and the spec goes on from where it was. `ref(name)` marks a point to fork from later:

```kotlin
@Test
fun `a requested payment should end confirmed or failed`() = aggregateSpec(model) {
    whenCommand(RequestPayment(order, amount)) { expectEventTypes(PaymentRequested::class) }
    ref("requested")
    fork("confirmed") {
        whenCommand(ConfirmPayment(transaction)) { expectEventTypes(PaymentSucceeded::class) }
    }
    fork("failed") {
        whenCommand(FailPayment("declined")) { expectEventTypes(PaymentFailed::class) }
        whenCommand(ConfirmPayment(transaction)) {
            expectRejected(PaymentRejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
        }
    }
    fork(from = "requested") { /* … */ }
}
```

A failure inside a fork names it: `In fork "failed": Expected rejection …`. Forks nest, and their names join (`outer > inner`).

### Scheduled commands

```kotlin
@Test
fun `an unpaid order should cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { expectScheduled("pay-timeout", CancelOrder("unpaid"), after = 30.minutes) }
    advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
}

@Test
fun `a paid order should not cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { }
    whenCommand(payOrder) { expectScheduleCancelled("pay-timeout") }
    expectNothingDue(within = 30.minutes)
}
```

## Testing sagas, projections and reactors

A processor is tested through the handlers the compiler generated for it: for each `@Saga`, `@Projection` or `@Reactor` class, a function `<processor>Handlers(dependencies)` in its package (`orderSagaHandlers` for `OrderSaga`). It constructs that processor only, with its own services from `dependencies`, so a test needs none of the services the module's other processors take.

### `sagaSpec`

```kotlin
class OrderSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", "order-1")
    private val handlers = orderSagaHandlers(Dependencies.NONE)

    @Test
    fun `a placed order should reserve its stock`() = sagaSpec(handlers) {
        whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
            expectCommands(ReserveStock("order-1", 2), ReserveStock("order-1", 1))
            // Handling the commit again derives the same requests.
            expectDeterministic()
        }
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
        whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
            whenRejected(commands.last(), "inventory.stock.insufficient", "Only 0 of pear are available.") {
                expectCommand(CancelOrder("pear cannot be reserved: Only 0 of pear are available."), to = order)
            }
        }
    }
}
```

| Call | Does |
| --- | --- |
| `whenEvent(event, handler = null) { … }` | Hands the commit to each handler of the saga that reads it, as their subscriptions would: those that name its aggregate type in `aggregates`, or that name none and take its state type. `handler` names the only one to call (`"onOrderCreated"`) |
| `whenRejected(command, code, message, commandType) { … }` | Hands the rejection of a command this spec derived to the saga's `@OnRejected` handler of it, as the runtime does when the target refuses it |
| `whenRejected(rejected, CommandClass::class) { … }` | The same for a `CommandRejected` built by hand |

Each call of a handler gets a fresh `ExecutionSagaContext`, as the runtime gives each handling of a commit, so the ids the saga derives are the same on every call for the same commit. A test that builds the context itself builds one per call: a context numbers the commands it derives, so a second call with the same one derives other ids.

| Expectation | Passes when |
| --- | --- |
| `expectCommands(vararg bodies)` | The handlers derived commands of exactly these bodies, in order |
| `expectCommandTypes(vararg types)` | They derived commands of exactly these classes, in order |
| `expectCommand(body, to = null)` | They derived exactly one command, of this body, to `to` when given; it returns the command |
| `expectCommand<C> { … }` | They derived exactly one command, a `C`; the block checks the `Command<C>` (its `ref`, `body`, `causation`) |
| `expectNoCommand()` | They derived nothing |
| `expectScheduled(key, command, at, after, target)` | They scheduled this command under `key`; `after` counts from the commit time |
| `expectScheduleCancelled(key, target)`, `expectNoSchedule()` | They cancelled the schedule `key`; they changed no schedule |
| `expectHandledBy(vararg functions)` | Exactly these handlers were called, in order |
| `expectDeterministic()` | Handling the commit, or the rejection, again with a fresh context derives the same commands, ids included, and the same schedules |
| `expectError<T> { … }`, `expectNoError()` | A handler threw a `T`; none threw |
| `commands`, `schedules`, `handled`, `error` | The raw commands, schedules (`SagaSchedule`), handler names and exception |

`stateEvent(ref, state, vararg events, version = 1, …)` builds the commit; its command's ids default to `command-<version>` and `request-<version>`, its commit time to `DEFAULT_START`, and `commandType`, `scope`, `causation`, `headers` and `operator` can be given by name. A saga that reads a stream another context publishes gets that context's events by name: build them with `publishedEvent(name, body, revision)`, and the state as the saga's view of it:

```kotlin
whenEvent(stateEvent(order, OrderView(total), publishedEvent("eshop-ordering.order.OrderStockConfirmed"))) {
    expectCommand(RequestPayment("alice-1", total), to = payment)
}
```

### `projectionSpec` and `reactorSpec`

A projection or a reactor writes to a port (a repository, a mail service) that the test fakes: give the fake to its generated handlers, feed them commits, and check what the fake then holds.

```kotlin
@Test
fun `a placed order should show in the order list`() {
    val views = InMemoryOrderViews()
    projectionSpec(orderListProjectionHandlers(Dependencies.of(views))) {
        whenEvent(stateEvent(order, state, orderCreated)) {
            expectHandledBy("onOrder")
            views["order-1"]!!.status.assert().isEqualTo("CREATED")
        }
    }
}
```

`reactorSpec` does the same for a reactor. `whenEvent`, `expectHandledBy`, `expectError` and `expectNoError` work as they do for a saga.

### The contexts themselves

`ExecutionSagaContext`, `ExecutionProjectContext` and `ExecutionReactContext` (`me.ahoo.wow.kernel`) are the contexts the runtime gives a handler, and `rejectionContextOf` gives the one of an `@OnRejected` handler. A test that calls a processor's function directly builds them, one per call.

## Testing the binding index and the fragment

A test of the domain module can load what the compiler generated: `ModelProvider.load()` finds the module's binding index, `provider.models(Dependencies.of(stubs…))` builds its models, and `provider.fragment` names the fragment resource to read and check (its routes, events, errors, subscriptions). `provider.processors(dependencies)` constructs every processor of the module, so it needs the services of all of them. The eShop ordering's [`OrderingDomainModelProviderTest`](https://github.com/Ahoo-Wang/Wow/blob/v10/example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/OrderingDomainModelProviderTest.kt) does this.

## Testing the server

Start the application on the memory backend with random ports and call it over HTTP:

```kotlin
val context = SpringApplicationBuilder(CatalogServer::class.java, TestCallers::class.java)
    .run("--server.port=0", "--wow.internal.port=0", "--wow.internal.host=127.0.0.1", "--wow.backend=memory")
```

- Give the test a `CallerResolver` bean that reads a test header, so each request has an owner (the shop's `HeaderCallers` reads `X-User`).
- On the memory backend no query backend serves the snapshots unless `wow.query.memory.enabled` is `true`; without it their query routes answer 404.
- A service whose sagas send commands to another context does not start without a command transport: give the test Kafka in a container, or in-memory topics (see [Sending a command to another context](./programming-model.md#sending-a-command-to-another-context)).
- Lower `wow.cluster.drain-timeout`, `wow.subscription.stop-timeout` and `wow.wait.max-timeout` to make the context close quickly.
- Tests against PostgreSQL, MongoDB, Kafka or ClickHouse run in containers (Testcontainers), in the module's `integrationTest` test suite. The `me.ahoo.wow` plugin registers it (DX gap G17): JUnit Jupiter, the module's classes and everything `testImplementation` gives, outside `check`. Put the sources under `src/integrationTest/kotlin`, add Testcontainers, and run `./gradlew integrationTest`:

```kotlin
dependencies {
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
```

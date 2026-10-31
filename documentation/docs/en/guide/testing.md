---
title: Test Domain Code
description: Test aggregates, sagas, projections, reactors and scheduled jobs with the wow-test DSL, in the terms the model is written in, without a server.
outline: deep
---

# Test Domain Code

Domain code is tested in the terms it is written in: given what has happened, when a command arrives, expect these events and this state. The `wow-test` DSL runs your aggregates on the real kernel, with an in-memory log, and your processors through the bindings the compiler generated for them. A domain test needs no server, no database and no broker. This page shows how to test each kind of domain code.

## Set up

Add the artifact `me.ahoo.wow:wow-test` to your test dependencies; its version comes from the Wow BOM. The DSL is a set of functions in the package `me.ahoo.wow.test` that a JUnit test calls: `aggregateSpec` for an aggregate; `sagaSpec`, `projectionSpec` and `reactorSpec` for a processor; `scheduleSpec` for a scheduled job. Each has a twin ending in `Tests`, which turns the same specification into a tree of tests. A processor spec that reads through the query API over snapshots in memory also takes `me.ahoo.wow:wow-test-query` ([Reads](#reads)).

A JUnit test function must return `Unit`. The spec functions return `Unit`, so `fun test() = aggregateSpec(...)` is fine; the `Tests` twins return the tree, for a `@TestFactory`.

Coming from the 9.x `AggregateSpec` and `SagaSpec`: [Migrate the Source](../migrate/source#tests) maps each 9.x construct to its V10 form, and consecutive `whenCommand` calls now chain.

## Aggregates

The quickstart's bank account, tested:

<!-- sample: examples/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class AccountSpec {
    private val model = accountModel()

    @Test
    fun `opening an account names its owner`() = aggregateSpec(model, id = "alice") {
        whenCommand(OpenAccount("Alice")) {
            expectEvents(AccountOpened("Alice"))
            expectState { owner.assert().isEqualTo("Alice") }
        }
    }

    @Test
    fun `a deposit adds to the balance`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"))
        whenCommand(Deposit(100)) {
            expectEvents(Deposited(100))
            expectState { balance.assert().isEqualTo(100) }
        }
    }

    @Test
    fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"), Deposited(100))
        whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
        whenCommand(Withdraw(30)) {
            expectEvents(Withdrawn(30))
            expectState { balance.assert().isEqualTo(70) }
        }
    }
}
```

`accountModel()` is the model factory the compiler generated for the `Account` aggregate. A factory takes the context's name, which defaults to the module's, and the domain services the command functions need; pass stubs of them. `aggregateSpec` runs one aggregate, `id` in `tenantId` (`aggregate-1` in `tenant` by default).

### Arrange and act

| Step | Does |
| --- | --- |
| `given(vararg events, scope = null, operator = null, commitTime = now)` | Commits the events as one stream, as if an earlier command had produced them; `scope` leaves the aggregate in that owner and space. `operator` and `commitTime` are the facts of the commit a sourcing function reads ([Read the commit](./aggregates#read-the-commit)). |
| `givenState(state, version, scope)`, `givenState(version, scope) { … }` | Starts the aggregate in a state at a version, without replaying a history; the block form changes the initial state. The first step of a spec. |
| `givenRecords(vararg records, scope, operator, commitTime)` | Commits stored records as one stream, as the log holds them: a history the model's classes can no longer write, such as an event the aggregate retired, whose record the kernel skips, or a record of an older revision, read through its upgrades. |
| `givenReservation(namespace, key, owner)` | Gives a reserved key to another aggregate of the same type, so a command that reserves it is refused. |
| `givenCommands(vararg commands)` | Sends the commands as the scenario's setup; one that is rejected or throws fails the spec, naming it. |
| `whenCommand(command, requestId, expectedVersion, caller, scope, …) { … }` | Sends the command through the kernel and checks its outcome in the block. The `caller` (the spec's `aggregateSpec(model, caller = …)` by default) gives its scope, operator and application, as the command gateway derives them; `scope` names another scope the caller acts in. |
| `asCaller(caller) { … }` | Sends the block's commands as another caller. |
| `advanceTime(duration) { … }` | Moves the clock on and sends the aggregate each command it scheduled for itself that is due by then; the block checks the last one. |
| `expectNothingDue(within)` | Moves the clock on and fails if a command the aggregate scheduled for itself is due by then. |
| `ref(name)`, `fork(name, from) { … }` | Marks a point; runs a scenario on a copy of the aggregate, now or from a marked point. |
| `holds(namespace, key)` | Whether the aggregate holds a reserved key. |
| `now`, `timer(key)` | The time the spec is at; the timer the aggregate keeps under a schedule's key, in whatever status, or null. |

Time stands still at `start`, 2026-01-01T00:00:00Z by default, until `advanceTime` moves it: a decision reads it as `scope.now`, and its commit is recorded at it. Ids a decide function takes are sequential: `id-1`, `id-2`, and so on.

### Expect

| Expectation | Passes when |
| --- | --- |
| `expectEvents(vararg events)` | The command committed exactly these events, in order. |
| `expectEventTypes(vararg types)` | It committed events of exactly these classes, in order. |
| `expectEvent<E> { … }` | It committed exactly one event, an `E`, and the block's checks hold on it. |
| `expectEventEnvelope<E> { … }` | The same, with the stream's envelope beside the event (`body`): `aggregateId`, `tenantId`, `version`, `commandId`, `requestId`, `headers`. |
| `expectEventCount(n)` | It committed exactly `n` events. |
| `expectUnchanged()` | It was accepted and changed nothing. |
| `expectVersion(n)` | The aggregate is at version `n` after the command: the committed stream's, or the one it stayed at when the command appended nothing. |
| `expectDeleted()` | The command committed the aggregate's deletion. |
| `expectRejected(code) { … }` | It was rejected with this error code, returned or thrown (`reject`, `require`, `check`); the block checks the rejection. |
| `expectError<T> { … }` | Its decide function threw a `T`. |
| `expectNoError()` | Nothing was thrown, and the command was not rejected. |
| `expectState { … }` | The block's checks hold on the state after the command. |
| `expectState(expected)` | The state after the command equals `expected`, field by field; a mismatch lists each difference. |
| `expectResult(key, value)`, `results` | It committed, and its decide function put this value for the caller (`putResult`); `results` reads them all, and fails for a command that committed nothing, since only a committed command answers with results. |
| `expectScope(scope)`, `expectTags(tags)` | The aggregate is in this scope, or has exactly these tags, after the command. |
| `expectScheduled(key, command, rule)`, `expectScheduleCancelled(key)` | It scheduled this command under `key`, or cancelled the schedule. |
| `fork(name) { … }`, `fork(name, model) { … }` | Branches from this command's outcome ([A tree of tests](#a-tree-of-tests)). |

`error` is what the decide function threw, null when it returned a decision. A check that fails ends the block, so the forks after it do not run.

A rejection is a value, checked by its code. A rejection with events commits them, so `expectRejected` goes with `expectEvents`, `expectVersion` and `expectState` to check them and the state they left; a creation rejected with events fails the test, naming the command, since its events could never be committed. An exception decide throws is a failure, checked by its type; one that no `expectError` checks fails the test as it was thrown. After a command that committed nothing, `expectState` checks the state the aggregate is in.

**Decide must not change the state.** In a spec, the kernel compares the state before and after each command function and after hook, and fails the test when one of them changed it directly. A change that does not go through an event would drift from the log at run time; the test catches it.

### Start from a state

When a scenario starts where a long history would have left the aggregate, start from that state instead of replaying the history. The commands that follow are decided on it, and their streams continue from its version:

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#given-state -->

```kotlin
@Test
fun `a payment given as succeeded should be refunded`() = paymentSpec {
    givenState(version = 2) {
        onSourcing(PaymentRequested(ORDER, AMOUNT))
        onSourcing(PaymentSucceeded(TRANSACTION))
    }
    whenCommand(RefundPayment(REFUSED), expectedVersion = 2) {
        expectEvents(PaymentRefunded(REFUSED))
        expectState { transactionId.assert().isEqualTo(TRANSACTION) }
    }
}
```

The state is copied through its JSON, as a snapshot would be, so it must serialize.

### Fork scenarios that share a beginning

Scenarios that share a beginning fork from it instead of repeating it. Each fork runs on its own copy of the aggregate: its log, timers, reservations, ids and clock. What a fork does stays in the fork, and nothing before the fork runs again, so a domain service decide called earlier is not called again. `ref` marks a point to fork from later:

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#fork -->

```kotlin
@Test
fun `a requested payment should end confirmed and refunded, or failed`() = paymentSpec {
    givenCommands(RequestPayment(ORDER, AMOUNT))
    ref("requested")
    fork("confirmed") {
        givenCommands(ConfirmPayment(TRANSACTION))
        whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
    }
    fork("failed") {
        givenCommands(FailPayment(DECLINED))
        whenCommand(ConfirmPayment(TRANSACTION)) {
            expectRejected(Payment.Rejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
        }
    }
    // The forks left the payment as requested.
    whenCommand(RefundPayment(REFUSED)) {
        expectRejected(Payment.Rejections.WRONG_STATUS)
        expectState { status.assert().isEqualTo(PaymentStatus.REQUESTED) }
    }
    fork(from = "requested") {
        whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
    }
}
```

A failure inside a fork names it, with the step and the command it checked: `In fork "failed", whenCommand(ConfirmPayment) (command-4): …`. Forks nest, and their names join. Outside a fork a failing check names its step and command alike, and `expectEvents` and `expectState(expected)` list each difference by its path (`[0].quantity: expected 3, was 2`) instead of printing both values.

A fork can also run with other services: give it the same model built with another domain service, by the factory the compiler generated, so that one branch sees the service answer otherwise. The copy of the aggregate is read by that model, which must be of the same aggregate type:

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#fork-model -->

```kotlin
// One branch runs with another payment policy: the same model the compiler generated, built with other
// services, on a copy of the order so far.
fork("express", model = orderModel(paymentPolicy = PaymentPolicy(EXPRESS_GRACE))) {
    whenCommand(ConfirmOrderStock(CAP)) {
        expectScheduled(
            Order.PAYMENT_GRACE_PERIOD,
            CancelOrder(Order.PAYMENT_TIMEOUT),
            ScheduleRule.after(EXPRESS_GRACE),
        )
    }
}
```

### A tree of tests

`aggregateSpecTests` runs the same kind of specification as a tree for a JUnit `@TestFactory`: each command is a test, and each fork a container of the tests it runs. A failing check fails only its own test; the other steps and forks still run and report on their own:

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#tree -->

```kotlin
/**
 * The same lifecycle as a tree of tests: each command is a test and each fork a container, forked from the checks
 * of the request, so a failing branch is reported on its own and the others still run.
 */
@TestFactory
fun `a payment's lifecycle`() = aggregateSpecTests(paymentModel(), id = ORDER) {
    whenCommand(RequestPayment(ORDER, AMOUNT)) {
        expectEventTypes(PaymentRequested::class)
        fork("confirmed") {
            whenCommand(ConfirmPayment(TRANSACTION)) {
                expectEventTypes(PaymentSucceeded::class)
                fork("refunded") {
                    whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
                    whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
                }
            }
            whenCommand(FailPayment("too late")) { expectRejected(Payment.Rejections.WRONG_STATUS) }
        }
        fork("failed") {
            whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
            whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
        }
    }
    whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
}
```

A fork inside a command's checks branches from that command's outcome.

### Scheduled commands

A command function that schedules a command for its aggregate is tested by moving the clock. `advanceTime` sends each command that is due, as the runtime's timers would:

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#schedule -->

```kotlin
@Test
fun `an unpaid order should cancel itself once its grace period has passed`() = orderSpec {
    given(submitted, OrderAwaitingValidation, OrderStockItemConfirmed(MUG))
    whenCommand(
        ConfirmOrderStock(CAP)
    ) { expectScheduled(Order.PAYMENT_GRACE_PERIOD, CancelOrder(Order.PAYMENT_TIMEOUT), ScheduleRule.after(GRACE)) }
    advanceTime(GRACE) {
        expectEvents(OrderCancelled(Order.PAYMENT_TIMEOUT))
        expectState {
            status.assert().isEqualTo(OrderStatus.CANCELLED)
            cancelReason.assert().isEqualTo(Order.PAYMENT_TIMEOUT)
        }
    }
}
```

A cron rule steps through its slots as the runtime does: moving past several slots sends only the latest, unless the rule catches up.

## Sagas

A processor is tested through the handlers the compiler generated for it: for each `@Saga`, `@Projection` or `@Reactor` class, a function `<processor>Handlers(services…)` in its package. It constructs only that processor, with the services the test passes (`orderSummaryProjectionHandlers(summaries = …)`; none for a processor that takes none).

`sagaSpec` hands a commit to the saga's handlers that read it, as their subscriptions would, and checks the commands they send. `givenAggregate(model, id, vararg history)` gives the aggregate: its model sources the state and the version from the history, one commit per event, each committed at 2026-01-01T00:00:00Z. `whenEvent(events, by = Command::class)` hands over its next commit, whose command type the model names. A peer's state, its api's generated view (`OrderStateView`), which no model of the module sources, is built by hand with `stateEvent(ref, state, vararg events, version, …)`:

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#saga -->

```kotlin
@Test
fun `an order awaiting validation should reserve each product's stock in the inventory`() = sagaSpec(handlers) {
    // The order's model sources its state from its history; the commit after it is version 2.
    val order = givenAggregate(model, ORDER, OrderSubmitted(items))
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
        expectCommands(
            stock.ref(order.ref.tenantId, "mug") to ReserveStock(ORDER, 5),
            stock.ref(order.ref.tenantId, "cap") to ReserveStock(ORDER, 1),
        )
        // Handling the commit again derives the same requests, which the inventory decides once: the spec
        // gives each handling a fresh context, as the runtime does.
        expectDeterministic()
    }
}
```

`expectDeterministic()` handles the commit again, with a fresh context as the runtime gives each handling, and checks that the saga sends the same commands with the same ids. That is what makes a redelivered commit safe: the target decides each request once. A projection or a reactor has no such check: handling a commit again would run its side effects again.

### Steps

`sagaSpec`, `projectionSpec` and `reactorSpec` take the same steps. Each `when…` step's checks are optional: without a block, a step only fails when a handler throws.

| Step | Does |
| --- | --- |
| `givenAggregate(model, id, vararg history, tenantId, scope)` | Gives the aggregate whose next commits `whenEvent` hands over, at the state and version its history leaves it in. A fork advances copies of its own. |
| `whenEvent(vararg events, on, by, headers, operator, handler) { … }` | Commits the events on the given aggregate (`on`, by default the only one) and hands the commit to the handlers that read it. `by` is the command class that committed them, which names the commit's command type as the model does; `handler = "onX"` calls only that handler. |
| `whenEvent(stateEvent, handler) { … }` | Hands a commit built by hand over ([Peer states and published streams](#peer-states-and-published-streams)). |
| `whenRedelivered(on, handler) { … }` | Hands the latest commit over again, as a subscription that delivers each commit at least once does after a retry or a rebalance. An idempotent handler does the same again, or nothing. |
| `whenDeliveredLate(version, on, handler) { … }` | Hands an older commit over again after newer ones, as another partition's lag or a replay delivers it. A handler that keeps the newest version leaves what the later commits wrote. |
| `whenRejected(command, code, message) { … }`, `whenCommitted(command, version, aggregateId, duplicate) { … }` | Hands the rejection, or the success receipt, of a command the spec derived back to the saga ([Rejections and receipts](#rejections-and-receipts)). |
| `answerCalls { … }` | Answers the saga's synchronous calls ([Synchronous calls](#synchronous-calls)). |
| `givenQueries(queries)` | Serves the handlers' reads ([Reads](#reads)); without it, a handler that reads fails. |
| `fork(name) { … }` | Runs a scenario on a copy of the spec as it is now; inside a step's checks, on a copy as that handling left it. In `sagaSpecTests` each step is a test and each fork a container. |

### Which handlers a commit reaches

A commit reaches each handler that reads it, as its subscription would: one that names the commit's aggregate type in `aggregates`, or names none and takes its state type. A handler of one event type, such as `StateTypedEvent<S, E>`, skips a commit without an event of that type, as the runtime does, so a commit every handler skips is handled by none, which `expectHandledBy()` with no names checks. A commit that no handler reads at all fails the spec: it is a mistake of the test, not a skip.

### Rejections and receipts

A command the saga sent can be rejected by its target, or committed with a receipt. `whenRejected` and `whenCommitted` hand that outcome back to the saga's `@OnRejected` or `@OnCommitted` handler, as the runtime does. The rejection's code is one the target may answer: its contract's generated constant, or a `FrameworkCodes` one; another code fails:

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#rejected -->

```kotlin
@Test
fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
    val order = givenAggregate(model, ORDER, OrderSubmitted(items))
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
        val cap = commands.single { it.ref.id == "cap" }
        whenRejected(cap, EshopInventoryContract.Stock.Errors.INSUFFICIENT, "Only 0 of cap are available, not 1.") {
            expectHandledBy("onReserveRejected")
            expectCommand(
                CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."),
                to = order.ref
            )
            // The same receipt again derives the same request.
            expectDeterministic()
        }
    }
}
```

### Synchronous calls

A saga that calls another service synchronously gets its answers from `answerCalls`, as the target's command gateway would give them:

- `committedResult(command)`, the default, committed at version 1;
- `rejectedResult(command, code)`, which the handler gets as a `CommandRejectedException`;
- `failedResult(command, code)`, which it gets as a `WowProblemException`;
- an exception thrown from the block, as a target that is down throws it.

A rejection the handler lets through goes to the saga's `@OnRejected` handler of that command, whose commands the aborted handling is left with. The checkout saga has none, so the runtime parks the basket at once, and the spec says so with `expectParked`. `expectCalls` and `calls` check what it called:

<!-- sample: examples/eshop/basket/basket-domain/src/test/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSagaTest.kt#calls -->

```kotlin
@Test
fun `an order the ordering context refuses should park the checkout`() = sagaSpec(handlers) {
    answerCalls { rejectedResult(it, EshopOrderingContract.Order.Errors.WRONG_STATUS) }
    basket()
    whenEvent(checkedOut, by = CheckoutBasket::class, operator = alice) {
        // The saga lets the call's rejection through: a business outcome, so the runtime parks the basket at once.
        val rejected = expectParked().cause as CommandRejectedException
        rejected.code.assert().isEqualTo(EshopOrderingContract.Order.Errors.WRONG_STATUS)
    }
}
```

### Reads

A saga whose handlers read through the query API gets its reads from `givenQueries`, a fake of the read side, or from the read side over snapshots in memory that `wow-test-query` gives. That read side is the query engine of the Manifest the modules on the class path declare: a handler's filters, scope and tenant policies and budgets apply, as on a node. `givenSnapshots(model, id, *events)` holds the snapshot of an aggregate in the state its events leave it in, at the version of its last event: its model replays them as `givenAggregate` does, and refuses an event it has no sourcing function for. `givenSnapshots(type, id to state)` holds a state as it is given, for a state no model of the test sources. Prefer the events where the model is at hand: a state written as a JSON map skips the state's schema, so a misspelled field is dropped without a word and the state reads back with that field's default. A spec that reads several aggregates gives one `givenQueries(memoryQueries { snapshot(model, id, *events); … })`, which also takes `snapshots(type, id to state, …)`; each `givenSnapshots` replaces the queries given before.

### Failures, retries and parking

A handler that throws fails the spec unless the step's checks expect it: `expectError<T>`, `expectRetried` or `expectParked`. The spec decides what the runtime would do with the failure as the runtime's subscriptions do:

- A rejection the handling met parks the aggregate at once, without a retry: the rejection of a call the handler let through, or of a derived command that no `@OnRejected` handler takes ([Rejections and receipts](#rejections-and-receipts)). A rejection is a business outcome, which no retry changes.
- Any other failure is classified: the handler's `@Retry(recoverable, unrecoverable)` first, then the failure classifiers of the modules on the class path, then the built-in rules ([How a failure is classified](./failures#how-a-failure-is-classified)). An unrecoverable failure parks at once; a downstream that is down or pushes back is retried without spending the budget; any other is retried until the handling has spent `@Retry(maxAttempts)`, 5 by default.
- A handling that runs longer than its `@Retry(timeout)` is cancelled and counts as a failed attempt. That time is real, not the spec's clock.

`expectRetried(attempt)` passes when the runtime retries the handling after that attempt, the first by default; `expectParked(attempt)` when it parks it then. Both return what was thrown. The spec does not wait out the backoff between attempts, and it uses neither the parked retries nor the application's own `FailureClassifier` beans: test those beside the classifier. [Test a failure](./failures#test-it) shows a reactor retried and then parked.

### Saga expectations

| Expectation | Passes when |
| --- | --- |
| `expectCommands(vararg bodies)`, `expectCommandTypes(vararg types)` | The handlers sent commands of exactly these bodies, or classes, in order. |
| `expectCommand(body, to)`, `expectCommand<C> { … }` | They sent exactly one command, of this body and to this aggregate, or a `C` whose body the block checks. |
| `expectCommandEnvelope<C> { … }` | They sent exactly one command, a `C`, which the block checks with its envelope: the aggregate it goes to (`ref`), its ids, scope, request facts and operator beside its `body`. |
| `expectCommandCount(n)` | They sent exactly `n` commands. |
| `expectNoCommand()` | They sent nothing. |
| `expectCalls(vararg bodies)`, `expectNoCall()` | They called exactly these commands synchronously, or none. |
| `expectScheduled(…)`, `expectScheduleCancelled(…)`, `expectNoSchedule()` | They scheduled or cancelled a command, or changed no schedule. |
| `expectHandledBy(vararg functions)` | Exactly these handlers were called, in order. |
| `expectDeterministic()` | Handling the commit, or the outcome, again sends the same commands, ids included. A saga's only. |
| `expectError<T> { … }`, `expectNoError()` | A handler threw a `T`; none threw. |
| `expectRetried(attempt)`, `expectParked(attempt)` | The handling failed, and the runtime retries it, or parks it, after that attempt ([Failures, retries and parking](#failures-retries-and-parking)). |

`commands`, `calls`, `schedules`, `handled` and `error` give the raw results for checks the table does not cover.

### Peer states and published streams

A peer's state, which no model of the module sources, is a commit built by hand with `stateEvent(ref, state, vararg events, version, …)`, as the first saga sample does. A saga that reads another context's published stream gets that context's events; build one by name with `publishedEvent(name, body, revision)` when the other context's classes are not on the test's classpath. `rejectedReceipt` and `committedReceipt` build the receipt of a command another handler derived, for `whenRejected` and `whenCommitted` of a receipt.

## Projections and reactors

A projection or a reactor writes to a port, such as a repository or a mail service, that the test fakes. Pass the fake to the processor's generated handlers, feed them commits with `whenEvent`, and check what the fake then holds. `projectionSpec` and `reactorSpec` take the same steps as `sagaSpec` and the expectations that apply to them: `expectHandledBy`, `expectError`, `expectNoError`, `expectRetried` and `expectParked`.

A subscription delivers each commit at least once, and a commit of another partition may arrive after newer ones. `whenRedelivered()` hands the latest commit over again, which tests that a handler is idempotent; `whenDeliveredLate(version)` hands an older one over after newer ones, which tests that a read model keeps the newest version. [Test a projection](./projections#test-them) shows both.

## Scheduled jobs

A saga's `@OnSchedule` handlers are tested with `scheduleSpec(handlers, start)`, against the real job aggregate, in the context the generated handlers name. Time stands still at `start` until `advanceTime` moves it.

| Step | Does |
| --- | --- |
| `enable(job, tenantId, rule)`, `reschedule(job, tenantId, rule)`, `disable(job, tenantId)` | Enables an instance of a job by its default rule, or another, as `wow.schedule.Enable` does; overrides its rule; disables it. A `SHARDED` job's instance also takes its `partition`. |
| `advanceTime(duration) { … }` | Moves the time on: each instance runs the slots then due, and its job's handlers get each tick. The block checks what they did with all of them together, with a saga's expectations, and fails when no slot was due; without a block, nothing is checked. |
| `whenTick(tick) { … }` | Hands a tick to its job's handlers directly, without the job aggregate. |
| `ticks`, `now`, `instanceOf(job, tenantId)` | The ticks handed over so far, the time, and an instance's state. |
| `answerCalls { … }`, `givenQueries(queries)` | As in a saga spec. |

Moving past several slots at once runs only the latest, and the tick counts the others in `skipped`, unless the job's rule catches up. A failing tick is retried or parked under its job's `@Retry`, as [Failures, retries and parking](#failures-retries-and-parking) describes. `scheduleSpecTests` makes each `advanceTime` that checks, and each `whenTick`, a test of its own. [Test a scheduled job](./scheduling#test-it) shows a job's ticks and a missed morning.

## What the compiler generated

A test in the domain module can load what the compiler generated for it, and check the module's binding index and Manifest fragment: which context it names, which models it builds, which subscriptions its processors become.

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/OrderingDomainModelProviderTest.kt#provider -->

```kotlin
@Test
fun `the binding index should name the context and the fragment and build the order model`() {
    provider.context.assert().isEqualTo("eshop-ordering")
    provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-ordering-domain.json")
    provider.models(Dependencies.of(PaymentPolicy(30.minutes))).map { it.type.toString() }
        .assert().containsExactly("eshop-ordering.order")
    val ports = Dependencies.of(OrderSummaries { _, _ -> }, BuyerNotifications { _, _, _ -> })
    provider.processors(ports).map { it.name }.assert().containsExactlyInAnyOrder(
        "OrderProcessSaga.onOrderSubmitted",
        "OrderProcessSaga.onOrderAwaitingValidation",
        "OrderProcessSaga.onOrderCancelled",
        "OrderProcessSaga.onOrderShipped",
        "OrderSummaryProjection.onEvent",
        "ShipmentNotifier.onOrderShipped",
    )
}
```

`processors(dependencies)` constructs every processor of the module, so it needs the services of all of them. A processor test that needs only its own uses its generated handlers instead.

## Beyond domain tests

Domain tests cover the model. A service test starts the application and calls it over HTTP; keep it for what only a running service shows, such as routes, authorization and configuration:

- Run it on the memory backend, with random ports for the public and internal servers and the internal port on the loopback interface.
- Give the test a caller resolver of its own that reads a test header, so each request has a tenant and an owner.
- On the memory backend, the snapshot and events queries are served from memory by default, so a test can query what it wrote; `wow.query.memory.enabled: false` turns them off. They run the in-memory engine, not a real store's.
- A service whose sagas send commands to another context needs a command transport: Kafka in a container, or `wow.transport=memory`.
- Tests against PostgreSQL, MongoDB, Kafka or ClickHouse run in containers, in the module's `integrationTest` test suite, which the Gradle plugins `me.ahoo.wow` and `me.ahoo.wow.service` register (JUnit Jupiter, the module's classes and everything `testImplementation` gives) and `check` does not run: put the sources under `src/integrationTest/kotlin` and run `./gradlew integrationTest`.

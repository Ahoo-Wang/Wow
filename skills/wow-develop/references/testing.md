# Testing

The job: prove a rule before writing it. The V10 test DSL (`me.ahoo.wow.test`, artifact `wow-test`, Kotlin only) runs a domain in memory against its generated model: no container, no Spring. Write the spec first, see it fail for the right reason, then make it pass. Verify the DSL's members in the target's `wow-test` version.

## Aggregates

`aggregateSpec(model, id, tenantId, start) { … }`, the model from the generated factory (`accountModel("bank")`, services stubbed):

<!-- sample: example/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt#withdraw -->

```kotlin
@Test
fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
    given(AccountOpened("Alice"), Deposited(100))
    whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
    whenCommand(Withdraw(30)) {
        expectEvents(Withdrawn(30))
        expectState { balance.assert().isEqualTo(70) }
    }
}
```

- Arrange: `given(events…)`, or `givenState(state, version)` / `givenState(version) { … }` as the first step (copied through JSON; later commits continue from it).
- Act: `whenCommand(command, scope = …) { … }`.
- Assert: `expectEvents(...)`, `expectEventTypes(X::class)`, `expectEvent<E> { }`, `expectUnchanged`, `expectRejected(code) { }` (a local or full code), `expectError<T> { }`, `expectNoError`, `expectState { }`, `expectScope`, `expectScheduled`, `expectScheduleCancelled`.
- A rejection is checked by code (`expectRejected`); an exception decide throws by type (`expectError<T>`), and an unchecked one fails the spec. After a command that committed nothing, `expectState` checks the current state.
- The spec fails a decide function that mutates the state.
- Time: `advanceTime(d) { … }` fails when nothing was due (without a block it never fails); `expectNothingDue(within = d)` shows a cancelled schedule does not fire.

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#schedule -->

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

## Shared prefixes: forks

`ref("name")` marks a point; `fork("name") { … }`, `fork(from = "name") { … }`, or a `fork` inside a `whenCommand` block runs on a copy of everything the spec holds (log, state, timers, reservations, ids, clock), so the prefix is not run again and mocked services are not called again. `@TestFactory fun x() = aggregateSpecTests(model) { … }` makes each step a test and each fork a container (`sagaSpecTests`, `projectionSpecTests`, `reactorSpecTests` likewise).

<!-- sample: example/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#fork -->

```kotlin
@Test
fun `a requested payment should end confirmed and refunded, or failed`() = paymentSpec {
    whenCommand(RequestPayment(ORDER, AMOUNT)) { expectEventTypes(PaymentRequested::class) }
    ref("requested")
    fork("confirmed") {
        whenCommand(ConfirmPayment(TRANSACTION)) { expectEventTypes(PaymentSucceeded::class) }
        whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
    }
    fork("failed") {
        whenCommand(FailPayment(DECLINED)) { expectEventTypes(PaymentFailed::class) }
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

## Sagas

`sagaSpec(<processor>Handlers(Dependencies.of(…))) { whenEvent(stateEvent(ref, state, *events, version = …)) { … } }`. The compiler generates `<processor>Handlers(dependencies)` per processor (`orderProcessSagaHandlers`), which builds that processor alone with its own services.

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#rejected -->

```kotlin
@Test
fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
    whenEvent(event(OrderAwaitingValidation)) {
        val cap = commands.single { it.ref.id == "cap" }
        whenRejected(cap, "eshop-inventory.stock.insufficient", "Only 0 of cap are available, not 1.") {
            expectHandledBy("onReserveRejected")
            expectCommand(CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."), to = order)
            // The same receipt again derives the same request.
            expectDeterministic()
        }
    }
}
```

- Assert: `expectCommands`, `expectCommandTypes`, `expectCommand(body, to)`, `expectCommand<C> { }`, `expectNoCommand`, `expectScheduled`, `expectScheduleCancelled`, `expectNoSchedule`, `expectHandledBy(...)`, `expectDeterministic()`, `expectError<T>`; then `whenRejected(command, code, message) { … }` and `whenCommitted(command, version) { … }` for the receipts.
- `whenEvent` calls every handler that reads the event. Events of another context's stream are `publishedEvent(name, body)`.
- `expectDeterministic()` handles the commit again with a fresh context and expects the same commands: the idempotency of the saga, tested.

## Projections and reactors

`projectionSpec(handlers)`, `reactorSpec(handlers)`: pass a fake of the port they write to through the handlers' dependencies, `whenEvent(...)`, then assert on the fake. Test a projection twice with the same commit, and an older version after a newer one: its version guard is the behavior.

## Server tests

Command constraints are checked at admission, which `aggregateSpec` does not run (decision D200): test them here, a request that breaks one answering 400 `wow.command.invalid` with the violation's `path` and `code` (the constraint's simple name, `Positive`).

Start the application with `server.port=0`, `wow.internal.port=0` and a test `CallerResolver`; the memory backend serves no snapshot queries unless `wow.query.memory.enabled` is on, and a saga to another context needs `wow.transport=memory` or an `InMemoryTopics` bean. End-to-end tests across services on containers belong in the service's `integrationTest` source set (Testcontainers), as the eShop's `example/eshop/e2e` does.

## Gotchas

- A JUnit test function must return `Unit`: an expression body that returns a value is skipped silently. `aggregateSpec` and `sagaSpec` return `Unit`; count executed tests in `build/test-results` when it matters.
- `ModelProvider.processors(...)` builds every processor of the module and needs all their services; prefer the generated `<processor>Handlers` per processor.

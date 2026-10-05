---
title: Domain Test Suite
description: Use wow-test to express aggregate and saga behavior as Given → When → Expect specifications.
outline: deep
---

# Domain Test Suite

`wow-test` runs aggregate and stateless saga specifications with an in-memory domain runtime. It verifies command decisions, domain events, event-sourced state, and commands produced by sagas without requiring a database or message broker.

::: warning Test boundary
A passing domain specification proves domain behavior only. It does not prove that KSP output is packaged, Spring is wired correctly, HTTP routes work, real storage recovers, or authorization is enforced. See [Testing Wow Applications](./application-testing.md) for those gates.
:::

::: tip Completion signal
Every modeled invariant should have at least one success or rejection specification, and state transitions should assert both the event and sourced state. When the domain layer is complete, run its owning module's `test`/`check`, then move to application integration gates.
:::

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
dependencies {
    testImplementation("me.ahoo.wow:wow-test:${wowVersion}")
}
```

```groovy [Gradle(Groovy)]
dependencies {
    testImplementation "me.ahoo.wow:wow-test:${wowVersion}"
}
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

Kotlin assertions use the FluentAssert extension from the project's test stack:

```kotlin
import me.ahoo.test.asserts.assert
```

## Given → When → Expect

| Stage | Question to express | Common DSL |
| --- | --- | --- |
| Given | What happened to the aggregate before now? | `givenEvent`, `givenState`, `givenOwnerId`, `givenSpaceId`, `inject` |
| When | What happens now? | `whenCommand`, or `whenEvent` for a saga |
| Expect | Does the result satisfy the invariant? | `expectNoError`, `expectErrorType`, `expectEventType`, `expectState`, `expectCommand` |

Prefer historical events for Given. `givenState` is useful when a test explicitly needs to start at a state version, but it bypasses event replay and cannot replace sourcing-behavior verification.

### The same pipeline as production

Since 9.3.0 an aggregate specification runs each command through the production command pipeline and kernel: the aggregate processor loads the state from an event store and an in-memory snapshot store, the kernel checks creation, existence, owner, space and deletion, decides, appends, and applies the committed events. The command body is validated first, as the command gateway does. Only one thing differs: a recoverable failure is reported at once instead of being retried.

- Given events are appended to the event store (the one passed to `aggregateVerifier`, by default in memory) as one stream at the next version; `givenState` is saved as the snapshot the command starts from. The in-memory snapshot store keeps the snapshot as JSON, so the state the command sees is a JSON round trip of the given one: a field Jackson does not serialize (transient, no accessor, a type it cannot read back) does not reach the command.
- A command addresses the aggregate its message names: a command whose `@AggregateId` names another aggregate does not see the given history.
- After each step the state is loaded again from the stores; the next step, `then()`, continues that history, and `fork` copies it into new in-memory stores.
- Sibling `whenCommand`s of one given (several in one `on { }` or one `given(...) { }` block) are independent: each starts from the given history, not after its siblings' commands. The first runs on the given stores; a later sibling for the same aggregate runs on a copy of the history as it was given. Chain steps with `then()` or `fork` to build on a command.

What a specification sees therefore matches production; before 9.3.0 the DSL differed in these places:

| Situation | Before 9.3.0 | Since 9.3.0 |
| --- | --- | --- |
| Non-create command on an aggregate without history | `IllegalArgumentException` ("given at least one sourcing event") | `NotFoundResourceException`, as in production |
| Create command after given events | `IllegalArgumentException` thrown by `whenCommand` | `DuplicateAggregateIdException` from the append |
| Command function returns nothing (`null`, `Unit`, empty `Mono`) | the verification failed ("A command generates at least one event.") | no error and no event stream |
| Command for another aggregate ID after a verified step | ran on the verified state | runs on that aggregate: `NotFoundResourceException` when it has no history |
| Given events | sourced into the state, not stored | stored at their real version, then loaded |
| The next step's state | the previous step's instance | loaded from the stores (a new instance) |
| A sourcing function that cannot apply committed events | the command failed | the events are stored and the step reports the sourcing error |
| `givenState` | the given object ran the command | a copy loaded from its snapshot; the given object is not changed |

## Aggregate Specifications: Assert Event and State Together

This minimal scenario comes from the current `CartSpec`. It starts with an uninitialized aggregate, sets the owner, executes an add-item command, and verifies the event, business state, and aggregate metadata together:

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        val ownerId = generateGlobalId()
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        givenOwnerId(ownerId)
        whenCommand(addCartItem) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
            expectStateAggregate {
                this.ownerId.assert().isEqualTo(ownerId)
            }
        }
    }
})
```

The event assertion proves the command decision; the state assertion proves the sourcing function applied that event correctly. Asserting only one side misses regressions on the other.

### Rejection Paths

Rejection paths should assert a specific error and, where important, confirm that state or aggregate metadata did not advance. The current `OrderSpec` covers empty items, insufficient inventory, inconsistent prices, shipping before payment, and access after deletion.

```kotlin
fork("Ship Before Payment") {
    val shipOrder = ShipOrder(stateAggregate.aggregateId.id)
    whenCommand(shipOrder) {
        expectErrorType(IllegalStateException::class)
        expectState {
            paidAmount.assert().isEqualTo(BigDecimal.ZERO)
            status.assert().isEqualTo(OrderStatus.CREATED)
        }
    }
}
```

Do not reduce every failure to `expectError()`. When the error type is part of the business contract, use `expectErrorType(...)` so the specification distinguishes rejection reasons.

### Branches and Reference Points

`fork` continues from an already verified state. It fits payment, shipment, and receipt after order creation, as well as invalid transitions from the same starting point. Each branch has independent subsequent state and does not contaminate sibling branches.

Branch directly from the current Expect stage:

```kotlin
fork(name = "Remove CartItem") {
    whenCommand(RemoveCartItem(setOf(addCartItem.productId))) {
        expectEventType(CartItemRemoved::class)
        expectState {
            items.assert().isEmpty()
        }
    }
}
```

When a later scenario must branch from the same point, call `ref("AggregateDeleted")` first and then use top-level `fork(ref = "AggregateDeleted", ...)`. A reference should represent a verified business state, not merely save a few setup lines.

```kotlin
fork(ref = "AggregateDeleted", name = "Recover") {
    whenCommand(DefaultRecoverAggregate) {
        expectNoError()
        expectStateAggregate {
            deleted.assert().isFalse()
        }
        fork(name = "Recover Again") {
            whenCommand(DefaultRecoverAggregate) {
                expectErrorType(IllegalStateException::class)
            }
        }
    }
}
```

## Inject Domain Dependencies

When a command handler depends on a domain specification service, register a test implementation with `inject`. The current `OrderSpec` injects inventory and pricing services into `DefaultCreateOrderSpec` to cover success, insufficient inventory, and inconsistent prices.

```kotlin
inject {
    register(DefaultCreateOrderSpec(inventoryService, pricingService))
}

whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false)) {
    expectNoError()
    expectEventType(OrderCreated::class)
    expectState {
        status.assert().isEqualTo(OrderStatus.CREATED)
        totalAmount.assert().isEqualTo(totalAmount)
    }
}
```

These are test implementations at the domain boundary. Real network clients, databases, and brokers belong in application integration tests, not this layer.

## Stateless Saga Specifications

For `SagaSpec`, When is an input event and Expect is the command sent by the saga. The current `CartSagaSpec` verifies that creating an order from a cart removes the corresponding products:

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.SagaSpec

class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )

        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = ownerId,
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
})
```

Use `expectNoCommand()` for the corresponding negative path, such as `OrderCreated.fromCart == false`. A saga specification verifies command intent and content; broker redelivery and external-side-effect idempotency still require real-adapter tests.

## Choose the Narrowest Assertion

| Goal | DSL |
| --- | --- |
| No error / a specific error | `expectNoError()` / `expectErrorType(...)` |
| Event count, order, or type | `expectEventCount`, `expectEventIterator`, `expectEventType` |
| Event-body fields | `expectEventBody<E> { ... }` |
| Business state | `expectState { ... }` |
| Aggregate metadata such as owner, version, or deleted flag | `expectStateAggregate { ... }` |
| Saga command count, type, or content | `expectCommandCount`, `expectCommandType`, `expectCommand<C>` |

Assert business-observable results instead of copying framework internals. Use `.assert()` consistently for Kotlin values rather than mixing assertion styles in the same specification suite.

## Reading Coverage Evidence

The current repository configures a `0.8` minimum for `:example-domain:jacocoTestCoverageVerification`, and that task depends on `test` and report generation. The threshold runs only when `:example-domain:jacocoTestCoverageVerification` is invoked explicitly; neither the current `:example-domain:check` nor the CI workflows attach it automatically. This is an optional repository coverage gate, not a coverage level automatically guaranteed by `wow-test` or a number every application must copy.

Old documentation screenshots, historical coverage figures, or anecdotal defect data describe only their original samples. Evaluate a current change using the current test output, current coverage report, and the application's own threshold.

## Run and Move to the Next Layer

Verify the example and DSL in this repository with:

```bash
./gradlew :wow-test:check :example-domain:check \
  :example-domain:jacocoTestCoverageVerification
```

Business applications should substitute their own domain-module path and include its verification task only when the project actually configures a threshold. After this command passes, move to [Testing Wow Applications](./application-testing.md) for generated metadata, runtime wiring, HTTP, real adapters, restart recovery, and security negatives. When changing the Wow framework itself, use the repository tasks in [Framework Tests and Benchmarks](./test-runtime.md).

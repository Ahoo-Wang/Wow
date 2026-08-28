# wow-test

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-test)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-test)

`wow-test` provides a JUnit dynamic-test DSL for expressing aggregate and stateless Saga behavior as Given → When → Expect specifications.

## When to use it

- Use `AggregateSpec` to verify command decisions, domain events, and event-sourced state.
- Use `SagaSpec` to verify commands produced from input events.
- Inject domain services in tests or branch scenarios from an already verified state.

## Dependency

Maven coordinate: `me.ahoo.wow:wow-test`. Add it only to the test configuration:

```kotlin
dependencies {
    testImplementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    testImplementation("me.ahoo.wow:wow-test")
}
```

For Kotlin assertions, use the FluentAssert extension already provided by this module: `me.ahoo.test.asserts.assert`.

## Public boundary

The public API lives under `me.ahoo.wow.test.*`, led by `AggregateSpec`, `SagaSpec`, and their DSLs. Aggregate specifications use an in-memory EventStore by default; Saga specifications use an in-memory CommandBus. An aggregate specification can verify modeled domain lifecycle transitions such as `DefaultDeleteAggregate` and `DefaultRecoverAggregate`, as the current `CartSpec` does.

These specifications prove domain decisions, events, sourced state, or Saga commands. They do not prove KSP output, Spring wiring, HTTP routes, real brokers/databases, recovery of real storage, process restarts, production-infrastructure recovery, or authorization.

## Minimal example

This scenario is reduced from the current [cart specification](../../example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartSpec.kt):

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        whenCommand(AddCartItem(productId = "productId", quantity = 1)) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
        }
    }
})
```

The event assertion verifies the command decision; the state assertion verifies that sourcing applied the event.

## Verify

```bash
./gradlew :wow-test:check
```

## Guides

- [Domain Test Suite](../../documentation/docs/en/guide/test-suite.md)
- [Wow Application Testing](../../documentation/docs/en/guide/application-testing.md)
- [Aggregate Modeling](../../documentation/docs/en/guide/modeling.md)

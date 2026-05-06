# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build entire project
./gradlew build

# Run all tests
./gradlew test

# Single module test
./gradlew :<module>:test --tests "me.ahoo.wow.<module>.<TestClass>"

# Integration tests
./gradlew :wow-it:test

# Lint & format
./gradlew detekt
./gradlew detekt --auto-correct

# Generate code coverage report
./gradlew codeCoverageReport
```

**Compensation Dashboard** (React/TypeScript frontend):
```bash
cd compensation/dashboard
pnpm install  # first time only
pnpm dev      # development server
pnpm build    # production build
pnpm test     # run tests
pnpm coverage # test coverage
pnpm generate # regenerate OpenAPI clients from server schema
```

## Architecture Overview

Wow is a **reactive CQRS + Event Sourcing framework** for building microservices with DDD patterns.

### Module Structure

| Module | Purpose |
|--------|---------|
| `wow-api` | Core interfaces, annotations, and types (contracts) |
| `wow-core` | Framework runtime: command processing, event sourcing, saga orchestration |
| `wow-compiler` | KSP annotations processor for compile-time code generation |
| `wow-spring-boot-starter` | Spring Boot 3.x/4.x integration |

**Infrastructure modules**: `wow-mongo`, `wow-kafka`, `wow-redis`, `wow-elasticsearch`, `wow-r2dbc`

**Extensions**: `wow-query` (query support), `wow-openapi` (REST routing), `wow-opentelemetry` (tracing), `wow-bi` (business intelligence), `wow-cosec` (security)

**Testing modules**: `wow-test` (BDD DSL), `wow-tck` (compatibility tests), `wow-mock` (test doubles), `wow-it` (integration tests)

**Examples**: `example-domain`, `example-api`, `example-server` (order/cart domain); `example-transfer-*` (Java transfer saga)

### Key Patterns

**Aggregate with separate state** (recommended):
```kotlin
@AggregateRoot
class Cart(private val state: CartState) {
    @OnCommand
    fun onCommand(command: AddCartItem): CartItemAdded { ... }
}

class CartState(val id: String) {
    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) { ... }
}
```

**Saga for distributed transactions**:
```kotlin
@StatelessSaga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: OrderCreated): CommandBuilder? { ... }
}
```

**Testing with BDD DSL**:
```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        whenCommand(CreateOrder()) {
            expectNoError()
            expectEventType(OrderCreated::class)
        }
    }
})
```

## Code Conventions

- **Language**: Kotlin (JVM toolchain 17)
- **Compiler args**: `-Xjsr305=strict -Xannotation-default-target=param-property`
- **Package structure**: `me.ahoo.wow.<module>.<component>`
- **Line length**: 300 chars (Detekt auto-format handles this)
- **No wildcard imports** except `java.util.*`, `kotlinx.coroutines.*`, `org.assertj.core.api.Assertions.*`

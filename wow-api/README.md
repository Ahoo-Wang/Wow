# Wow API

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)

The core API definitions module for the Wow framework, providing essential interfaces, annotations, and types for building domain-driven design (DDD) applications with Command Query Responsibility Segregation (CQRS) and Event Sourcing patterns.

## Introduction

Wow API is the foundational module of the [Wow framework](https://github.com/Ahoo-Wang/Wow), a modern reactive microservice development framework based on DDD and Event Sourcing. This module defines the core abstractions and contracts that enable:

- **Domain-Driven Design**: Aggregate roots, domain events, value objects, and entities
- **CQRS Architecture**: Separate command and query models with clear boundaries
- **Event Sourcing**: Immutable event streams for state reconstruction and audit trails
- **Reactive Programming**: Non-blocking, asynchronous processing pipelines
- **Type Safety**: Strongly-typed APIs with Kotlin's type system

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-api:6.5.2")
}
```

### Gradle (Groovy DSL)

```gradle
dependencies {
    implementation 'me.ahoo.wow:wow-api:6.5.2'
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-api</artifactId>
    <version>6.5.2</version>
</dependency>
```

## Usage

### Defining Commands and Events

Commands and events are simple data classes with validation and routing annotations:

#### Commands with Validation and Routing

```kotlin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary

@Order(1)
@AllowCreate
@CommandRoute(method = CommandRoute.Method.POST)
@Summary("Add item to cart")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

@Order(2)
@Summary("Change item quantity")
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeQuantity(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int
)

@Order(3)
@Summary("Remove items from cart")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)
```

#### Domain Events

```kotlin
@Summary("Item added to cart")
data class CartItemAdded(
    val added: CartItem
)

data class CartQuantityChanged(
    val changed: CartItem
)

data class CartItemRemoved(
    val productIds: Set<String>
)
```

#### Value Objects and Entities

```kotlin
import me.ahoo.wow.api.annotation.ValueObject
import me.ahoo.wow.api.annotation.EntityObject

@ValueObject
data class CartItem(
    val productId: String,
    val quantity: Int = 1
)

@EntityObject
data class OrderItem(
    override val id: String,
    val productId: String,
    val price: BigDecimal,
    val quantity: Int
) : Identifier {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

### Bounded Context Configuration

Define bounded contexts and aggregates:

```kotlin
import me.ahoo.wow.api.annotation.BoundedContext

@BoundedContext(
    name = "example-service",
    alias = "example",
    description = "Example Service Context",
    aggregates = [
        BoundedContext.Aggregate("order", packageScopes = [CreateOrder::class]),
        BoundedContext.Aggregate(
            "cart",
            tenantId = TenantId.DEFAULT_TENANT_ID,
            packageScopes = [AddCartItem::class]
        ),
    ],
)
object ExampleService {
    const val SERVICE_NAME = "example-service"
    const val ORDER_AGGREGATE_NAME = "order"
    const val CART_AGGREGATE_NAME = "cart"
}
```

## API Reference

### Core Annotations

- `@AggregateRoot` - Marks a class as an aggregate root in DDD
- `@AggregateId` - Marks a field as the aggregate identifier
- `@Event` - Marks a class as a domain event
- `@OnCommand` - Marks a method as a command handler
- `@OnSourcing` - Marks a method as an event sourcing handler
- `@StatelessSaga` - Marks a class as a stateless saga orchestrator
- `@OnEvent` - Marks a method as an event handler (for sagas)
- `@Retry` - Configures retry behavior for event processing
- `@BoundedContext` - Defines a bounded context boundary
- `@CommandRoute` - Configures REST API routing for commands
- `@AllowCreate` - Allows command to create new aggregates
- `@CreateAggregate` - Marks command as creating new aggregates
- `@VoidCommand` - Marks command as fire-and-forget (no response)
- `@Order` - Defines execution order for commands/events
- `@Summary` - Provides human-readable descriptions
- `@ValueObject` - Marks a class as a value object
- `@EntityObject` - Marks a class as an entity within an aggregate

### Key Interfaces

#### CommandMessage<T>
Represents a command to be executed against an aggregate.

**Key Properties:**
- `aggregateId: AggregateId` - Target aggregate identifier
- `aggregateVersion: Int?` - Expected version for optimistic locking
- `isCreate: Boolean` - Whether this creates a new aggregate
- `allowCreate: Boolean` - Whether creation is permitted
- `isVoid: Boolean` - Whether a response is expected

#### DomainEvent<T>
Represents an immutable fact about a business occurrence.

**Key Properties:**
- `aggregateId: AggregateId` - Source aggregate identifier
- `sequence: Int` - Event sequence number
- `revision: String` - Event schema version
- `isLast: Boolean` - Whether this is the final event

#### AggregateId
Identifies an aggregate instance within a bounded context.

**Properties:**
- `contextName: String` - Bounded context name
- `aggregateName: String` - Aggregate type name
- `id: String` - Instance identifier

### Saga Orchestration

Sagas coordinate distributed transactions using event-driven orchestration:

```kotlin
@StatelessSaga
class TransferSaga {

    fun onEvent(prepared: Prepared, aggregateId: AggregateId): Entry {
        return Entry(prepared.to(), aggregateId.id, prepared.amount())
    }

    fun onEvent(amountEntered: AmountEntered): Confirm {
        return Confirm(amountEntered.sourceId(), amountEntered.amount())
    }

    fun onEvent(entryFailed: EntryFailed): UnlockAmount {
        return UnlockAmount(entryFailed.sourceId(), entryFailed.amount())
    }
}
```

### Query APIs

The module provides comprehensive query capabilities:

```kotlin
// Single entity query
val query = SingleQuery(
    aggregateId = AggregateId("order", "order-123")
)

// Paged list query
val pagedQuery = PagedQuery(
    condition = Condition("status", Operator.EQ, "PENDING"),
    sort = listOf(Sort("createdAt", Direction.DESC)),
    pagination = Pagination(page = 1, size = 20)
)

// Dynamic document queries
val dynamicQuery = DynamicDocument(
    condition = Condition("customerId", Operator.EQ, customerId),
    projection = listOf("orderId", "totalAmount", "status")
)
```

## Modeling Patterns

### Aggregation Pattern (Recommended)

Separates command handling from state management for better separation of concerns:

```kotlin
// Command Aggregate - handles business logic
@AggregateRoot
class Cart(private val state: CartState) {

    @OnCommand
    fun onCommand(command: AddCartItem): CartItemAdded {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "Shopping cart can only contain [$MAX_CART_ITEM_SIZE] items."
        }
        // Business logic here
        return CartItemAdded(added = CartItem(command.productId, command.quantity))
    }
}

// State Aggregate - manages state data
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }
}
```

### Single Class Pattern

Combines everything in one class (simpler but less strict):

```kotlin
@AggregateRoot
class Order(@AggregateId val orderId: String) {

    private var status: OrderStatus = OrderStatus.PENDING
    private val items: MutableList<OrderItem> = mutableListOf()

    @OnCommand
    fun create(command: CreateOrder): OrderCreated {
        return OrderCreated(orderId, command.items, command.customerId)
    }

    @OnSourcing
    fun onCreated(event: OrderCreated) {
        items.addAll(event.items)
        status = OrderStatus.CREATED
    }
}
```

## Examples

### Shopping Cart API (Real Example)

Based on the actual example implementation:

```kotlin
// Commands with validation and routing
@Order(1)
@AllowCreate
@CommandRoute(method = CommandRoute.Method.POST)
@Summary("Add item to cart")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

@Order(2)
@Summary("Change item quantity")
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeQuantity(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int
)

@Order(3)
@Summary("Remove items from cart")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)

// Events
@Summary("Item added to cart")
data class CartItemAdded(val added: CartItem)

data class CartQuantityChanged(val changed: CartItem)

data class CartItemRemoved(val productIds: Set<String>)

// Value objects
@ValueObject
data class CartItem(
    val productId: String,
    val quantity: Int = 1
)

// Void command for queries
@VoidCommand
class ViewCart
```

### Order Management API (Real Example)

```kotlin
@Summary("Create order")
@CommandRoute(action = "")
@CreateAggregate
data class CreateOrder(
    @field:Size(min = 1)
    val items: List<Item>,
    @field:NotNull @field:Valid
    val address: ShippingAddress,
    val fromCart: Boolean
) : CommandValidator {

    override fun validate() {
        require(address.country == "China") {
            "Only support China shipping address."
        }
    }

    data class Item(
        @field:NotEmpty
        override val productId: String,
        @field:Positive
        override val price: BigDecimal,
        @field:Positive
        override val quantity: Int
    ) : CreateOrderItem
}

@CommandRoute("pay", method = CommandRoute.Method.POST, appendOwnerPath = CommandRoute.AppendPath.NEVER)
data class PayOrder(
    @field:NotBlank
    val paymentId: String,
    @field:Positive
    val amount: BigDecimal
)

// Events
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)

data class OrderPaid(val amount: BigDecimal, val paid: Boolean)
data class OrderOverPaid(val paymentId: String, val overPay: BigDecimal)

// Error handling
data class OrderPayDuplicated(val paymentId: String, override val errorMsg: String) : ErrorInfo {
    override val errorCode: String get() = "OrderPayDuplicated"
}

// Value objects and entities
@ValueObject
data class ShippingAddress(
    @field:NotBlank
    val country: String,
    @field:NotBlank
    val province: String,
    val city: String,
    val district: String,
    val detail: String
)

@EntityObject
data class OrderItem(
    override val id: String,
    override val productId: String,
    override val price: BigDecimal,
    override val quantity: Int
) : Identifier, CreateOrderItem {

    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

### Aggregate Implementation

```kotlin
@AggregateRoot
class Cart(private val state: CartState) {

    @OnCommand
    fun onCommand(command: AddCartItem): CartItemAdded {
        // Business logic validation
        return CartItemAdded(CartItem(command.productId, command.quantity))
    }

    @OnCommand
    fun onCommand(command: ChangeQuantity): CartQuantityChanged {
        // Update existing item quantity
        val updated = state.items.find { it.productId == command.productId }
            ?.copy(quantity = command.quantity)
            ?: throw IllegalArgumentException("Item not found")

        return CartQuantityChanged(updated)
    }
}

class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }

    @OnSourcing
    fun onCartQuantityChanged(event: CartQuantityChanged) {
        items = items.map {
            if (it.productId == event.changed.productId) event.changed else it
        }
    }
}
```

## Contributing

We welcome contributions to the Wow API module! Please see the main [Wow repository](https://github.com/Ahoo-Wang/Wow) for contribution guidelines.

### Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/Ahoo-Wang/Wow.git
   cd Wow
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

3. Run tests:
   ```bash
   ./gradlew :wow-api:test
   ```

### Code Style

This project follows Kotlin coding conventions and uses Detekt for static analysis. Format code using:

```bash
./gradlew detekt --auto-correct
```

## License

Wow API is licensed under the Apache License 2.0. See [LICENSE](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE) for details.
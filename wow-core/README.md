# Wow Core

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-core)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-core)

The core implementation module for the Wow framework, providing essential runtime infrastructure for CQRS and Event Sourcing applications including command processing, event handling, event sourcing, saga orchestration, and projections.

## Introduction

Wow Core is the foundational runtime module of the [Wow framework](https://github.com/Ahoo-Wang/Wow), implementing the core patterns and infrastructure needed for building domain-driven design (DDD) applications with Command Query Responsibility Segregation (CQRS) and Event Sourcing.

This module provides:

- **Command Processing**: Command gateway with validation, idempotency, and wait strategies
- **Event Handling**: Domain event bus, dispatching, and processing pipelines
- **Event Sourcing**: Event store implementations and state reconstruction
- **Saga Orchestration**: Distributed transaction coordination using event-driven workflows
- **Projection**: Event-driven updates to query models and read databases
- **Serialization**: JSON serialization for commands, events, and aggregate state
- **Exception Handling**: Comprehensive error handling and recovery mechanisms
- **Message Propagation**: Header propagation, tracing, and cross-service communication

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-core:6.5.2")
}
```

### Gradle (Groovy DSL)

```gradle
dependencies {
    implementation 'me.ahoo.wow:wow-core:6.5.2'
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-core</artifactId>
    <version>6.5.2</version>
</dependency>
```

## Usage

### Command Processing

The `CommandGateway` provides comprehensive command handling with validation, idempotency, and flexible wait strategies:

```kotlin
// Send command with validation and idempotency checking
val addCartItem = AddCartItem(productId = "product-123", quantity = 2)
val command = addCartItem.toCommandMessage(ownerId = "customer-456")

gateway.send(command)
    .doOnSuccess { println("Command sent successfully") }
    .subscribe()

// Send and wait for completion with different strategies
gateway.sendAndWait(command, WaitStrategy.PROCESSED)
    .doOnNext { result ->
        if (result.succeeded) {
            println("Cart item added: ${result.commandId}")
        }
    }
    .subscribe()

// Stream command results in real-time
gateway.sendAndWaitStream(command, WaitStrategy.PROCESSED)
    .doOnNext { result ->
        println("Command stage: ${result.stage} - ${result.succeeded}")
    }
    .subscribe()
```

### Event Handling

Domain events are published through the event bus and processed by handlers:

```kotlin
// Events are automatically published by aggregates after command processing
// The framework handles event publishing internally

// Handle events with projections
@ProjectionProcessor
class OrderProjector {

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return orderRepository.save(
            OrderSummary(
                id = event.aggregateId.id,
                totalAmount = event.items.sumOf { it.totalPrice },
                status = OrderStatus.CREATED,
                createdAt = event.createTime
            )
        ).then()
    }

    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> {
        return orderRepository.findById(event.aggregateId.id)
            .flatMap { summary ->
                val updated = summary.copy(
                    status = OrderStatus.PAID,
                    paidAt = event.createTime
                )
                orderRepository.save(updated)
            }
            .then()
    }
}
```

### Event Sourcing

Load and reconstruct aggregate state from event streams:

```kotlin
// Load event streams for state reconstruction
eventStore.load(aggregateId, headVersion = 1, tailVersion = Int.MAX_VALUE)
    .collectList()
    .map { eventStreams ->
        // Reconstruct aggregate state from events
        val state = OrderState(aggregateId.id)
        eventStreams.forEach { stream ->
            stream.events.forEach { event ->
                when (event) {
                    is OrderCreated -> {
                        state.items.addAll(event.items)
                        state.address = event.address
                        state.status = OrderStatus.CREATED
                    }
                    is OrderPaid -> {
                        state.status = OrderStatus.PAID
                        state.paidAmount = event.amount
                    }
                    is OrderShipped -> {
                        state.status = OrderStatus.SHIPPED
                    }
                    // ... handle other events
                }
            }
        }
        state
    }
    .subscribe()

// Load events by time range for auditing
eventStore.load(aggregateId, headEventTime = startTime, tailEventTime = endTime)
    .flatMap { stream -> Flux.fromIterable(stream.events) }
    .collectList()
    .subscribe { events -> auditLog.append(events) }
```

### Saga Orchestration

Coordinate distributed transactions using event-driven sagas:

```kotlin
@StatelessSaga
class CartSaga {

    /**
     * Remove cart items after order is created
     */
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) {
            return null
        }
        // Build command to remove items from cart
        return RemoveCartItem(
            productIds = orderCreated.items.map { it.productId }.toSet(),
        ).commandBuilder()
            .aggregateId(event.ownerId) // Cart aggregate ID
    }
}
```

## API Reference

### Core Interfaces

#### CommandGateway
Central interface for sending commands with validation and wait strategies.

**Key Methods:**
- `send(command: CommandMessage)` - Send command asynchronously
- `sendAndWait(command, waitStrategy)` - Send and wait for completion
- `sendAndWaitStream(command, waitStrategy)` - Stream command results

#### DomainEventBus
Message bus for publishing and subscribing to domain event streams.

**Implementations:**
- `LocalDomainEventBus` - In-process event handling
- `DistributedDomainEventBus` - Cross-service event distribution
- `LocalFirstDomainEventBus` - Hybrid local/distributed approach

#### EventStore
Persistent storage for domain event streams with versioning support.

**Key Methods:**
- `append(eventStream)` - Store new event streams
- `load(aggregateId, headVersion, tailVersion)` - Load event streams by version range
- `load(aggregateId, headEventTime, tailEventTime)` - Load by time range

#### StatelessSagaHandler
Processes domain events to coordinate distributed transactions.

#### ProjectionHandler
Updates query models in response to domain events.

### Key Components

#### Command Processing
- **Command Validation**: Jakarta validation integration
- **Idempotency Checking**: Prevents duplicate command processing
- **Wait Strategies**: `SENT`, `PROCESSED`, `PROJECTED` for different consistency levels

#### Event Processing
- **Event Dispatching**: Ordered event processing per aggregate
- **Event Filtering**: Function-based event routing
- **Error Handling**: Configurable error recovery strategies

#### Serialization
- **Message Serialization**: JSON serialization for all message types
- **State Serialization**: Aggregate state persistence
- **Event Stream Serialization**: Efficient event storage format

#### Exception Handling
- **Error Conversion**: Standardized error handling
- **Recoverable Exceptions**: Automatic retry mechanisms
- **Error Propagation**: Consistent error reporting across services

## Examples

### Complete Command Processing with REST Controller

```kotlin
@RestController
class CartController(
    private val commandGateway: CommandGateway,
    private val cartQueryClient: CartQueryClient
) {

    @PostMapping("/cart/{userId}/add-cart-item")
    fun addCartItem(@PathVariable userId: String): Flux<CommandResult> {
        val addCartItem = AddCartItem(
            productId = "product-123",
            quantity = 2
        )
        val command = addCartItem.toCommandMessage(ownerId = userId)

        // Stream command processing results in real-time
        return commandGateway.sendAndWaitStream(
            command,
            waitStrategy = WaitingForStage.snapshot(command.commandId)
        )
    }

    @GetMapping("/cart/me")
    fun getCart(): Mono<CartData> {
        return singleQuery {
            // Query current user's cart
        }.queryState(cartQueryClient)
    }
}
```

### Event-Sourced Aggregate Repository

```kotlin
class EventSourcingOrderRepository(
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository
) : OrderRepository {

    override fun load(orderId: String): Mono<OrderState> {
        val aggregateId = AggregateId("order", orderId)

        return snapshotRepository.load(aggregateId)
            .flatMap { snapshot ->
                // Load events after snapshot version
                eventStore.load(aggregateId, snapshot.version + 1)
                    .collectList()
                    .map { eventStreams ->
                        val state = snapshot.state as OrderState
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            }
            .switchIfEmpty(
                // No snapshot, load all events from beginning
                eventStore.load(aggregateId, headVersion = 1)
                    .collectList()
                    .map { eventStreams ->
                        val state = OrderState(orderId)
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            )
    }

    private fun OrderState.apply(event: DomainEvent<*>): OrderState {
        return when (event) {
            is OrderCreated -> {
                this.items.addAll(event.items)
                this.address = event.address
                this.status = OrderStatus.CREATED
                this
            }
            is OrderPaid -> {
                this.status = OrderStatus.PAID
                this.paidAmount += event.amount
                this
            }
            is OrderShipped -> {
                this.status = OrderStatus.SHIPPED
                this
            }
            is OrderReceived -> {
                this.status = OrderStatus.RECEIVED
                this
            }
            else -> this
        }
    }
}
```

### Projection Implementation

```kotlin
@ProjectionProcessor
class OrderProjector(
    private val orderRepository: OrderRepository
) {

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        // Log the event for monitoring
        log.info("Order created: ${event.aggregateId.id}")

        // Update read model asynchronously
        return orderRepository.saveOrderSummary(
            OrderSummary(
                id = event.aggregateId.id,
                items = event.items,
                address = event.address,
                totalAmount = event.items.sumOf { it.totalPrice },
                status = OrderStatus.CREATED,
                createdAt = event.createTime
            )
        ).then()
    }

    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> {
        log.debug("Order paid: ${event.aggregateId.id}")

        return orderRepository.updateOrderStatus(
            event.aggregateId.id,
            OrderStatus.PAID,
            paidAt = event.createTime
        ).then()
    }

    @OnEvent
    fun onOrderShipped(event: OrderShipped): Mono<Void> {
        return orderRepository.updateOrderStatus(
            event.aggregateId.id,
            OrderStatus.SHIPPED,
            shippedAt = event.createTime
        ).then()
    }

    // Handle state events for additional processing
    @OnEvent
    fun onStateEvent(event: OrderCreated, state: OrderState): Mono<Void> {
        // Access both event and current state for complex logic
        log.info("Order state after creation: ${state.toJsonString()}")
        return Mono.empty()
    }
}
```

## Configuration

### Command Gateway Configuration

```yaml
wow:
  command:
    bus:
      type: kafka  # in_memory, kafka, redis, etc.
      local-first:
        enabled: true  # Enable local-first optimization
    idempotency:
      enabled: true  # Enable command idempotency checking
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S  # Time-to-live for idempotency records
        fpp: 0.00001  # False positive probability
```

### Event Store Configuration

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo  # mongo, redis, r2dbc, in_memory, delay
    snapshot:
      enabled: true  # Enable snapshot optimization
      strategy: all  # all, version_offset
      storage: mongo  # mongo, redis, r2dbc, elasticsearch, in_memory, delay
      version-offset: 5  # Version offset for snapshots
    state:
      bus:
        type: kafka  # in_memory, kafka, redis, etc.
        local-first:
          enabled: true
```

### Saga Configuration

Saga orchestration is automatically configured when using `@StatelessSaga` annotations. No additional configuration is typically required, but you can customize error handling behavior through the filter chain.

## Contributing

We welcome contributions to the Wow Core module! Please see the main [Wow repository](https://github.com/Ahoo-Wang/Wow) for contribution guidelines.

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
   ./gradlew :wow-core:test
   ```

### Code Style

This project follows Kotlin coding conventions and uses Detekt for static analysis. Format code using:

```bash
./gradlew detekt --auto-correct
```

## License

Wow Core is licensed under the Apache License 2.0. See [LICENSE](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE) for details.
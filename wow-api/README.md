# Wow API

[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

The core API definitions for the Wow framework, providing abstractions and annotations for building CQRS/Event Sourcing applications.

## Overview

Wow API is the foundational library of the Wow framework, offering a comprehensive set of interfaces, annotations, and abstractions for implementing Command Query Responsibility Segregation (CQRS) and Event Sourcing patterns in Kotlin applications.

### Key Features

- **Annotation-Driven**: Rich set of annotations for declaring aggregates, commands, events, and processors
- **Type-Safe Modeling**: Strongly typed abstractions for aggregates, events, and commands
- **Messaging Infrastructure**: Built-in support for message headers, topics, and function metadata
- **Query Capabilities**: Flexible query APIs with pagination, sorting, and dynamic projections
- **Exception Handling**: Standardized error information and recoverable exception types
- **Multi-Tenancy**: Built-in support for tenant isolation and ownership

## Modules

### Annotations (`me.ahoo.wow.api.annotation`)

Core annotations for declaring domain components:

- **Aggregate Annotations**: `@AggregateRoot`, `@AggregateId`, `@AggregateName`, `@AggregateVersion`
- **Command Annotations**: `@OnCommand`, `@CommandRoute`, `@CreateAggregate`
- **Event Annotations**: `@OnEvent`, `@Event`, `@EventProcessor`
- **Projection Annotations**: `@ProjectionProcessor`, `@StatelessSaga`
- **Utility Annotations**: `@Retry`, `@Order`, `@BoundedContext`

### Commands (`me.ahoo.wow.api.command`)

Command-related abstractions and validation:

- `CommandMessage`: Represents a command with metadata
- `CommandId`, `RequestId`: Unique identifiers for commands
- `CommandResultAccessor`: Access to command execution results
- Command validation framework

### Events (`me.ahoo.wow.api.event`)

Event sourcing abstractions:

- `DomainEvent`: Core domain event interface
- `AggregateDeleted`, `AggregateRecovered`: Lifecycle events
- `@IgnoreSourcing`: Annotation to exclude events from sourcing
- `Revision`: Event versioning support

### Exceptions (`me.ahoo.wow.api.exception`)

Standardized error handling:

- `ErrorInfo`: Structured error information
- `ErrorInfoCapable`: Interface for objects that can provide error info
- `RecoverableType`: Classification for retryable exceptions

### Messaging (`me.ahoo.wow.api.messaging`)

Message processing infrastructure:

- `Message`: Base message interface with headers
- `Header`: Message header utilities
- `TopicKind`: Message routing classification
- Function metadata: `FunctionInfo`, `FunctionKind`, processor information

### Modeling (`me.ahoo.wow.api.modeling`)

Domain modeling abstractions:

- `AggregateId`: Aggregate identifier with tenant/owner support
- `NamedAggregate`: Named aggregate with context information
- State capabilities: `StateCapable`, `DeletedCapable`, `OperatorCapable`
- Temporal capabilities: `CreateTimeCapable`, `EventTimeCapable`

### Naming (`me.ahoo.wow.api.naming`)

Naming and qualification utilities:

- `Named`: Basic naming interface
- `QualifiedNamed`: Qualified naming with context
- `NamedBoundedContext`: Bounded context naming
- Status capabilities: `EnabledCapable`, `CompletedCapable`

### Query (`me.ahoo.wow.api.query`)

Query and projection APIs:

- Query types: `SingleQuery`, `ListQuery`, `PagedQuery`
- Conditions: `Condition`, `RewritableCondition`
- Projections: `RewritableProjection`, `DynamicDocument`
- Results: `PagedList`, `MaterializedSnapshot`

## Quick Start

### Basic Aggregate Definition

```kotlin
@AggregateRoot
class Order {
    @AggregateId
    lateinit var id: String

    @AggregateVersion
    var version: Int = 0

    var status: OrderStatus = OrderStatus.CREATED
    var items: List<OrderItem> = emptyList()

    @OnCommand
    fun create(@Valid command: CreateOrderCommand): OrderCreatedEvent {
        // Command handling logic
        return OrderCreatedEvent(command.items)
    }

    @OnEvent
    fun onCreated(event: OrderCreatedEvent) {
        this.items = event.items
        this.status = OrderStatus.CONFIRMED
    }
}
```

### Command Definition

```kotlin
@ValueObject
data class CreateOrderCommand(
    @AggregateId
    val orderId: String,
    val items: List<OrderItem>
)
```

### Event Definition

```kotlin
@Event
data class OrderCreatedEvent(
    val items: List<OrderItem>
)
```

### Query Usage

```kotlin
val query = PagedQuery(
    condition = Condition.and(
        Condition.eq("status", OrderStatus.CONFIRMED),
        Condition.gt("createTime", yesterday)
    ),
    sort = listOf(SortCapable.asc("createTime")),
    pagination = Pagination(0, 20)
)

val results = orderQueryService.paged(query)
```

## Installation

### Gradle

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-api:6.5.1")
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-api</artifactId>
    <version>6.5.1</version>
</dependency>
```
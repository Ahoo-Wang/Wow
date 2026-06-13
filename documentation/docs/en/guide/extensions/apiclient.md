---
title: API Client
description: RESTful API client for Wow based on CoApi, providing reactive and synchronous command sending and snapshot query interfaces.
---

# API Client

The API Client module provides a declarative RESTful client for Wow services based on [CoApi](https://github.com/Ahoo-Wang/CoApi). It offers both reactive and synchronous interfaces for sending commands and querying snapshots.

## Features

- **Reactive and Synchronous APIs** — Choose between `Mono`-based reactive or blocking synchronous interfaces
- **Service Discovery** — Built-in support via `@CoApi` and `@LoadBalanced` annotations
- **Command Gateway** — Send commands with wait plans through REST endpoints
- **Snapshot Query** — Single, list, paged, and count query interfaces

## Installation

Add the `wow-apiclient` dependency:

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
```

## Command Gateway

### Reactive Command Gateway

```kotlin
@CoApi
interface OrderCommandGateway : ReactiveRestCommandGateway<Mono<CommandResult>, CommandResult>
```

Send a command:

```kotlin
val request = CommandRequest(
    body = CreateOrder(orderId = "order-001", items = listOf(...)),
    waitPlan = WaitPlan.projected()
)
val result = orderCommandGateway.send(request).block()
```

### Synchronous Command Gateway

```kotlin
@CoApi
interface OrderCommandGateway : SyncRestCommandGateway<CommandResult, CommandResult>
```

## Snapshot Query

### Reactive Query API

```kotlin
@CoApi
interface OrderQueryApi : ReactiveSnapshotQueryApi<OrderState>
```

Provides single, list, paged, and count query methods:

```kotlin
val order = queryApi.getById("order-001").block()
val paged = queryApi.paged(PagedQuery(pageIndex = 0, pageSize = 10)).block()
val count = queryApi.count().block()
```

### Synchronous Query API

```kotlin
@CoApi
interface OrderQueryApi : SynchronousSnapshotQueryApi<OrderState>
```

## Error Handling

`RestCommandGatewayException` wraps command errors with full request context:

```kotlin
try {
    orderCommandGateway.send(request).block()
} catch (ex: RestCommandGatewayException) {
    println("Command failed: ${ex.message}")
}
```

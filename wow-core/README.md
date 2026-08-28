# Wow Core

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-core)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-core)

`wow-core` is Wow's reactive runtime core. It provides public interfaces and base implementations for command processing, event sourcing, message dispatch, wait stages, projections, and Sagas.

## When to use it

- Build domain runtime or a non-Spring integration that directly uses `CommandGateway`, `EventStore`, message buses, or dispatchers.
- Develop an extension that participates in the Wow runtime lifecycle.

Spring Boot services normally depend on `wow-spring-boot-starter` instead of assembling `wow-core` themselves.

## Dependency

Maven coordinate: `me.ahoo.wow:wow-core`. Use the Wow BOM to align versions:

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-core")
}
```

`wow-core` exposes `wow-api` and its reactive runtime contracts through its API dependency surface.

## Public boundary

The main public packages are:

- `me.ahoo.wow.command` for `CommandGateway`, command-message construction, and wait results;
- `me.ahoo.wow.eventsourcing` for `EventStore`, snapshots, and aggregate restoration contracts;
- `me.ahoo.wow.event` and `messaging` for domain-event and message buses;
- `me.ahoo.wow.modeling`, `projection`, `saga`, and `runtime` for domain execution and runtime lifecycle.

Kafka/Redis buses, MongoDB/Redis/Elasticsearch stores, WebFlux routes, and Spring auto-configuration belong to their extension modules. `wow-core` does not select or deploy that infrastructure for an application.

## Minimal example

`CommandGateway` returns Reactor types. This example waits until aggregate processing reaches `PROCESSED`:

```kotlin
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import reactor.core.publisher.Mono

fun send(
    commandGateway: CommandGateway,
    command: CommandMessage<AddCartItem>,
): Mono<CommandResult> = commandGateway.sendAndWaitForProcessed(command)
```

A successful `PROCESSED` result does not prove that a snapshot, projection, event handler, or Saga has completed. Select the corresponding wait plan when the caller needs that evidence.

## Verify

```bash
./gradlew :wow-core:check
```

## Guides

- [Architecture Overview](../documentation/docs/en/guide/advanced/architecture.md)
- [Command Gateway](../documentation/docs/en/guide/command-gateway.md)
- [Event Store](../documentation/docs/en/guide/eventstore.md)
- [Module Dependencies](../documentation/docs/en/guide/advanced/module-dependencies.md)

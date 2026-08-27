---
title: Testing Wow Applications
description: Build domain, HTTP, real-adapter, recovery, and security release gates for applications using Wow.
outline: deep
---

# Testing Wow Applications

This page is for teams building business systems with Wow. The goal is not to reuse the Wow repository test tasks, but to prove that your domain model, generated metadata, runtime wiring, and production adapters work together.

## Test Layers

| Layer | What it proves | Recommended entry point |
| --- | --- | --- |
| Domain specification | Commands, rejection rules, events, and sourced state are correct | `AggregateSpec`, `SagaSpec` |
| Compile contract | KSP generates non-empty `META-INF/wow-metadata.json` | `clean kspKotlin test` |
| HTTP vertical slice | Spring wiring, WebFlux routes, wait stages, and state loading form a closed loop | `@SpringBootTest` + `WebTestClient` |
| Real adapters | Production EventStore, SnapshotStore, broker, and serialization contracts | Testcontainers or an isolated environment |
| Recovery and upgrade | Restarts, historical events, snapshots, projections, and event revisions remain recoverable | backup/restore and replay tests |
| Security boundary | Anonymous, unauthorized, cross-tenant, and raw-query access fails closed | Spring Security/CoSec integration tests |

Run the narrowest business modules first for daily changes:

```shell
./gradlew :domain:test
./gradlew :server:test
./gradlew check
```

Replace `domain` and `server` with the actual application module names.

## 1. Domain Specifications

Every aggregate rule should cover:

- emitted events and final state for a successful command;
- business-rule rejection;
- required create, update, delete, and recovery branches;
- deterministic state from the same historical events;
- correct and idempotent downstream commands from a saga.

See [Test Suite](./test-suite.md) for the DSL.

## 2. Metadata Gate

Every module containing Wow-annotated models must generate metadata:

```shell
./gradlew clean kspKotlin test
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

Do not hand-write or commit generated files. Check every module that actually applies KSP in a multi-module application.

## 3. Minimal HTTP Vertical Slice

The following test uses in-memory adapters and needs no Docker. Replace the route and body with the first aggregate in your application:

```kotlin
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WowCommandFlowIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `http command reaches sourced state`() {
        val aggregateId = "it-${UUID.randomUUID()}"
        val client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()

        client.post()
            .uri("/tenant/test/demo")
            .header("Command-Wait-Stage", "SNAPSHOT")
            .header("Command-Aggregate-Id", aggregateId)
            .header("Command-Request-Id", "request-$aggregateId")
            .bodyValue(mapOf("data" to "integration"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.succeeded").isEqualTo(true)
            .jsonPath("$.stage").isEqualTo("SNAPSHOT")
            .jsonPath("$.aggregateId").isEqualTo(aggregateId)

        client.get()
            .uri("/tenant/test/demo/$aggregateId/state/1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(aggregateId)
            .jsonPath("$.data").isEqualTo("integration")
    }
}
```

This proves that KSP metadata, Spring wiring, routing, command processing, `SNAPSHOT` waiting, and historical state reconstruction are connected. It does not prove Kafka delivery, persistent storage, restart recovery, or production authorization.

## 4. Real Adapters and Restart

Add a container or isolated-environment test for every production adapter and verify at least:

1. the same Starter capabilities and configuration used in production;
2. commands write to the real EventStore and state remains readable after restart;
3. the same `requestId` cannot execute twice;
4. broker redelivery does not repeat projections or external side effects;
5. snapshots match a full event replay;
6. projection rebuild, consumer offsets, and compensation tasks have repeatable procedures.

## 5. Security and Isolation

Protected applications should retain these negative tests:

| Scenario | Expected result |
| --- | --- |
| Anonymous protected command/query | `401` or `403` |
| Forged tenant, owner, or space | rejected without expanding scope |
| Missing principal ABAC tags | fail closed; never fall back to `MatchAllFilter` |
| Cross-tenant/owner query | no unauthorized records returned |
| Ordinary request reaches raw `*QueryServiceFactory` | no reachable entry point |

See [Data Access Control](./data-access.md#required-security-closure) for the complete boundary.

## 6. Release Completion Gates

- domain success, rejection, and recovery branches pass;
- KSP metadata is non-empty and loaded by the runtime;
- the HTTP vertical slice passes;
- state survives restart with the real store;
- redelivery, version conflict, and external-dependency failure paths pass;
- authentication, tenant isolation, and ABAC negative tests pass;
- backup, restore, replay, and rollback drills have evidence;
- traces, metrics, and alerts work in the target environment.

When changing the Wow framework itself, use the repository tasks in [Framework Tests and Benchmarks](./test-runtime.md).

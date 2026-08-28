---
title: Getting Started
description: Prove a complete Wow command, event, snapshot, and versioned-state path with the current project template.
outline: deep
---

# Getting Started

Use the [Wow Project Template](https://github.com/Ahoo-Wang/wow-project-template) to prove one complete vertical slice before replacing the demo domain:

```text
domain test → generated route → HTTP command → SNAPSHOT wait → state at version 1
```

This path uses the template's checked-in in-memory buses, event store, and snapshot store. It does not require Kafka, MongoDB, or Redis.

## Verified Baseline

This page was executed on 2026-08-27 against template commit [`1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9`](https://github.com/Ahoo-Wang/wow-project-template/tree/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9):

| Component | Verified value |
| --- | --- |
| Template Wow version | `8.13.0` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| Gradle Wrapper | `9.7.1` |
| JDK used for the run | `17.0.7` |

The current Wow documentation source is `8.15.0`, while the verified template pins `8.13.0`. The repositories evolve independently. Always inspect the cloned template's [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/wow-project-template/blob/main/gradle/libs.versions.toml); change versions only as one reviewed compatibility baseline for a selected [Wow release](https://github.com/Ahoo-Wang/Wow/releases).

## Before You Start

- JDK 17 or later
- Git
- `curl`
- the checked-in Gradle Wrapper; no global Gradle installation is required

Use a disposable clone for the first pass so local runtime files cannot pollute an application repository:

[![Use this template](https://img.shields.io/badge/Use%20this%20template-2ea44f?style=for-the-badge&logo=github)](https://github.com/new?template_name=wow-project-template&template_owner=Ahoo-Wang)

```shell
git clone https://github.com/Ahoo-Wang/wow-project-template.git
cd wow-project-template
git fetch --depth 1 origin 1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9
git checkout --detach FETCH_HEAD
git rev-parse HEAD
grep '^wow = ' gradle/libs.versions.toml
```

The checkout command pins the exact commit used for the expected results below. If you intentionally keep a different template `HEAD`, do not mix it with this page's fixed expectations: repeat the domain check, startup, route, command, and versioned-state validation against that `HEAD` and record the new baseline. If you create a repository with the template button instead, rename `rootProject.name` in `settings.gradle.kts` after the first successful run.

## The 30-Minute Target Path

The functional path below has been exercised end to end. Thirty minutes remains a target because first-time developer wall-clock completion has not been measured; this is not a completed human usability study.

### 1. Prove the Domain Model

Run the exact module check defined by the template:

```shell
./gradlew :domain:check --console=plain
```

The check compiles KSP metadata, runs `DemoSpec` and `DemoSagaSpec`, and enforces the domain coverage rule. Success ends with `BUILD SUCCESSFUL`.

In the verification environment, the first run exhausted Gradle's default `384 MiB` Metaspace during `:api:kspKotlin`. If the failure is specifically `OutOfMemoryError: Metaspace`, retry without editing the template:

```shell
./gradlew :domain:check --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=1g'
```

Do not use this retry to hide a compilation, test, coverage, or dependency failure.

The behavior under test comes from the template itself:

- [`Demo`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/Demo.kt) returns `DemoCreated` and `DemoUpdated`;
- [`DemoState`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/DemoState.kt) applies those events;
- [`DemoSpec`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/test/kotlin/me/ahoo/wow/template/domain/demo/DemoSpec.kt) verifies command → event → state;
- [`DemoSaga`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/DemoSaga.kt) reacts to creation with an update command.

### 2. Start the Service with Versioned Configuration

The template's `:server:run` task uses `server/` as its working directory and expects `server/config/`. Point that path to the checked-in `server/src/main/resources` instead of copying configuration:

```shell
mkdir -p server/logs
test -e server/config || ln -s src/main/resources server/config
./gradlew :server:run --console=plain
```

If the earlier Metaspace failure occurred, apply the same command-line JVM setting to `:server:run`. Wait until the log contains both:

```text
Netty started on port 8080 (http)
Started ServerKt
```

The sourced configuration is [`server/src/main/resources/application.yaml`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/server/src/main/resources/application.yaml). It selects `in_memory` command/event buses, event store, snapshot store, and state-event bus, plus a manual CosId machine ID for one local instance.

`server/config` and `server/logs/` are local runtime artifacts and are not ignored by the verified template. A Metaspace crash may also leave an untracked heap dump such as `java_pid*.hprof` in the repository root. Before committing an application repository, inspect all three with:

```shell
git status --short -- '*.hprof' server/config server/logs
```

Remove only the listed local artifacts after inspection. On Windows, create an equivalent directory link or point `spring.config.location` at the checked-in resource directory for the local run.

Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html). The generated OpenAPI should include `POST /tenant/{tenantId}/demo` and `/tenant/{tenantId}/demo/{id}/state/{version}`.

<a id="send-the-first-real-command"></a>

### 3. Send the First Real Command

Keep the service running and execute this in another terminal:

```shell
curl -sS -X POST \
  'http://localhost:8080/tenant/tenant-1/demo' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SNAPSHOT' \
  -H 'Command-Aggregate-Id: demo-1' \
  -H 'Command-Request-Id: quickstart-demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"data":"hello-wow"}'
```

The generated route comes from the template's [`CreateDemo`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/api/src/main/kotlin/me/ahoo/wow/template/api/demo/CreateDemo.kt). Verify these response fields rather than relying on HTTP `200` alone:

```json
{
  "stage": "SNAPSHOT",
  "aggregateId": "demo-1",
  "aggregateVersion": 1,
  "requestId": "quickstart-demo-1",
  "errorCode": "Ok",
  "succeeded": true
}
```

The response contains additional generated IDs and timing data; those values vary by run.

### 4. Read the Versioned Sourced State

Read the aggregate at exactly version `1`:

```shell
curl -sS \
  'http://localhost:8080/tenant/tenant-1/demo/demo-1/state/1' \
  -H 'accept: application/json'
```

The verified response is:

```json
{"id":"demo-1","data":"hello-wow"}
```

This proves the `CreateDemo` route, aggregate decision, `DemoCreated` persistence, sourcing handler, snapshot wait, and versioned state reconstruction. It also avoids a race with `DemoSaga`: that saga subsequently sends `UpdateDemo(data = "updated")`, so the unversioned current-state endpoint eventually returns:

```shell
curl -sS \
  'http://localhost:8080/tenant/tenant-1/demo/demo-1/state' \
  -H 'accept: application/json'
```

```json
{"id":"demo-1","data":"updated"}
```

::: tip Repeating the path
`Command-Request-Id` is the idempotency key. For another trial, change both the request ID and aggregate ID, or restart the service to clear its in-memory data.
:::

## Completion Gate

The first slice is complete only when all five observations hold:

- `:domain:check` succeeds;
- startup loads the generated `META-INF/wow-metadata.json` resources and listens on 8080;
- Swagger/OpenAPI contains the generated command and versioned-state routes;
- the HTTP command returns `succeeded: true`, `stage: SNAPSHOT`, and aggregate version `1`;
- the version `1` state is exactly `{"id":"demo-1","data":"hello-wow"}`.

## Replace the Demo Safely

The template's module responsibilities are the migration path:

| Module/path | Replace or preserve |
| --- | --- |
| `api` | Replace demo commands/events and update `DemoService` aggregate metadata |
| `domain` | Replace `Demo`, `DemoState`, `DemoSaga`, and their specs together |
| `server` | Preserve runtime wiring; add only extensions required by the target environment |
| `server/src/main/resources` | Keep configuration versioned; split environment values without committing secrets |
| `gradle/libs.versions.toml` | Pin one tested dependency baseline |

After each domain change, rerun `./gradlew :domain:check`. Do not add Kafka, MongoDB, Redis, or Elasticsearch until durable storage, multi-instance messaging, or a specific query backend is an actual requirement.

The template also wires [CosId](https://github.com/Ahoo-Wang/CosId) for IDs and contains a TypeScript client generated with [Fetcher](https://github.com/Ahoo-Wang/fetcher); keep or remove those pieces according to the application's published contract.

## Next Steps

- Understand the terms used above: [Core Concepts](./core-concepts.md)
- Replace the demo model: [Aggregate and Invariants](./domain/aggregate.md)
- Choose completion semantics: [Completion Semantics](./command/completion.md)
- Add application gates: [Testing Wow Applications](./application-testing.md)
- Build a read model: [Projection](./projection.md) and [Query Service](./query.md)
- Select runtime integrations: [Configuration](./configuration.md) and [Spring Boot Starter](./extensions/spring-boot-starter.md)

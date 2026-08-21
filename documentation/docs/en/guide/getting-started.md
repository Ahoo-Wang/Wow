---
title: Getting Started
description: Get started with the Wow framework using the project template to quickly create a DDD project.
---

# Getting Started

> Use the [Wow Project Template](https://github.com/Ahoo-Wang/wow-project-template) to quickly create a DDD project based on the _Wow_ framework.

This page completes one minimal vertical slice: verify the rules with a domain test, then use a real HTTP command to prove **command → event → sourced state**.

## Before You Start

- JDK 17 or later.
- Git.
- Use the checked-in Gradle Wrapper; no global Gradle installation is required.
- The template defaults to in-memory buses, event storage, and snapshot storage, so the first run does not require Kafka, MongoDB, or Redis.

::: warning Confirm the version first
The Wow Project Template evolves independently and is not guaranteed to match the Wow source tag documented by this site. After creating a project, inspect the template's [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/wow-project-template/blob/main/gradle/libs.versions.toml), then pin the version for your selected [Wow release](https://github.com/Ahoo-Wang/Wow/releases).
:::

## The 10-Minute Path

1. Create a repository from the template and clone it locally.
2. Change `rootProject.name` in `settings.gradle.kts` to your project name.
3. Run the domain checks and start the service:

```shell
./gradlew :domain:check
mkdir -p server/logs
test -e server/config || ln -s src/main/resources server/config
./gradlew :server:run
```

4. Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).
5. Submit one `CreateDemo` command as shown below and wait for `SNAPSHOT`.
6. Load version `1` of `demo-1` and confirm that `DemoCreated` produced the state.

The first vertical slice is complete only when the domain test, HTTP command, and versioned-state read all pass. The remaining sections explain how to understand and replace the template's `Demo` model.

### Send the First Real Command

The template's `CreateDemo` generates `POST /tenant/{tenantId}/demo`. Keep the service running and execute this in another terminal:

```shell
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/demo' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SNAPSHOT' \
  -H 'Command-Aggregate-Id: demo-1' \
  -H 'Command-Request-Id: quickstart-demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"data":"hello-wow"}'
```

Inspect the command result rather than relying on the HTTP status alone:

- `succeeded` is `true`;
- `stage` is `SNAPSHOT`;
- `aggregateId` is `demo-1`;
- `aggregateVersion` is `1`.

Then load the event-sourced aggregate state at version `1`:

```shell
curl \
  'http://localhost:8080/tenant/tenant-1/demo/demo-1/state/1' \
  -H 'accept: application/json'
```

The response should be `{"id":"demo-1","data":"hello-wow"}`. This proves more than route availability: the aggregate processed the command and `DemoCreated` can rebuild state at version `1`.

The template also contains `DemoSaga`. After `DemoCreated`, it sends `UpdateDemo(data = "updated")`. As a result, the unversioned current-state endpoint `/tenant/tenant-1/demo/demo-1/state` eventually returns `data = "updated"`, demonstrating the asynchronous Saga flow that follows creation.

::: tip Repeating the request
`Command-Request-Id` is the idempotency key. Repeating the same request is detected as a duplicate. For another trial, change both the request ID and aggregate ID, or restart the service that uses in-memory storage.
:::

## Create Project

[![Use this template](https://img.shields.io/badge/Use%20this%20template-2ea44f?style=for-the-badge&logo=github)](https://github.com/new?template_name=wow-project-template&template_owner=Ahoo-Wang)

Click the button above to create a new repository from [Wow Project Template](https://github.com/Ahoo-Wang/wow-project-template), then clone it locally.

- Modify the `settings.gradle.kts` file, change `rootProject.name` to the project name
- Modify `api/{package}/DemoService`
- Modify `domain/{package}/DemoBoundedContext`


## Project Structure

| Directory/file | Responsibility |
| --- | --- |
| `api` | Commands, domain events, and query view models that form the published language between modules |
| `domain` | Aggregates, business invariants, sourcing handlers, and domain tests |
| `server` | Host wiring for the domain and Wow extensions, plus the application entry point and `Dockerfile` |
| `config` | Versioned starting points for application and environment configuration |
| `client` | Type-safe TypeScript clients generated with [fetcher-generator](https://github.com/Ahoo-Wang/fetcher) |
| `code-coverage-report` | Aggregated coverage reports and verification gates |
| `dependencies` / `bom` | Central dependency constraints and BOM publication |
| `gradle/libs.versions.toml` | Pinned Wow and third-party dependency versions |
| `deploy` | Kubernetes manifests that require review for the target environment |
| `document` | Context maps, UML, and other project-level design material |

## Add External Infrastructure (Optional)

Keep the template's `in_memory` configuration for the first run. Add an extension only when durable storage, multi-instance messaging, or a specific query backend becomes an actual requirement.

1. Use _Kafka_ as the messaging engine: command bus and event bus

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-kafka")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-kafka'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-kafka</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

2. Use _MongoDB_ as event store and snapshot store

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```
```xml [Maven]
  <dependencies>
    <dependency>
        <groupId>me.ahoo.wow</groupId>
        <artifactId>wow-mongo</artifactId>
        <version>${wow.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
    </dependency>
  </dependencies>
```
:::

3. Use [CosId](https://github.com/Ahoo-Wang/CosId) as global and aggregate root ID generator

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.cosid:cosid-mongo")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.cosid:cosid-mongo'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.cosid</groupId>
    <artifactId>cosid-mongo</artifactId>
    <version>${cosid.version}</version>
</dependency>
```
:::

## External Infrastructure Configuration Example

The following example replaces the in-memory first-run setup with Kafka and MongoDB. It is a configuration starting point, not a production manifest: authentication, TLS, capacity, backup/restore, and alerting must be designed for the target environment.

```yaml {20,23,29,34}
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid
          - cosidGenerator
          - cosidStringGenerator
springdoc:
  show-actuator: true
spring:
  application:
    name: <your-service-name>
  mongodb:
    uri: <mongodb-uri>

cosid:
  machine:
    enabled: true
    distributor:
      type: mongo
  generator:
    enabled: true
wow:
  kafka:
    bootstrap-servers: <kafka-bootstrap-servers>
```

## Start Service

```shell
mkdir -p server/logs
test -e server/config || ln -s src/main/resources server/config
./gradlew :server:run
```

The template's `run` task uses `server/` as its working directory, reads runtime configuration there, and writes GC logs there. The symlink above makes `server/config` point directly to the version-controlled `server/src/main/resources`, avoiding two configuration copies; it is only for local execution and must not be committed. On Windows, create an equivalent directory link or update `spring.config.location` in `server/build.gradle.kts` to the actual configuration directory.

![Start Service](/images/getting-started/run-server.png)

> Access: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

![Swagger-UI](/images/getting-started/swagger-ui.png)

## Domain Modeling

::: tip Aggregate Pattern
In the following examples, we will use the [aggregate pattern](modeling) for modeling.
:::

### Command Aggregate Root

The *command aggregate root* is responsible for receiving command handler functions, executing corresponding business logic, and returning domain events.

```kotlin {2,5}
@Suppress("unused")
@AggregateRoot
class Demo(private val state: DemoState) {

    @OnCommand
    fun onCreate(command: CreateDemo): DemoCreated {
        return DemoCreated(
            data = command.data,
        )
    }

    @OnCommand
    fun onUpdate(command: UpdateDemo): DemoUpdated {
        return DemoUpdated(
            data = command.data
        )
    }
}
```

### State Aggregate Root

The *state aggregate root* is responsible for maintaining aggregate state data, receiving and processing domain events, and changing aggregate state data.

::: warning
The state aggregate root's `setter` accessor is set to `private` to prevent the command aggregate root from directly changing aggregate state data.
:::

```kotlin {3,5}
class DemoState(override val id: String) : Identifier {
    var data: String? = null
        private set

    @OnSourcing
    fun onCreated(event: DemoCreated) {
        data = event.data
    }

    @OnSourcing
    fun onUpdated(event: DemoUpdated) {
        data = event.data
    }
}
```

## Writing Unit Tests

To ensure code quality, we need to write unit tests to verify that aggregate root behavior meets expectations.

### Test Aggregate Root

```kotlin
class DemoSpec : AggregateSpec<Demo, DemoState>({
  on {
    val create = CreateDemo(
      data = "data"
    )
    whenCommand(create) {
      expectNoError()
      expectEventType(DemoCreated::class)
      expectState {
        data.assert().isEqualTo(create.data)
      }
      fork {
        val update = UpdateDemo(
          data = "newData"
        )
        whenCommand(update) {
          expectNoError()
          expectEventType(DemoUpdated::class)
          expectState {
            data.assert().isEqualTo(update.data)
          }
        }
      }
    }
  }
})
```

## Verify Changes

In an application created from the template, begin with the narrow checks that directly cover the domain model:

```shell
./gradlew :domain:check
./gradlew :domain:jacocoTestCoverageVerification
./gradlew detekt
```

If you are changing the Wow framework itself, use the [Contributor Guide](../onboarding/contributor-guide.md) and [Test Runtime](./test-runtime.md) instead. Application repositories should design release and deployment for their own registry and runtime environment rather than copying a pipeline tied to a specific cloud provider or credential model.

## Next Steps

- Replace the example domain: [Aggregate Modeling](./modeling.md)
- Understand write APIs and completion stages: [Command Gateway](./command-gateway.md)
- Build a read model: [Projection](./projection.md) and [Query Service](./query.md)
- Switch to external storage or messaging: [Configuration](./configuration.md) and [Extensions](./extensions/spring-boot-starter.md)

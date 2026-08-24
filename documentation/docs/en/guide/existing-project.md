---
title: Add Wow to an Existing Spring Boot Project
description: Add Wow to an existing Gradle, Kotlin, and Spring Boot project and verify code generation, command routing, and the minimal runtime flow.
outline: deep
---

# Add Wow to an Existing Spring Boot Project

The project template is the shortest path for a new service. For an existing Spring Boot service, use this page to prove the following flow with in-memory infrastructure first:

```text
KSP metadata → Spring auto-configuration → HTTP command → aggregate → event/snapshot
```

## Version Baseline

These versions match the current Wow `8.11.5` source baseline:

| Component | Version |
| --- | --- |
| JDK | 17+ |
| Wow | `8.11.5` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| CosId | `3.2.1` |

For another Wow release, inspect that tag's `gradle/libs.versions.toml` and release notes. Do not replace only one dependency version.

## 1. Configure the Build

The following is a minimal single-module Kotlin `build.gradle.kts`. In a multi-module service, put commands and events in `api`, aggregates and domain tests in `domain`, and the Starter and WebFlux wiring in `server`. Every module containing Wow-annotated models must apply KSP and depend on `wow-compiler`.

```kotlin
plugins {
    id("org.springframework.boot") version "4.1.1"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("me.ahoo.wow:wow-bom:8.11.5"))
    ksp(platform("me.ahoo.wow:wow-bom:8.11.5"))

    ksp("me.ahoo.wow:wow-compiler")
    implementation("me.ahoo.wow:wow-spring-boot-starter")
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.wow:webflux-support")
        }
    }
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("me.ahoo.cosid:cosid-spring-boot-starter:3.2.1")

    testImplementation("me.ahoo.wow:wow-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

The Spring Boot BOM aligns Spring ecosystem dependencies, while `wow-bom` aligns Wow modules; keep both. Starter capabilities are Gradle feature variants, so request `webflux-support` to include Wow's command and query route handlers. See [Spring Boot Starter](./extensions/spring-boot-starter.md) for other backends.

::: warning Maven boundary
The repository's verified automatic metadata pipeline is Gradle + KSP. Maven can declare Wow runtime dependencies, but this site does not provide a verified equivalent Maven code-generation path. If generated OpenAPI and handler metadata are required, use Gradle or independently verify `META-INF/wow-metadata.json` before release.
:::

## 2. Use a First-Run Configuration

Wow's production-oriented defaults expect Kafka and MongoDB. Without those integrations, select in-memory implementations explicitly. `PrepareKey` supports MongoDB or Redis only, so disable it for the first run.

```yaml
spring:
  application:
    name: demo-service

cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true

wow:
  prepare:
    enabled: false
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
      strategy: all
    state:
      bus:
        type: in_memory
```

This configuration is for local adoption verification only. Data disappears when the process exits, and it does not provide multi-instance delivery or general conditional queries.
`manual.machine-id` is also limited to a single-instance first run. Use a distributor that guarantees unique machine IDs before running multiple instances.

## 3. Add a Domain Model

Follow [Aggregate Modeling](./modeling.md) to define:

1. a bounded context declaration with `@BoundedContext`;
2. commands, domain events, and `@CommandRoute`;
3. a command aggregate with `@AggregateRoot` and `@OnCommand`;
4. a state aggregate with `@OnSourcing`;
5. at least one `AggregateSpec`.

Do not duplicate invariants in controllers or database scripts. HTTP hands a command to Wow, the aggregate makes the decision, and only domain events change sourced state.

## 4. Verify Code Generation

```shell
./gradlew clean kspKotlin test
test -s build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

The second command exits with `0` and no output when KSP produced non-empty metadata. In a multi-module build, inspect the actual KSP module instead, for example:

```shell
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

At runtime, `MetadataSearcher` merges every `META-INF/wow-metadata.json` resource on the classpath. Do not hand-write this file or commit generated `build/` output.

## 5. Start and Verify Routes

```shell
./gradlew bootRun
```

Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) and confirm that the aggregate's command and state endpoints exist. Then submit a command and load a versioned state as shown in [Getting Started](./getting-started.md#send-the-first-real-command).

## Completion Gate

Adoption is complete only when all of the following are true:

- `test` passes and `AggregateSpec` verifies the domain rules;
- every annotated model module generates `META-INF/wow-metadata.json`;
- startup has no missing Kafka, MongoDB, or route-handler wiring error;
- the first command does not fail because `GlobalIdGenerator` is uninitialized;
- Swagger UI contains the expected command route;
- a command with a fixed `requestId` reaches a successful stage;
- the sourced state can be loaded from the state endpoint.

If the service starts but routes are missing, follow [Troubleshooting: Missing Metadata or Generated Code](./troubleshooting.md#missing-metadata-or-generated-code) instead of bypassing code generation with a handwritten controller.

## Next Steps

- Split `api`, `domain`, and `server`: [Module Dependencies](./advanced/module-dependencies.md).
- Select durable messaging and storage: [Spring Boot Starter](./extensions/spring-boot-starter.md).
- Define completion semantics: [Command Gateway](./command-gateway.md).
- Build application release gates: [Testing Wow Applications](./application-testing.md).
- Establish a policy for persisted event changes: [Event Evolution](./advanced/event-evolution.md).

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Wow is a modern reactive CQRS microservice framework based on DDD (Domain-Driven Design) and Event Sourcing. Written in Kotlin, it runs on JVM 17+ and integrates with Spring Boot 4.x (Wow 8.x). All packages are under `me.ahoo.wow`.

## Build & Test Commands

### Gradle (root project)
```bash
./gradlew <module>:check          # Run tests + lint for a module
./gradlew <module>:test           # Run tests only
./gradlew <module>:clean <module>:check --stacktrace  # Clean build + test (CI pattern)
./gradlew detekt                  # Run static analysis
./gradlew detekt --auto-correct   # Auto-fix lint issues
./gradlew build                   # Build all (excludes example-server apps)
```

### Run a single test class or method
```bash
./gradlew <module>:test --tests "me.ahoo.wow.example.domain.order.OrderTest"
./gradlew <module>:test --tests "me.ahoo.wow.example.domain.order.OrderTest.test method name"
```

### Compensation Dashboard (React/TypeScript)
```bash
cd compensation/dashboard
npm run dev         # Dev server
npm run test        # Vitest tests
npm run lint        # ESLint
npm run build       # Production build
npm run coverage    # Coverage report
```

### Documentation (VitePress)
```bash
cd documentation
npm run docs:dev    # Dev server
npm run docs:build  # Production build
```

## Architecture

### Module Dependency Graph (core flow)

```
wow-api (pure API contracts: CommandMessage, DomainEvent, Named, AggregateId, etc.)
  └─> wow-core (framework engine: aggregates, command bus, event sourcing, projections, sagas)
       ├─> wow-spring (Spring integration)
       │    └─> wow-spring-boot-starter (auto-configuration with feature capabilities)
       ├─> wow-query (query model support)
       ├─> wow-kafka (command/event bus via Kafka)
       ├─> wow-mongo (event store + snapshot store via MongoDB)
       ├─> wow-redis (event store + snapshot store via Redis)
       ├─> wow-r2dbc (event store via R2DBC)
       ├─> wow-elasticsearch (projection via Elasticsearch)
       ├─> wow-webflux (Spring WebFlux command endpoint integration)
       ├─> wow-opentelemetry (tracing/metrics)
       └─> wow-cosec (authorization)
```

### Key Sub-Systems

- **wow-compiler** — KSP (Kotlin Symbol Processing) processor. Generates command routing, event handling metadata, and OpenAPI specs at compile time. Domain projects apply it via `ksp(project(":wow-compiler"))`.
- **wow-test** — Unit testing DSL with `AggregateSpec`/`AggregateVerifier` and `SagaSpec`/`SagaVerifier` using Given-When-Expect pattern.
- **wow-tck** — Technology Compatibility Kit for integration tests (uses Testcontainers for Kafka, MongoDB, Elasticsearch).
- **wow-schema** — JSON Schema generation from Wow command/event models.
- **wow-bi** — BI sync script generator.
- **wow-cocache** — CoCache-based projection caching.
- **wow-apiclient** — RESTful API client using CoApi.

### Spring Boot Starter Feature Capabilities

`wow-spring-boot-starter` uses Gradle feature variants to declare optional capabilities:
`mongo-support`, `r2dbc-support`, `redis-support`, `mock-support`, `kafka-support`, `webflux-support`, `elasticsearch-support`, `opentelemetry-support`, `openapi-support`, `cosec-support`.

### Compensation Module

Located under `compensation/` with its own aggregate domain (`wow-compensation-domain`), API, core, and server modules. Includes a React-based dashboard (`compensation/dashboard/`) built with Ant Design, Vite, and TypeScript.

### Example Projects

- `example/` — Order & Cart domain (Kotlin), demonstrates aggregates, sagas, projections
- `example/transfer/` — Bank Transfer domain (Java), demonstrates simple event sourcing

## Testing

- **Framework**: JUnit 6 (Jupiter), AssertJ via `me.ahoo.test:fluent-assert-core` (use `.assert()` extension, not AssertJ's `assertThat()`)
- **Test pattern**: Given → When → Expect (AggregateSpec, SagaSpec)
- **Domain test example**: `example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/`
- **Coverage enforcement**: 80% minimum on domain modules (`jacocoTestCoverageVerification`)
- **Test retry**: In CI, failed tests retry up to 2 times (max 20 failures) via Gradle test-retry plugin
- **Mocking**: MockK (`io.mockk:mockk`)
- **Integration tests**: Testcontainers for Kafka, MongoDB, MariaDB (R2DBC), Elasticsearch

## Code Style & Conventions

- **Language**: Kotlin 2.3, JVM target 17
- **Linter**: Detekt with custom config at `config/detekt/detekt.yml` (relaxed rules: max line length 300, many rules disabled)
- **Reactive**: Project Reactor (`Mono`/`Flux`) throughout — all command/event paths are non-blocking
- **Serialization**: Jackson (tools.jackson for framework, fasterxml for Spring compat)
- **ID generation**: CosId (`me.ahoo.cosid`)
- **Logging**: kotlin-logging (`io.github.oshai:kotlin-logging-jvm`) + SLF4J + Logback
- **Coroutines**: kotlinx-coroutines with Reactor interop
- **Copyright header**: Apache 2.0 license header on all source files

## Version Management

Version is defined in `gradle.properties` (currently `8.3.2`). Bumping is done by updating the `version` property. The `wow-dependencies` module acts as a centralized BOM/platform for all third-party dependency versions.

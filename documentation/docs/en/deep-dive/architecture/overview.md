---
title: Deep Dive — Architecture Overview
description: Technical deep dive into the Wow Framework architecture — reactive CQRS engine, event sourcing, and module design
---

# Architecture Overview

This is a deep technical analysis of the Wow Framework's architecture. For a gentler introduction, see the [Architecture Guide](../../guide/architecture).

## The Core Insight

Wow's architecture is built around a single principle: **"Domain Model as a Service"**. Write your aggregate, and the framework handles everything else — command routing, event persistence, projection updates, and API generation.

```mermaid
flowchart TB
    subgraph External["External Layer"]
        API["REST API<br>(WebFlux)"]
        UI["Compensation<br>Dashboard"]
    end

    subgraph Core["Core Engine"]
        direction TB
        GW["Command Gateway"]
        CB["Command Bus"]
        AG["Aggregate Engine"]
        EB["Event Bus"]
    end

    subgraph Storage["Storage Layer"]
        ES["Event Store<br>MongoDB/Redis/R2DBC"]
        SS["Snapshot Store"]
        PS["Projection Store<br>MongoDB/Elasticsearch"]
    end

    subgraph Extensions["Extensions"]
        PR["Projections"]
        SG["Sagas"]
        QS["Query Service"]
        CP["Compensation"]
    end

    API -->|Command| GW
    GW --> CB
    CB --> AG
    AG -->|Events| ES
    ES --> EB
    EB --> PR
    EB --> SG
    EB --> CP
    PR --> PS
    SG -->|New Commands| CB
    QS --> PS

    style API fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style UI fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style GW fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AG fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ES fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style PS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style PR fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SG fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style QS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CP fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

<!-- Sources: wow-core/src/main/kotlin/me/ahoo/wow/, wow-spring-boot-starter/, settings.gradle.kts -->

## Module Dependency Graph

```
wow-api (pure API contracts)
  └─> wow-core (framework engine)
       ├─> wow-spring (Spring integration)
       │    └─> wow-spring-boot-starter (auto-configuration)
       ├─> wow-query (query model support)
       ├─> wow-kafka (command/event bus via Kafka)
       ├─> wow-mongo (event store + snapshot via MongoDB)
       ├─> wow-redis (event store + snapshot via Redis)
       ├─> wow-r2dbc (event store via R2DBC)
       ├─> wow-elasticsearch (projection via Elasticsearch)
       ├─> wow-webflux (Spring WebFlux integration)
       ├─> wow-opentelemetry (tracing/metrics)
       └─> wow-cosec (authorization)
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Reactive (Project Reactor) | Non-blocking I/O for maximum throughput |
| KSP over KAPT | Compile-time code generation, faster builds |
| Spring Boot auto-configuration | Zero-boilerplate setup |
| Pluggable event store | Swap backends without changing domain code |
| Given-When-Expect testing | Readable, maintainable test suite |
| Dark launch support | Feature flags for gradual rollouts |

## Related Pages

- [Command Bus](./command-bus) — Command routing and wait strategies
- [Event Bus](./event-bus) — Event distribution and processing
- [Aggregate Lifecycle](./aggregate-lifecycle) — Aggregate state flow
- [Event Store](../data/event-store) — Event persistence layer
- [Spring Boot Integration](../integrations/spring-boot) — Framework setup

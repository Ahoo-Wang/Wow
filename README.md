<p align="center" style="text-align:center;">
  <img width="150" src="documentation/docs/public/images/logo.svg" alt="Wow"/>
</p>

<h1 align="center">Wow</h1>

<p align="center"><strong>Domain Model as a Service</strong></p>

<p align="center">Modern Reactive CQRS Architecture Microservice Development Framework<br>Based on DDD & Event Sourcing</p>

<p align="center">
  <a href="https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202-4EB1BA.svg" alt="License"/></a>
  <a href="https://github.com/Ahoo-Wang/Wow/releases"><img src="https://img.shields.io/github/release/Ahoo-Wang/Wow.svg" alt="GitHub release"/></a>
  <a href="https://central.sonatype.com/artifact/me.ahoo.wow/wow-core"><img src="https://img.shields.io/maven-central/v/me.ahoo.wow/wow-core" alt="Maven Central"/></a>
  <a href="https://app.codacy.com/gh/Ahoo-Wang/Wow/dashboard"><img src="https://app.codacy.com/project/badge/Grade/cfc724df22db4f9387525258c8a59609" alt="Codacy"/></a>
  <a href="https://codecov.io/gh/Ahoo-Wang/Wow"><img src="https://codecov.io/gh/Ahoo-Wang/Wow/branch/main/graph/badge.svg?token=uloJrLoQir" alt="Codecov"/></a>
  <a href="https://github.com/Ahoo-Wang/Wow/actions/workflows/integration-test.yml"><img src="https://github.com/Ahoo-Wang/Wow/actions/workflows/integration-test.yml/badge.svg" alt="CI"/></a>
  <a href="https://kotlin.link/"><img src="https://kotlin.link/awesome-kotlin.svg" alt="Awesome Kotlin"/></a>
  <a href="https://deepwiki.com/Ahoo-Wang/Wow"><img src="https://deepwiki.com/badge.svg" alt="DeepWiki"/></a>
</p>

<p align="center">
  <strong>Domain-Driven</strong> &middot; <strong>Event-Driven</strong> &middot; <strong>Test-Driven</strong> &middot; <strong>Declarative Design</strong> &middot; <strong>Reactive</strong> &middot; <strong>CQRS</strong> &middot; <strong>Event Sourcing</strong>
</p>

<p align="center">
  <a href="https://wow.ahoo.me/">English</a> &middot; <a href="https://wow.ahoo.me/zh/">中文</a>
</p>

---

## Quick Start

[![Use this template](https://img.shields.io/badge/Use%20this%20template-2ea44f?style=for-the-badge&logo=github)](https://github.com/new?template_name=wow-project-template&template_owner=Ahoo-Wang)

Click the button above to create a new repository from [Wow Project Template](https://github.com/Ahoo-Wang/wow-project-template), then clone it and start writing your domain model.

> **Wow 8.x** supports Spring Boot 4.x, Java 17+
> 
> **Wow 6.x** supports Spring Boot 3.x, Java 17+

## Features

<p align="center"><img src="documentation/docs/public/images/Features.png" alt="Wow Features" width="95%"/></p>

| Feature | Description |
|---------|-------------|
| **Domain Model as a Service** | Just write the domain model, Wow auto-generates OpenAPI interfaces |
| **Test Suite** | Given→When→Expect pattern, 80%+ coverage made easy |
| **High Performance** | AppendOnly writes, query-oriented search engines for reads |
| **Horizontal Scalability** | No sharding rules needed, code unchanged when scaling out |
| **Distributed Transactions** | Saga orchestration pattern for complex multi-service transactions |
| **Event Compensation** | Visual dashboard + automatic retry for eventual consistency |
| **Observability** | End-to-end OpenTelemetry integration for monitoring and debugging |
| **Reactive** | Non-blocking async messaging with Project Reactor |
| **Business Intelligence** | Rich event-sourced data with minimal ETL cost |

## Architecture

<p align="center"><img src="documentation/docs/public/images/Architecture.svg" alt="Architecture" width="95%"/></p>

### Command Processing Propagation Chain

<p align="center"><img src="documentation/docs/public/images/wait/WaitingForChain.svg" alt="Command Processing Chain" width="95%"/></p>

## Performance

Stress test of the example application (2 min):

| Operation | Wait Strategy | Avg TPS | Peak TPS | Avg Latency |
|-----------|--------------|---------|----------|-------------|
| Add To Cart | `SENT` | 59,625 | 82,312 | 29 ms |
| Add To Cart | `PROCESSED` | 18,696 | 24,141 | 239 ms |
| Create Order | `SENT` | 47,838 | 86,200 | 217 ms |
| Create Order | `PROCESSED` | 18,230 | 25,506 | 268 ms |

<details>
<summary>Performance Details & Deployment</summary>

- Test Code: [Example](./example)
- Deployment: [Redis](deploy/example/perf/redis.yaml) / [MongoDB](deploy/example/perf/mongo.yaml) / [Kafka](deploy/example/perf/kafka.yaml)

<p align="center">
  <img src="./document/example/perf/Example.Cart.Add@SENT.png" alt="AddCartItem-SENT"/>
</p>

<p align="center">
  <img src="./document/example/perf/Example.Order.Create@SENT.png" alt="CreateOrder-SENT"/>
</p>

</details>

## Test Suite

> Given → When → Expect

<p align="center"><img src="document/design/assets/CI-Flow.png" alt="CI Flow" width="80%"/></p>

### Aggregate Test (`AggregateVerifier`)

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>({
  on {
    whenCommand(AddCartItem(productId = "productId", quantity = 1)) {
      expectNoError()
      expectEventType(CartItemAdded::class)
      expectState {
        items.assert().hasSize(1)
      }
    }
  }
})
```

### Saga Test (`SagaVerifier`)

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
  on {
    whenEvent(event = mockk<OrderCreated> {
      every { items } returns listOf(orderItem)
      every { fromCart } returns true
    }, ownerId = ownerId) {
      expectCommandType(RemoveCartItem::class)
    }
  }
})
```

## Design

### Modeling Patterns

| Single Class | Inheritance | Aggregation |
|:---:|:---:|:---:|
| ![Single Class](./document/design/assets/Modeling-Single-Class-Pattern.svg) | ![Inheritance](./document/design/assets/Modeling-Inheritance-Pattern.svg) | ![Aggregation](./document/design/assets/Modeling-Aggregation-Pattern.svg) |

### Core Flows

<p align="center"><img src="./document/design/assets/Command-Event-Flow.svg" alt="Command And Event Flow" width="95%"/></p>

<p align="center"><img src="./document/design/assets/EventSourcing.svg" alt="Event Sourcing" width="80%"/></p>

<details>
<summary>More Design Diagrams</summary>

**Load Aggregate**

<p align="center"><img src="./document/design/assets/Load-Aggregate.svg" alt="Load Aggregate" width="95%"/></p>

**Aggregate State Flow**

<p align="center"><img src="./document/design/assets/Aggregate-State-Flow.svg" alt="Aggregate State Flow" width="95%"/></p>

**Send Command**

<p align="center"><img src="./document/design/assets/Send-Command.svg" alt="Send Command" width="95%"/></p>

**Observability**

<p align="center"><img src="./document/design/assets/OpenTelemetry.png" alt="Observability" width="80%"/></p>

</details>

## Event Compensation

<p align="center"><img src="documentation/docs/public/images/compensation/dashboard.png" alt="Compensation Dashboard" width="80%"/></p>

<details>
<summary>Compensation Details</summary>

<p align="center"><img src="documentation/docs/public/images/compensation/usercase.svg" alt="Compensation Use Case" width="80%"/></p>

<p align="center"><img src="documentation/docs/public/images/compensation/process-sequence-diagram.svg" alt="Compensation Sequence" width="80%"/></p>

<p align="center"><img src="documentation/docs/public/images/compensation/dashboard-apply-retry-spec.png" alt="Apply Retry Spec" width="80%"/></p>

<p align="center"><img src="documentation/docs/public/images/compensation/dashboard-succeeded.png" alt="Compensation Succeeded" width="80%"/></p>

</details>

## Ecosystem

| Project | Description |
|---------|-------------|
| [CosId](https://github.com/Ahoo-Wang/CosId) | Universal, flexible, high-performance distributed ID generator |
| [CoSec](https://github.com/Ahoo-Wang/CoSec) | Multi-tenant reactive security framework based on RBAC and policies |
| [CoCache](https://github.com/Ahoo-Wang/CoCache) | Distributed consistent secondary cache framework |
| [Simba](https://github.com/Ahoo-Wang/Simba) | Easy-to-use, flexible distributed lock service |
| [CoSky](https://github.com/Ahoo-Wang/CoSky) | High-performance, low-cost microservice governance platform |
| [CoApi](https://github.com/Ahoo-Wang/CoApi) | Zero-boilerplate HTTP client auto-configuration for Spring 6 |
| [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert) | Kotlin fluent assertion library for readable and expressive tests |

## Examples

| Example | Language | Description |
|---------|----------|-------------|
| [Order Service](./example) | Kotlin | Aggregates, sagas, projections — full DDD demo |
| [Bank Transfer](./example/transfer) | Java | Simple event sourcing demo |

## License

Wow is released under the [Apache 2.0 License](LICENSE).

# Wow eShop

A multi-service shop on Wow V10, modelled on the classic eShop reference application ([dotnet/eShop](https://github.com/dotnet/eShop)). It is the M6e milestone of the V10 roadmap (decisions D158 to D160). It checks two things before M7:

- that everything from M1 to M6 works together across several services;
- that the developer experience holds up for someone who knows Wow only from the public documentation and the Skills. Each gap goes into [DX-GAPS.md](DX-GAPS.md).

The eShop builds on the V10 modules only, and is never published. `example/shop` stays as the minimal example and as the reference for 9.x-to-V10 migration.

> **Status: skeleton (work package E.0).** Each context has one aggregate and one creating command. The server runs on the memory backend. The behaviour described below arrives in work packages E.1 to E.6.

## Contexts

Five bounded contexts. Each one runs as its own service, and the services will talk over Kafka.

| Context | Projects (`example/eshop/<context>/`) | Aggregate today | Planned behaviour | In the classic eShop |
| --- | --- | --- | --- | --- |
| `eshop-catalog` | `:eshop-catalog-{api,domain,server}` | `product` (`CreateProduct`) | Products, brands and types; snapshot queries with `@QueryIndex` (E.1) | `Catalog.API` |
| `eshop-basket` | `:eshop-basket-{api,domain,server}` | `basket` (`AddBasketItem`), one per buyer | Checkout sends a cross-context command to ordering (E.2) | `Basket.API` |
| `eshop-ordering` | `:eshop-ordering-{api,domain,server}` | `order` (`PlaceOrder`) | The order state machine, and a placement saga with a grace period (E.3) | `Ordering.API`, `Ordering.Domain`, `OrderProcessor` |
| `eshop-inventory` | `:eshop-inventory-{api,domain,server}` | `stock` (`ReceiveStock`), one per product | Stock reservation and confirmation. A shortfall is rejected, and ordering's `@OnRejected` cancels the order (E.4) | The `AvailableStock` of `Catalog.API` and its stock-confirmation events, split out into a context of their own |
| `eshop-payment` | `:eshop-payment-{api,domain,server}` | `payment` (`RequestPayment`) | A reactor over a simulated payment gateway (E.5) | `PaymentProcessor` |

The rest of the classic eShop maps like this:

- **Identity.** `Identity.API` becomes Wow's `:cosec`. It is not a context of its own.
- **Event bus.** The RabbitMQ event bus becomes Kafka.
- **Sales data.** Sales data reaches ClickHouse through the ClickHouse Sink, as a dataset.
- **Web front ends.** They come after the backend, written with the TypeScript client that work package 5a.3 generates (D160).
- **End-to-end tests.** `:eshop-e2e` (`example/eshop/e2e`) holds them. Today it starts the catalog and basket services, each in its own JVM, and sends one command to each. Work package E.6 extends it to all five services on PostgreSQL, Kafka and ClickHouse, with the full order flow, failure scenarios and a load test.

### Naming

- **Context names.** Each context is named `eshop-<context>` (`@BoundedContext` in each `-domain`), so it does not clash with the shop's `example`.
- **No dots.** A context name holds no dot, because Wow writes a type as `<context>.<aggregate>`.
- **Commands and routes.** Commands and events are named `eshop-catalog.product.CreateProduct`. Routes start with `/eshop-catalog/product/`.
- **Gradle projects.** Every Gradle project is named `eshop-*`, so none clashes with the shop's projects, `:inventory-*` included.

### Dependencies

Each `-api` depends on `:api`. Each `-domain` depends on its API and on `:kernel`, and the compiler (KSP) generates its models, its binding index and its Manifest fragment. Each `-server` is a Spring Boot application on the host, `:spring-boot`. The architecture tests (`test/architecture`, `V10Rules`) enforce the rest:

- The eShop reaches V10 modules and other eShop projects only.
- No `-api` or `-domain` reaches `:runtime`.

## Running

Use JDK 21. Start a service from the repository root. Each one runs on the memory backend, on its own public port and its own internal (cluster) port:

| Service | Command | Public port | Internal port |
| --- | --- | --- | --- |
| catalog | `./gradlew -Pwow.scope=v10 :eshop-catalog-server:run` | 8081 | 9091 |
| basket | `./gradlew -Pwow.scope=v10 :eshop-basket-server:run` | 8082 | 9092 |
| ordering | `./gradlew -Pwow.scope=v10 :eshop-ordering-server:run` | 8083 | 9093 |
| inventory | `./gradlew -Pwow.scope=v10 :eshop-inventory-server:run` | 8084 | 9094 |
| payment | `./gradlew -Pwow.scope=v10 :eshop-payment-server:run` | 8085 | 9095 |

`-Pwow.scope=v10` leaves the 9.x modules out of the build. Without it, the build is slower but the result is the same.

The servers have no `CallerResolver` yet, so every request is served as the anonymous caller. For example:

```bash
curl -s localhost:8081/wow/manifest
curl -s -X POST localhost:8081/eshop-catalog/product/create-product \
  -H 'Content-Type: application/json' -d '{"name":"Wow mug","price":8.50}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item \
  -H 'Content-Type: application/json' -d '{"productId":"mug","quantity":2}'
```

## Tests

```bash
./gradlew -Pwow.scope=v10 :eshop-catalog-domain:check :eshop-catalog-server:check   # one context
./gradlew -Pwow.scope=v10 :eshop-e2e:integrationTest                                # the services together
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest                               # everything V10, the eShop included
```

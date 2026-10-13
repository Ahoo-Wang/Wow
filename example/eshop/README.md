# Wow eShop

A multi-service shop on Wow V10, modelled on the classic eShop reference application ([dotnet/eShop](https://github.com/dotnet/eShop)). It is the M6e milestone of the V10 roadmap (decisions D158 to D160). It checks two things before M7:

- that everything from M1 to M6 works together across several services;
- that the developer experience holds up for someone who knows Wow only from the public documentation and the Skills. Each gap goes into [DX-GAPS.md](DX-GAPS.md).

The eShop builds on the V10 modules only, and is never published. `example/shop` stays as the minimal example and as the reference for 9.x-to-V10 migration.

> **Status: E.2 to E.4 done.** The basket, ordering and inventory contexts and the order flow between them work end to end on PostgreSQL and Kafka. The catalog (E.1) and the payment (E.5) are still skeletons with one aggregate and one creating command; the full suite comes with E.6.

## Contexts

Five bounded contexts. Each one runs as its own service, and the services will talk over Kafka.

| Context | Projects (`example/eshop/<context>/`) | Aggregate today | Planned behaviour | In the classic eShop |
| --- | --- | --- | --- | --- |
| `eshop-catalog` | `:eshop-catalog-{api,domain,server}` | `product` (`CreateProduct`) | Products, brands and types; snapshot queries with `@QueryIndex` (E.1) | `Catalog.API` |
| `eshop-basket` | `:eshop-basket-{api,domain,server}` | `basket`, one per buyer (its id the buyer's) | Done (E.2): add, change and remove items, clear; checkout sends `SubmitOrder` to ordering | `Basket.API` |
| `eshop-ordering` | `:eshop-ordering-{api,domain,server}` | `order` | Done (E.3): the order state machine, the order-process saga, the payment grace period | `Ordering.API`, `Ordering.Domain`, `OrderProcessor`, `GracePeriodManager` |
| `eshop-inventory` | `:eshop-inventory-{api,domain,server}` | `stock`, one per product | Done (E.4): reservation, commit and release; a short stock is rejected, and ordering's `@OnRejected` cancels the order | The `AvailableStock` of `Catalog.API` and its stock-confirmation events, split out into a context of their own |
| `eshop-payment` | `:eshop-payment-{api,domain,server}` | `payment` (`RequestPayment`) | A reactor over a simulated payment gateway (E.5) | `PaymentProcessor` |

The rest of the classic eShop maps like this:

- **Identity.** `Identity.API` becomes Wow's `:cosec`. It is not a context of its own.
- **Event bus.** The RabbitMQ event bus becomes Kafka.
- **Sales data.** Sales data reaches ClickHouse through the ClickHouse Sink, as a dataset.
- **Web front ends.** They come after the backend, written with the TypeScript client that work package 5a.3 generates (D160).
- **End-to-end tests.** `:eshop-e2e` (`example/eshop/e2e`) holds them (see [Tests](#tests)). Work package E.6 extends them to all five services on PostgreSQL, Kafka and ClickHouse, with failure scenarios and a load test.

## The order flow

```text
basket                       ordering                                   inventory
------                       --------                                   ---------
CheckoutBasket
  BasketCheckedOut ──SubmitOrder──▶ SUBMITTED
  (BasketCheckoutSaga)              │ AwaitStockValidation (OrderProcessSaga)
                                    ▼
                             AWAITING_VALIDATION ──ReserveStock (each product)──▶ StockReserved
                                    │                                             │ (or rejected: short stock)
                                    │ ◀──ConfirmOrderStock── StockConfirmationSaga ◀┘ reads wow.eshop-inventory.stock.state
                                    ▼
                             STOCK_CONFIRMED ── schedules CancelOrder after the payment grace period
                                    │ PayOrder (cancels the schedule)
                                    ▼
                             PAID ── ShipOrder ──▶ SHIPPED ──CommitReservation──▶ ReservationCommitted

A rejected ReserveStock comes back as a receipt on wow.eshop-ordering.receipts:
OrderProcessSaga's @OnRejected sends CancelOrder ──▶ CANCELLED ──ReleaseReservation──▶ ReservationReleased
The grace period's scheduled CancelOrder does the same for an order not paid in time.
```

- **Order ids.** The basket numbers its checkouts: `alice`'s first order is `alice-1`. The checkout answers it as the result `orderId`.
- **States.** `SUBMITTED → AWAITING_VALIDATION → STOCK_CONFIRMED → PAID → SHIPPED`, or `CANCELLED` from any state before `PAID`.
- **Grace period.** `eshop.ordering.payment-grace-period` (30 minutes) is a durable scheduled command (decision D166), written with the commit that confirms the stock. Paying or cancelling the order cancels it. If it fires late on a paid order, the order rejects it, which is a normal guard.
- **Payment.** Until the payment context (E.5) exists, a client sends `pay-order` with the order's total.
- **Buyer.** A saga's command carries no caller scope, so `SubmitOrder` carries the buyer in its body (DX gap G15).

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

Use JDK 21. Start a service from the repository root. Each one runs on the memory backend by default, on its own public port and its own internal (cluster) port:

| Service | Command | Public port | Internal port |
| --- | --- | --- | --- |
| catalog | `./gradlew -Pwow.scope=v10 :eshop-catalog-server:run` | 8081 | 9091 |
| basket | `./gradlew -Pwow.scope=v10 :eshop-basket-server:run` | 8082 | 9092 |
| ordering | `./gradlew -Pwow.scope=v10 :eshop-ordering-server:run` | 8083 | 9093 |
| inventory | `./gradlew -Pwow.scope=v10 :eshop-inventory-server:run` | 8084 | 9094 |
| payment | `./gradlew -Pwow.scope=v10 :eshop-payment-server:run` | 8085 | 9095 |

`-Pwow.scope=v10` leaves the 9.x modules out of the build. Without it, the build is slower but the result is the same.

The basket, ordering and inventory services talk over Kafka. The basket and ordering services do not start without it, because their sagas send other contexts' commands. Each of the three has two profiles:

- **`kafka`** sets Kafka on `localhost:9092`. On ordering and inventory it also turns on the command intake. On ordering it starts `StockConfirmationSaga.onStockReserved` at the beginning of the stock stream.
- **`postgres`** puts the service on PostgreSQL. Its database is `eshop_basket`, `eshop_ordering` or `eshop_inventory` on `localhost:5432`.

The nodes do not create topics. Create the command topics with exactly as many partitions as the log (`wow.log.partitions`, 64 by default) before the first start:

```bash
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.eshop-ordering.commands --partitions 64
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.eshop-inventory.commands --partitions 64
./gradlew -Pwow.scope=v10 :eshop-inventory-server:run --args='--spring.profiles.active=kafka'
./gradlew -Pwow.scope=v10 :eshop-ordering-server:run --args='--spring.profiles.active=kafka'
./gradlew -Pwow.scope=v10 :eshop-basket-server:run --args='--spring.profiles.active=kafka'
```

The servers have no `CallerResolver` yet, so every request is served as the anonymous caller. For example:

```bash
curl -s localhost:8081/wow/manifest
curl -s -X POST localhost:8081/eshop-catalog/product/create-product \
  -H 'Content-Type: application/json' -d '{"name":"Wow mug","price":8.50}'
curl -s -X POST localhost:8084/eshop-inventory/stock/mug/receive-stock \
  -H 'Content-Type: application/json' -d '{"quantity":10}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item \
  -H 'Content-Type: application/json' -d '{"productId":"mug","unitPrice":8.50,"quantity":2}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/checkout-basket -H 'Content-Type: application/json' -d '{}' \
  -H 'Wow-Wait: committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)'
curl -s localhost:8083/eshop-ordering/order/alice-1/state
curl -s -X POST localhost:8083/eshop-ordering/order/alice-1/pay-order \
  -H 'Content-Type: application/json' -d '{"paymentId":"p-1","amount":17.00}'
```

## Tests

- **Aggregates.** Each domain module has `aggregateSpec` specs: `BasketSpec`, `OrderSpec` (the state machine and the grace period, with `advanceTime`) and `StockSpec`.
- **Sagas.** `BasketCheckoutSagaTest`, `OrderProcessSagaTest` (with the `@OnRejected` handlers) and `StockConfirmationSagaTest`.
- **Servers.** Each server test starts its service on the memory backend. The basket and ordering tests give it in-memory topics and drive it over HTTP: the basket's checkout, and the order's flow and grace period without an inventory.
- **`:eshop-e2e`.** It holds three tests:
  - `EshopE2eTest`: the catalog and inventory, each in a JVM of its own.
  - `EshopSharedJvmTest`: the catalog and basket in the test's JVM, each including its own context.
  - `EshopOrderFlowTest`: the basket, ordering and inventory, each in a JVM of its own with its `postgres` and `kafka` profiles, on PostgreSQL and Kafka in containers. It covers the checkout end to end, the rejection's compensation and the grace period. These three cannot share a class path yet (DX gap G14).

```bash
./gradlew -Pwow.scope=v10 :eshop-catalog-domain:check :eshop-catalog-server:check   # one context
./gradlew -Pwow.scope=v10 :eshop-e2e:integrationTest                                # the services together
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest                               # everything V10, the eShop included
```

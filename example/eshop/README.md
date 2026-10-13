# Wow eShop

A multi-service shop on Wow V10, modelled on the classic eShop reference application ([dotnet/eShop](https://github.com/dotnet/eShop)). It is the M6e milestone of the V10 roadmap (decisions D158 to D160). It checks two things before M7:

- that everything from M1 to M6 works together across several services;
- that the developer experience holds up for someone who knows Wow only from the public documentation and the Skills. Each gap goes into [DX-GAPS.md](DX-GAPS.md).

The eShop builds on the V10 modules only, and is never published. `example/shop` stays as the minimal example and as the reference for 9.x-to-V10 migration.

> **Status: E.1 to E.6 done.** The catalog has its products, brands and categories, and its storefront queries. The basket, ordering, inventory and payment contexts and the order flow between them work end to end on PostgreSQL and Kafka, the payment over a simulated gateway. All five services run together on PostgreSQL, Kafka and ClickHouse, with the sales dataset, a node killed mid-flow, and a load script with its baseline ([e2e/README.md](e2e/README.md)). The ordering runs on two nodes in the suite.

## Contexts

Five bounded contexts. Each one runs as its own service, and the services will talk over Kafka.

| Context | Projects (`example/eshop/<context>/`) | Aggregate today | Planned behaviour | In the classic eShop |
| --- | --- | --- | --- | --- |
| `eshop-catalog` | `:eshop-catalog-{api,domain,server}` | `product`, `brand`, `category` (see [Catalog](#catalog)) | Done (E.1): products, brands and categories; storefront queries with `@QueryIndex` | `Catalog.API` (brands and types) |
| `eshop-basket` | `:eshop-basket-{api,domain,server}` | `basket`, one per buyer (its id the buyer's) | Done (E.2): add, change and remove items, clear; checkout sends `SubmitOrder` to ordering | `Basket.API` |
| `eshop-ordering` | `:eshop-ordering-{api,domain,server}` | `order` | Done (E.3): the order state machine, the order-process saga, the payment grace period | `Ordering.API`, `Ordering.Domain`, `OrderProcessor`, `GracePeriodManager` |
| `eshop-inventory` | `:eshop-inventory-{api,domain,server}` | `stock`, one per product | Done (E.4): reservation, commit and release; a short stock is rejected, and ordering's `@OnRejected` cancels the order | The `AvailableStock` of `Catalog.API` and its stock-confirmation events, split out into a context of their own |
| `eshop-payment` | `:eshop-payment-{api,domain,server}` | `payment`, one per order (its id the order's) | Done (E.5): a payment requested once the order's stock is confirmed, charged through a simulated gateway; it pays the order or cancels it | `PaymentProcessor` |

The rest of the classic eShop maps like this:

- **Identity.** `Identity.API` becomes Wow's `:cosec`. It is not a context of its own.
- **Event bus.** The RabbitMQ event bus becomes Kafka.
- **Sales data.** The ordering's order stream reaches ClickHouse through the ClickHouse Sink, and the ordering serves it as the `eshop-ordering.sales` dataset (see [Sales](#sales)).
- **Web front ends.** They come after the backend, written with the TypeScript client that work package 5a.3 generates (D160).
- **End-to-end tests.** `:eshop-e2e` (`example/eshop/e2e`) holds them (see [Tests](#tests)): all five services on PostgreSQL, Kafka and ClickHouse, with failure scenarios and a load script.

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
                                    │
                                    │ OrderPaymentSaga reads wow.eshop-ordering.order.state ──RequestPayment──▶ payment REQUESTED
                                    │                                       PaymentGatewaySaga charges the gateway │
                                    │ ◀──PayOrder (cancels the schedule)── OrderPaymentSaga ◀── SUCCEEDED ◀────────┤
                                    ▼                                                                              │
                             PAID ── ShipOrder ──▶ SHIPPED ──CommitReservation──▶ ReservationCommitted             │
                                                                                                                   │
                             CANCELLED ◀──CancelOrder── OrderPaymentSaga ◀── FAILED ◀──────────────────────────────┘

A rejected ReserveStock comes back as a receipt on wow.eshop-ordering.receipts:
OrderProcessSaga's @OnRejected sends CancelOrder ──▶ CANCELLED ──ReleaseReservation──▶ ReservationReleased
A declined payment's CancelOrder, and the grace period's scheduled one for an order not paid in time, do the same.
```

- **Order ids.** The basket numbers its checkouts: `alice`'s first order is `alice-1`. The checkout answers it as the result `orderId`.
- **States.** `SUBMITTED → AWAITING_VALIDATION → STOCK_CONFIRMED → PAID → SHIPPED`, or `CANCELLED` from any state before `PAID`.
- **Grace period.** `eshop.ordering.payment-grace-period` (30 minutes) is a durable scheduled command (decision D166), written with the commit that confirms the stock. Paying or cancelling the order cancels it. If it fires late on a paid order, the order rejects it, which is a normal guard.
- **Payment.** See [Payment](#payment). A client may still send `pay-order` itself, with the order's total.
- **Buyer.** A saga's command carries no caller scope, so `SubmitOrder` carries the buyer in its body (DX gap G22).
- **Waiting for the order.** A checkout's `Wow-Wait` can follow the order the whole way to its payment, in 15 hops: every saga on the way, the ordering's `StockConfirmationSaga` on the inventory's stock stream included, until the payment's `PayOrder` is committed in the ordering context. Each hop names its saga and context:

  ```text
  committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)
    > handled(eshop-ordering/OrderProcessSaga.onOrderSubmitted) > committed(eshop-ordering)
    > handled(eshop-ordering/OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)
    > handled(eshop-ordering/StockConfirmationSaga.onStockReserved) > committed(eshop-ordering)
    > handled(eshop-payment/OrderPaymentSaga.onOrderStockConfirmed) > committed(eshop-payment)
    > handled(eshop-payment/PaymentGatewaySaga.onPaymentRequested) > committed(eshop-payment)
    > handled(eshop-payment/OrderPaymentSaga.onPaymentSucceeded) > committed(eshop-ordering)
  ```

  (written on one line in the header). Two settings make it work: the basket takes the inventory's and the payment's Manifests as peers, though it sends them nothing, because a chain names only contexts the node knows; and the basket allows 16 hops (`wow.wait.max-hops`, 4 by default). See DX gap G30. Shipping is the client's command, so the wait cannot go past the payment. A client's wait follows the inventory's accepted reservation back into the ordering, the round trip that a saga cannot follow by a receipt (G23).

### Naming

- **Context names.** Each context is named `eshop-<context>` (`@BoundedContext` in each `-domain`), so it does not clash with the shop's `example`.
- **No dots.** A context name holds no dot, because Wow writes a type as `<context>.<aggregate>`.
- **Commands and routes.** Commands and events are named `eshop-catalog.product.CreateProduct`. Routes start with `/eshop-catalog/product/`.
- **Gradle projects.** Every Gradle project is named `eshop-*`, so none clashes with the shop's projects, `:inventory-*` included.

### Dependencies

Each `-api` depends on `:api`. Each `-domain` depends on its API and on `:kernel`, and the compiler (KSP) generates its models, its binding index and its Manifest fragment. Each `-server` is a Spring Boot application on the host, `:spring-boot`. The architecture tests (`test/architecture`, `V10Rules`) enforce the rest:

- The eShop reaches V10 modules and other eShop projects only.
- No `-api` or `-domain` reaches `:runtime`.

## Payment

Work package E.5, after the classic eShop's `PaymentProcessor`. One aggregate, `payment`, one per order: its id is the order's.

| Command | Sent by | Accepted when | Rejections |
| --- | --- | --- | --- |
| `RequestPayment` (creates it) | `OrderPaymentSaga`, on the order's `OrderStockConfirmed` | The payment does not exist yet | `invalid-amount` (422): an amount that is not positive |
| `ConfirmPayment` | `PaymentGatewaySaga`, when the gateway approves | `REQUESTED`; again on `SUCCEEDED` changes nothing | `wrong-status` (409) |
| `FailPayment` | `PaymentGatewaySaga`, when the gateway declines | `REQUESTED`; again on `FAILED` changes nothing | `wrong-status` (409) |
| `RefundPayment` | `OrderPaymentSaga`, when the order rejects its `PayOrder` | `SUCCEEDED`; again on `REFUNDED` changes nothing | `wrong-status` (409) |

- **States.** `REQUESTED`, then `SUCCEEDED` or `FAILED`; a succeeded payment may become `REFUNDED`.
- **Request.** `OrderPaymentSaga.onOrderStockConfirmed` reads the order stream the ordering context publishes, `wow.eshop-ordering.order.state`, matches `eshop-ordering.order.OrderStockConfirmed` by name and reads the order's `totalAmount` from its published state. The server starts it at the beginning of the stream.
- **Gateway.** `PaymentGatewaySaga` charges the `PaymentGateway` for each requested payment, with its execution as the idempotency key, and records the answer. It is a saga, not a reactor, because it sends a command. The server binds the `SimulatedPaymentGateway`: it approves an amount up to `eshop.payment.gateway.limit` (1000.00) and declines one above it, so a test picks the outcome by the order's total.
- **Outcome.** A succeeded payment sends `PayOrder` (the payment's id and amount) to the ordering context; a failed one sends `CancelOrder`, and the cancelled order releases its stock as any cancelled order does.
- **Refund.** An order that rejects its `PayOrder` (cancelled meanwhile, when its grace period passed, say) comes back as a receipt on `wow.eshop-payment.receipts`, and `OrderPaymentSaga.onPayRejected` refunds the payment.
- **Peers.** The payment server takes the ordering's Manifest as its peer (`wowPeers`). The ordering knows nothing of the payment.

## Sales

Work package E.6. The ordering's `clickhouse` profile turns on the ClickHouse Sink: it writes the order stream to `eshop_ordering_order_state_events`, with the views `eshop_ordering_order_state` and `eshop_ordering_order_state_flat`. The dataset file `META-INF/wow/dataset/eshop-ordering.sales.json` of the ordering server declares the `eshop-ordering.sales` dataset over the flat view: one row per order in its current state, with its `buyerId`, `status` and `totalAmount` beside the system columns (`aggregate_id`, `commit_time`, ...). The items are a list, which the flat view leaves out, so the dataset has no revenue per product.

The ordering serves it at `/wow/dataset/eshop-ordering/sales/query`. For example, the revenue by status, then the shipped revenue by day:

```bash
curl -s -X POST localhost:8083/wow/dataset/eshop-ordering/sales/query -H 'Content-Type: application/json' -d '{
  "kind": "aggregate", "filter": {"op": "MATCH_ALL"},
  "groupBy": [{"type": "TERMS", "field": "status", "alias": "status"}],
  "metrics": [{"type": "COUNT", "alias": "orders"},
              {"type": "NUMERIC", "function": "SUM", "expression": {"type": "FIELD", "field": "totalAmount"}, "alias": "revenue"}]}'
curl -s -X POST localhost:8083/wow/dataset/eshop-ordering/sales/query -H 'Content-Type: application/json' -d '{
  "kind": "aggregate", "filter": {"op": "EQ", "field": "status", "value": "SHIPPED"},
  "groupBy": [{"type": "DATE_HISTOGRAM", "field": "commit_time", "alias": "day", "unit": "DAY"}],
  "metrics": [{"type": "NUMERIC", "function": "SUM", "expression": {"type": "FIELD", "field": "totalAmount"}, "alias": "revenue"}]}'
```

The Sink writes behind the commits, so a query right after an order's change may still read its previous state.

## Catalog

Work package E.1. Three aggregates, the classic eShop's catalog items, brands and types:

| Aggregate | Commands | Rejections |
| --- | --- | --- |
| `product` | `CreateProduct` (a draft; brand and category optional), `UpdateProduct` (name, description, brand, category), `ChangeProductPrice`, `PublishProduct`, `DelistProduct` | `blank-name` and `invalid-price` (422); `incomplete` (409): publishing without a brand and a category, or removing one from a published product; `not-published` (409): delisting a product that is not published |
| `brand` | `CreateBrand`, `RenameBrand` | `blank-name` (422); `name-taken` (409) |
| `category` | `CreateCategory`, `RenameCategory` | `blank-name` (422); `name-taken` (409) |

- **Codes.** Each code is `eshop-catalog.<aggregate>.<code>`, answered as an RFC 9457 problem.
- **Product status.** A product is `DRAFT`, `PUBLISHED` or `DELISTED`. A delisted product can be published again. Publishing a published product, or a command that changes nothing, is accepted and writes nothing.
- **Unique names.** Brand and category names are unique, ignoring case and surrounding blanks. The aggregate reserves the name with `DecideScope.reserve`. A renamed brand or category keeps its old name reserved, because nothing releases a reserved key yet ([G19](DX-GAPS.md)).
- **References.** A product names its brand and category by id. The catalog does not check that they exist.
- **Callers.** The catalog declares no `CallerResolver`. Every request is the anonymous caller of the default tenant, so every product belongs to the tenant and every shopper sees it ([G20](DX-GAPS.md)). Protecting the writes is CoSec's work (M7).

The storefront reads the product snapshots through the HTTP query routes: `POST /eshop-catalog/product/snapshot/query`. The product's state declares `@QueryIndex` indexes for what the storefront filters and sorts on:

- `state.categoryId` with `state.status` and `state.price`;
- `state.brandId` with `state.status` and `state.price`;
- `state.status` with `state.price`;
- `state.price`.

The memory backend serves no snapshot queries, so run the catalog on PostgreSQL to query it. For example, the published products of a category, cheapest first, two per page:

```bash
./gradlew -Pwow.scope=v10 :eshop-catalog-server:run --args='--spring.profiles.active=postgres'   # a database eshop_catalog on localhost:5432
curl -s -X POST localhost:8081/eshop-catalog/product/snapshot/query -H 'Content-Type: application/json' -d '{
  "kind": "paged",
  "filter": {"op": "AND", "operands": [
    {"op": "EQ", "field": "state.categoryId", "value": "mugs"},
    {"op": "EQ", "field": "state.status", "value": "PUBLISHED"}]},
  "sort": [{"field": "state.price", "direction": "ASC"}],
  "pagination": {"index": 1, "size": 2}}'
```

`GET` on the same path answers the model's descriptor: its fields and their operators. The guide does not describe the filter language yet ([G15](DX-GAPS.md)).

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

The basket, ordering, inventory and payment services talk over Kafka. The basket, ordering and payment services do not start without it, because their sagas send other contexts' commands. Each of the four has two profiles:

- **`kafka`** sets Kafka on `localhost:9092`, and `wow.kafka.topics.ddl: auto`: each service creates the topics it owns at startup. On ordering and inventory it also turns on the command intake. On ordering it starts `StockConfirmationSaga.onStockReserved` at the beginning of the stock stream, and on payment `OrderPaymentSaga.onOrderStockConfirmed` at the beginning of the order stream.
- **`postgres`** puts the service on PostgreSQL. Its database is `eshop_basket`, `eshop_ordering`, `eshop_inventory` or `eshop_payment` on `localhost:5432`.

The ordering has a third profile, **`clickhouse`**: the Sink writes its order stream to ClickHouse on `localhost:8123`, and it serves the sales dataset (see [Sales](#sales)).

With the `kafka` profile, each service creates the topics it owns when they are missing (decision D168): its command topic with exactly as many partitions as its log (`wow.log.partitions`, 64 by default), its receipts topic and its state topics. A service that reads another's topic waits until that service has created it. In production, leave `wow.kafka.topics.ddl` at its default, `validate`, and create the topics beforehand ([Deployment](https://wow.ahoo.me/guide/v10/deployment#kafka)):

```bash
./gradlew -Pwow.scope=v10 :eshop-inventory-server:run --args='--spring.profiles.active=kafka'
./gradlew -Pwow.scope=v10 :eshop-ordering-server:run --args='--spring.profiles.active=kafka'
./gradlew -Pwow.scope=v10 :eshop-basket-server:run --args='--spring.profiles.active=kafka'
./gradlew -Pwow.scope=v10 :eshop-payment-server:run --args='--spring.profiles.active=kafka'
```

The servers have no `CallerResolver` yet, so every request is served as the anonymous caller. For example:

```bash
curl -s localhost:8081/wow/manifest
curl -s -X POST localhost:8081/eshop-catalog/brand/wow/create-brand \
  -H 'Content-Type: application/json' -d '{"name":"Wow"}'
curl -s -X POST localhost:8081/eshop-catalog/category/mugs/create-category \
  -H 'Content-Type: application/json' -d '{"name":"Mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/create-product \
  -H 'Content-Type: application/json' -d '{"name":"Wow mug","price":8.50,"brandId":"wow","categoryId":"mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/publish-product
curl -s localhost:8081/eshop-catalog/product/mug/state
curl -s -X POST localhost:8084/eshop-inventory/stock/mug/receive-stock \
  -H 'Content-Type: application/json' -d '{"quantity":10}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item \
  -H 'Content-Type: application/json' -d '{"productId":"mug","unitPrice":8.50,"quantity":2}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/checkout-basket -H 'Content-Type: application/json' -d '{}' \
  -H 'Wow-Wait: committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)'
curl -s localhost:8083/eshop-ordering/order/alice-1/state     # STOCK_CONFIRMED, then PAID once the payment succeeds
curl -s localhost:8085/eshop-payment/payment/alice-1/state
```

## Tests

The catalog's tests are in three places:

- **`:eshop-catalog-domain`.** The specs of the three aggregates (`aggregateSpec`), and a test of the binding index and fragment.
- **`:eshop-catalog-server`.** Its commands and rejections over HTTP (`CatalogEndToEndTest`), and the storefront's query contract on the memory backend (`CatalogQueryContract`).
- **`:eshop-e2e`.** The storefront queries and the indexes on PostgreSQL, in a container (`CatalogPostgresTest`). The catalog server has no `integrationTest` source set of its own ([G17](DX-GAPS.md)).

The basket, ordering and inventory tests:

- **Aggregates.** Each domain module has `aggregateSpec` specs: `BasketSpec`, `OrderSpec` (the state machine and the grace period, with `advanceTime`), `StockSpec` and `PaymentSpec`.
- **Sagas.** `BasketCheckoutSagaTest`, `OrderProcessSagaTest` (with the `@OnRejected` handlers), `StockConfirmationSagaTest`, `PaymentGatewaySagaTest` (over the simulated gateway) and `OrderPaymentSagaTest` (with the refund on a rejected `PayOrder`).
- **Servers.** Each server test starts its service on the memory backend. The basket, ordering and payment tests give it in-memory topics and drive it over HTTP: the basket's checkout, the order's flow and grace period without an inventory, and the payment's approval and decline without an ordering.
- **`:eshop-e2e`.** Besides `CatalogPostgresTest`:
  - `EshopE2eTest`: the catalog and inventory, each in a JVM of its own.
  - `EshopSharedJvmTest`: services in the test's JVM, on one class path, each including its own context: the catalog and basket; and the inventory, ordering and payment, whose servers carry each other's Manifests as peers (DX gap G21). Services on one class path read one `application.yaml`, so the test passes each one's configuration as arguments.
  - `EshopOrderFlowTest`: the basket, ordering and inventory, each in a JVM of its own with its `postgres` and `kafka` profiles, on PostgreSQL and Kafka in containers. It covers the checkout end to end, the rejection's compensation and the grace period. No payment service runs, so the test pays the order itself.
  - `EshopPaymentFlowTest`: the ordering, inventory and payment the same way, with a gateway limit of 100.00. An order of 29.00 is paid and shipped; an order of 150.00 is declined, cancelled, and its stock released.
  - `EshopFullChainTest`: all five services the same way (`EshopStack`), the ordering with its `clickhouse` profile, on PostgreSQL, Kafka and ClickHouse in containers. In order:
    1. **Full chain.** Products created and published in the catalog, their stock received, a basket filled at the catalog's prices and checked out with the 15-hop wait of [Waiting for the order](#the-order-flow), which answers with the committed `PayOrder`; then the order shipped and its reservations committed, each order event once.
    2. **Rejection.** A stock too short cancels the order, and no payment is requested.
    3. **Sales.** The sales dataset sums the revenue of those two orders by status (29.00 shipped, 6.00 cancelled) and the shipped revenue by day.
    4. **Node killed.** The ordering node owning the order's partition is killed (`SIGKILL`) right after a checkout, before the payment is requested, and a node of another id is started in its place. The order is paid and shipped with every effect once: one order with each event once, the stock reserved and committed once, one payment requested and succeeded once. The test logs how long the order's partition was unavailable. On this machine the replacement served after about 1.7 s and took the partition's commands at once (a node without the lease runs them itself, D71), after about 2 s; the partition's lease, and with it the order process's subscriptions, followed after about 4.6 to 5.2 s (the lease's 5 s TTL), and the order was paid after about 5 to 5.4 s. The test's bound is 90 s, for slow CI runners.

    The ordering runs on two nodes sharing its database: a saga's commands go to the owner of their partition like any other (G32), and the order process's subscriptions save their starting checkpoints as they start, so a node killed before its first save does not make its successor skip the partition's first orders (G33).
- **Load.** `EshopLoad`, in the same source set, starts `EshopStack` and drives checkouts through the full chain, each with the 15-hop wait, and reports their throughput and latency. It is not part of any check; [e2e/README.md](e2e/README.md) has the baseline and how to run it.

```bash
./gradlew -Pwow.scope=v10 :eshop-catalog-domain:check :eshop-catalog-server:check   # one context
./gradlew -Pwow.scope=v10 :eshop-e2e:integrationTest                                # the services together
./gradlew -Pwow.scope=v10 :eshop-e2e:integrationTest --tests '*EshopFullChainTest'  # all five services
./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad                                      # the load script
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest                               # everything V10, the eShop included
```

Each service's output of a run of `:eshop-e2e` is kept in `example/eshop/e2e/build/eshop-logs/`.

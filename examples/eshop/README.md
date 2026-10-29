# Wow eShop

A multi-service shop on Wow, modelled on the classic eShop reference application ([dotnet/eShop](https://github.com/dotnet/eShop)). It checks two things:

- that Wow's features work together across several services;
- that the developer experience holds up for someone who knows Wow only from the public documentation and the Skills. Each gap goes into [DX-GAPS.md](DX-GAPS.md).

The eShop builds on the modules only, and is never published. It is the example: the shop (`examples/shop`), its inventory (`examples/inventory`) and their end-to-end tests (`examples/e2e`) came before it and were folded into it, each scenario they alone tested now covered here (see [Tests](#tests)).

> **Status: E.1 to E.7 done.** The catalog has its products, brands and categories, and its storefront queries. The basket, ordering, inventory and payment contexts and the order flow between them work end to end on PostgreSQL and Kafka, the payment over a simulated gateway. All five services run together on PostgreSQL, Kafka and ClickHouse, with the sales dataset, a node killed mid-flow, and a load script with its baseline ([e2e/README.md](e2e/README.md)). The ordering runs on two nodes in the suite. A web UI drives the shop end to end ([Web UI](#web-ui)).

## Contexts

Five bounded contexts. Each one runs as its own service, and the services will talk over Kafka.

| Context | Projects (`examples/eshop/<context>/`) | Aggregate today | Planned behaviour | In the classic eShop |
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
- **Web front ends.** One web UI, `examples/eshop/web`, on the TypeScript clients generated from the services' Manifests (see [Web UI](#web-ui)).
- **End-to-end tests.** `:eshop-e2e` (`examples/eshop/e2e`) holds them (see [Tests](#tests)): all five services on PostgreSQL, Kafka and ClickHouse, with failure scenarios and a load script.

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
                                    │ ◀──ConfirmOrderStock── @OnCommitted receipt ◀─┘ OrderProcessSaga, on wow.eshop-ordering.receipts
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
- **Grace period.** `eshop.ordering.payment-grace-period` (30 minutes) is a durable scheduled command, written with the commit that confirms the stock. Paying or cancelling the order cancels it. If it fires late on a paid order, the order rejects it, which is a normal guard.
- **Payment.** See [Payment](#payment). A client may still send `pay-order` itself, with the order's total.
- **Buyer.** The order belongs to its buyer. The basket's owner is its buyer, and the checkout saga's `SubmitOrder` carries the basket's scope, as every command a saga derives carries the scope of the commit it handles. The order takes its owner from that command, and so does the payment the payment context's saga creates for it.
- **Stock confirmation.** The inventory sends the receipt of each reservation it commits back to `wow.eshop-ordering.receipts`, because `OrderProcessSaga` declares an `@OnCommitted` handler of `ReserveStock`; the handler confirms the product on the order. The receipt carries the stock's id, its version and the command's result, not its state, and the saga handles each receipt once.
- **Waiting for the order.** A checkout's `Wow-Wait` can follow the order the whole way to its payment, in 15 hops: every saga on the way, the ordering's `@OnCommitted` handler of the reservations' receipts included, until the payment's `PayOrder` is committed in the ordering context. Each hop names its saga and context:

  ```text
  committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)
    > handled(eshop-ordering/OrderProcessSaga.onOrderSubmitted) > committed(eshop-ordering)
    > handled(eshop-ordering/OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)
    > handled(eshop-ordering/OrderProcessSaga.onReserveCommitted) > committed(eshop-ordering)
    > handled(eshop-payment/OrderPaymentSaga.onOrderStockConfirmed) > committed(eshop-payment)
    > handled(eshop-payment/PaymentGatewaySaga.onPaymentRequested) > committed(eshop-payment)
    > handled(eshop-payment/OrderPaymentSaga.onPaymentSucceeded) > committed(eshop-ordering)
  ```

  (written on one line in the header). The basket knows only the ordering's Manifest, its one peer. It lets the hops of the inventory and the payment through, and each is checked where the chain arrives. Each saga adds two hops, its own and the next commit, and the 15 hops fit in the default of 16 (`wow.wait.max-hops`). Shipping is the client's command, so the wait cannot go past the payment. A declined payment's `onPaymentSucceeded` sends nothing: the wait ends at its hop, its result marked `chainEnded` and naming the order's commit as `pending`, the hop that will not come. A reservation's receipt carries the rest of the chain back to the ordering, where the `@OnCommitted` handler's hop is signalled and its confirmation goes on with it.

### Naming

- **Context names.** Each context is named `eshop-<context>` (`@BoundedContext` in each `-domain`), so it does not clash with the 9.x example's `example`.
- **No dots.** A context name holds no dot, because Wow writes a type as `<context>.<aggregate>`.
- **Commands and routes.** Commands and events are named `eshop-catalog.product.CreateProduct`. Routes start with `/eshop-catalog/product/`.
- **Gradle projects.** Every Gradle project is named `eshop-*`, so none clashes with a 9.x example's projects.

### Dependencies

Each `-api` depends on `:api`. Each `-domain` depends on its API and on `:kernel`, and the compiler (KSP) generates its models, its binding index and its Manifest fragment. Each `-server` is a Spring Boot application on the host, `:spring-boot`. The architecture tests (`test/architecture`, `ArchitectureRules`) enforce the rest:

- The eShop reaches Wow modules and other eShop projects only.
- No `-api` or `-domain` reaches `:runtime`.

### Validation

A command's shape is checked before its aggregate decides: each `-api` puts Jakarta constraints on its commands (`@field:Positive`, `@field:NotBlank`, `@field:NotEmpty`, `@field:Valid` for the items of an order), and each `-server` has a Bean Validation provider (`spring-boot-starter-validation`). The gateway checks every command it takes, from HTTP, in process, or from a saga or another context, and answers a violation `wow.command.invalid` (400) with its `violations`. An aggregate rejects only what needs its state, with an `@ErrorCode`: a product the basket does not hold, an empty basket, a short stock. Which basket a buyer reaches is not the basket's rule: its id is its buyer's (`@AggregateRoot(idSource = AggregateIdSource.OWNER)`), so a buyer's routes name no basket, and another buyer's basket is `wow.aggregate.forbidden` (403).

## Payment

Work package E.5, after the classic eShop's `PaymentProcessor`. One aggregate, `payment`, one per order: its id is the order's.

| Command | Sent by | Accepted when | Rejections |
| --- | --- | --- | --- |
| `RequestPayment` (creates it) | `OrderPaymentSaga`, on the order's `OrderStockConfirmed` | The payment does not exist yet | None: an amount that is not positive breaks the command's constraint (`wow.command.invalid`, 400) |
| `ConfirmPayment` | `PaymentGatewaySaga`, when the gateway approves | `REQUESTED`; again on `SUCCEEDED` changes nothing | `wrong-status` (409) |
| `FailPayment` | `PaymentGatewaySaga`, when the gateway declines | `REQUESTED`; again on `FAILED` changes nothing | `wrong-status` (409) |
| `RefundPayment` | `OrderPaymentSaga`, when the order rejects its `PayOrder`; the gateway's chargeback callback, `ChargebackNotified` | `SUCCEEDED`; again on `REFUNDED` changes nothing | `wrong-status` (409) |

- **States.** `REQUESTED`, then `SUCCEEDED` or `FAILED`; a succeeded payment may become `REFUNDED`.
- **Request.** `OrderPaymentSaga.onOrderStockConfirmed` reads the order stream the ordering context publishes, `wow.eshop-ordering.order.state`, matches `eshop-ordering.order.OrderStockConfirmed` by name and reads the order's `totalAmount` from its published state. The server starts it at the beginning of the stream.
- **Gateway.** `PaymentGatewaySaga` charges the `PaymentGateway` for each requested payment, with its execution as the idempotency key, and records the answer. It is a saga, not a reactor, because it sends a command. The server binds the `SimulatedPaymentGateway`: it approves an amount up to `eshop.payment.gateway.limit` (1000.00) and declines one above it, so a test picks the outcome by the order's total.
- **Outcome.** A succeeded payment sends `PayOrder` (the payment's id and amount) to the ordering context; a failed one sends `CancelOrder`, and the cancelled order releases its stock as any cancelled order does.
- **Refund.** An order that rejects its `PayOrder` (cancelled meanwhile, when its grace period passed, say) comes back as a receipt on `wow.eshop-payment.receipts`, and `OrderPaymentSaga.onPayRejected` refunds the payment.
- **Chargeback.** The gateway calls back when a buyer disputes a payment with their bank: `ChargebackNotified` (`merchantReference`, `disputeId`, `reason`), in the gateway's shape, is a mounted command (`@MountedCommand(aggregate = "payment")`) with no `@OnCommand`. The server's transformer, `Chargebacks`, turns it into the `RefundPayment` of the payment the reference names, and keeps the dispute in the `dispute-id` header. The gateway knows no payment id, so it posts to the route without one, `POST /eshop-payment/payment/chargeback-notified`; the result's `transformedFrom` and the stream's `wow.transformed-from` header name the callback, and the refund is authorized again as itself.
- **Peers.** The payment knows the ordering's contract from the jar of `eshop-ordering-api`, which its domain module depends on. The ordering knows nothing of the payment.

## Sales

Work package E.6. The ordering's `clickhouse` profile turns on the ClickHouse Sink: it writes the order stream to `eshop_ordering_order_state_events`, with the views `eshop_ordering_order_state` and `eshop_ordering_order_state_flat`. The dataset file `META-INF/wow/dataset/eshop-ordering.sales.json` of the ordering server declares the `eshop-ordering.sales` dataset over the flat view: one row per order in its current state, with its `status` and `totalAmount` beside the system columns (`aggregate_id`, `owner_id`, the buyer, `commit_time`, ...). The items are a list, which the flat view leaves out, so the dataset has no revenue per product.

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
| `product` | `CreateProduct` (a draft; brand and category optional), `UpdateProduct` (name, description, brand, category), `ChangeProductPrice`, `PublishProduct`, `DelistProduct` | `incomplete` (409): publishing without a brand and a category, or removing one from a published product; `not-published` (409): delisting a product that is not published |
| `brand` | `CreateBrand`, `RenameBrand` | `name-taken` (409) |
| `category` | `CreateCategory`, `RenameCategory` | `name-taken` (409) |

- **Codes.** Each code is `eshop-catalog.<aggregate>.<code>`, answered as an RFC 9457 problem. A blank name or a price that is not more than zero breaks the command's constraints instead (`wow.command.invalid`, 400).
- **Product status.** A product is `DRAFT`, `PUBLISHED` or `DELISTED`. A delisted product can be published again. Publishing a published product, or a command that changes nothing, is accepted and writes nothing.
- **Unique names.** Brand and category names are unique, ignoring case and surrounding blanks. The aggregate reserves the name with `DecideScope.reserve`. A renamed brand or category keeps its old name reserved, because nothing releases a reserved key yet ([G19](DX-GAPS.md)).
- **References.** A product names its brand and category by id. The catalog does not check that they exist.
- **Callers.** The products, brands and categories belong to the tenant (`@AggregateRoot(ownership = Ownership.TENANT)`), so every shopper reads them. In development the host gives the two caller levels from headers, standing in for CoSec: `Wow-User: <id>` with `Wow-Owner: <id>` is a buyer, and `Wow-User` with `Wow-Roles: employee` and no owner is an employee, a caller of the whole tenant. The server's `CommandAuthorizer` (`CatalogAuthorization`) lets only an employee change the catalog. A request without `Wow-User` is the anonymous caller, for trying the service out.

The storefront reads the product snapshots through the HTTP query routes: `POST /eshop-catalog/product/snapshot/query`. The product's state declares `@QueryIndex` indexes for what the storefront filters and sorts on:

- `state.categoryId` with `state.status` and `state.price`;
- `state.brandId` with `state.status` and `state.price`;
- `state.status` with `state.price`;
- `state.price`.

Run the catalog on PostgreSQL to query it with these indexes. For example, the published products of a category, cheapest first, two per page:

```bash
./gradlew :eshop-catalog-server:run --args='--spring.profiles.active=postgres'   # a database eshop_catalog on localhost:5432
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

| Service | Command | Public port | Internal port | Swagger UI |
| --- | --- | --- | --- | --- |
| catalog | `./gradlew :eshop-catalog-server:run` | 8081 | 9091 | http://localhost:8081/swagger-ui.html |
| basket | `./gradlew :eshop-basket-server:run` | 8082 | 9092 | http://localhost:8082/swagger-ui.html |
| ordering | `./gradlew :eshop-ordering-server:run` | 8083 | 9093 | http://localhost:8083/swagger-ui.html |
| inventory | `./gradlew :eshop-inventory-server:run` | 8084 | 9094 | http://localhost:8084/swagger-ui.html |
| payment | `./gradlew :eshop-payment-server:run` | 8085 | 9095 | http://localhost:8085/swagger-ui.html |

The basket, ordering, inventory and payment services talk over Kafka. Run on this machine without the `kafka` profile, each service keeps its topics in its own JVM (`wow.transport` derives this in development), where other services do not see them; `./gradlew :eshop-e2e:eshopLocal` runs all five in one JVM that way. Each of the four has two profiles:

- **`kafka`** sets Kafka on `localhost:9092`: each service creates the topics it owns at startup (`wow.kafka.ddl` follows `wow.ddl`, `auto` by default). On ordering and inventory it also turns on the command intake. On payment it starts `OrderPaymentSaga.onOrderStockConfirmed` at the beginning of the order stream.
- **`postgres`** puts the service on PostgreSQL. Its database is `eshop_basket`, `eshop_ordering`, `eshop_inventory` or `eshop_payment` on `localhost:5432`.

The ordering has a third profile, **`clickhouse`**: the Sink writes its order stream to ClickHouse on `localhost:8123`, and it serves the sales dataset (see [Sales](#sales)).

Every server has two more:

- **`cosec`** is how the services are deployed, behind the CoSec gateway ([Secure a Deployment](https://wow.ahoo.me/operate/security)). The gateway verifies each request's CoSec token, decides the request with its documents and forwards the principal it verified in the `CoSec-Identity` header; the service reads it in CoSec's gateway mode, without a secret, documents or Redis of its own, and wow-cosec's resolver makes it the caller. A request without the header is anonymous, and since the service believes any `CoSec-Identity`, only the gateway may reach it. A principal with the role `employee` is an employee (`wow.cosec.tenant-level.roles`), any other a buyer. The headers above name nobody there.
- **`mongo`** puts the service on a MongoDB replica set (`spring.mongodb.uri`, `localhost:27017` by default), its database `eshop_catalog`, `eshop_basket`, … like the PostgreSQL ones. The host creates the client from the URI.

Each server's image is built from its Gradle distribution with `deploy/eshop-<context>/Dockerfile`.

With the `kafka` profile, each service creates the topics it owns when they are missing: its command topic with exactly as many partitions as its log (`wow.log.partitions`, 64 by default), its receipts topic and its state topics. A service that reads another's topic waits until that service has created it. In production, set `wow.ddl: validate` (or `wow.kafka.ddl` alone) and create the topics beforehand ([Deployment](https://wow.ahoo.me/operate/kafka)):

```bash
./gradlew :eshop-inventory-server:run --args='--spring.profiles.active=kafka'
./gradlew :eshop-ordering-server:run --args='--spring.profiles.active=kafka'
./gradlew :eshop-basket-server:run --args='--spring.profiles.active=kafka'
./gradlew :eshop-payment-server:run --args='--spring.profiles.active=kafka'
```

In a development profile (`dev`, which `./gradlew run` and `eshopLocal` add), every server reads the caller from the host's development headers: `Wow-User` (the subject), `Wow-Owner` (the owner of what it creates; none for a caller of the whole tenant), `Wow-Roles` and `Wow-Tenant`. A request without `Wow-User` is the anonymous caller. A server in development by its loopback internal port alone serves every request as the anonymous caller, and anywhere else a server without CoSec does not start, since any client could name its caller. For example:

```bash
curl -s localhost:8081/wow/manifest
EMPLOYEE=(-H 'Wow-User: erin' -H 'Wow-Roles: employee')    # changes the catalog
BUYER=(-H 'Wow-User: alice' -H 'Wow-Owner: alice')        # reads the catalog, owns her basket (id alice) and orders
curl -s -X POST localhost:8081/eshop-catalog/brand/wow/create-brand "${EMPLOYEE[@]}" \
  -H 'Content-Type: application/json' -d '{"name":"Wow"}'
curl -s -X POST localhost:8081/eshop-catalog/category/mugs/create-category "${EMPLOYEE[@]}" \
  -H 'Content-Type: application/json' -d '{"name":"Mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/create-product "${EMPLOYEE[@]}" \
  -H 'Content-Type: application/json' -d '{"name":"Wow mug","price":8.50,"brandId":"wow","categoryId":"mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/publish-product "${EMPLOYEE[@]}"
curl -s localhost:8081/eshop-catalog/product/mug/state "${BUYER[@]}"
curl -s -X POST localhost:8084/eshop-inventory/stock/mug/receive-stock \
  -H 'Content-Type: application/json' -d '{"quantity":10}'
curl -s -X POST localhost:8082/eshop-basket/basket/add-basket-item "${BUYER[@]}" \
  -H 'Content-Type: application/json' -d '{"productId":"mug","unitPrice":8.50,"quantity":2}'
curl -s -X POST localhost:8082/eshop-basket/basket/checkout-basket "${BUYER[@]}" -H 'Content-Type: application/json' -d '{}' \
  -H 'Wow-Wait: committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)'
curl -s localhost:8083/eshop-ordering/order/alice-1/state "${BUYER[@]}"   # alice's order: STOCK_CONFIRMED, then PAID
curl -s localhost:8085/eshop-payment/payment/alice-1/state
```

## Web UI

Work package E.7: `examples/eshop/web`, a React app (Vite, shadcn) on the clients `wow-generator` writes from the five Manifest baselines into `src/generated/<context>/` (`pnpm --filter wow-eshop-web generate`; a test holds them byte for byte). A switcher in its header picks the caller it sends as `Wow-User` with `Wow-Owner` or `Wow-Roles`: the buyers `alice` and `bob`, or the employee `erin`.

- **Shop.** Products from the catalog's snapshot query, searched by name and category, sorted and paged; a buyer sees the published ones and adds them to the basket.
- **Catalog admin** (employee). Brands, categories and draft products; a product's price, its publication, and its stock in the inventory.
- **Basket and checkout** (buyer). The checkout streams the 15-hop wait of [Waiting for the order](#the-order-flow) and shows each hop as it arrives, to the paid order, to the hop that failed, or to the hop where the server says the chain ended (`chainEnded`, with the hops `pending`).
- **Orders.** A buyer's own orders, every order for an employee, who ships the paid ones; an order with its payment.
- **Problems.** A refusal or rejection shows its title, status, code, detail and violations.

The dev server forwards each context's routes (`/eshop-<context>/`) to its service on the ports of [Running](#running) (`ESHOP_<CONTEXT>_URL` overrides one). Without Kafka, run the whole shop in one JVM, in memory: `EshopLocal` shares one set of in-memory topics among the five services ([G27](DX-GAPS.md)).

```bash
./gradlew :eshop-e2e:eshopLocal          # the five services, ports 8081 to 8085
pnpm --filter wow-eshop-web... build                     # once: the workspace packages it uses
pnpm --filter wow-eshop-web dev                          # http://localhost:5173
```

`pnpm test` runs the component tests. `pnpm test:e2e` is the end-to-end test: it starts `EshopLocal` on ports bound to 0, the dev server against them, and drives the UI in Chromium from the catalog to a shipped order, with a short stock and a declined payment. Build its class path first with `./gradlew :eshop-e2e:eshopLocalClasspath` (JDK 21). Set `ESHOP_WEB_BROWSER_CHANNEL=chrome` to run it in an installed Chrome.

## Tests

The catalog's tests are in three places:

- **`:eshop-catalog-domain`.** The specs of the three aggregates (`aggregateSpec`), a test of the binding index and fragment, and `StatePropertiesTest`: the compiler's `ProductStateProperties`, `BrandStateProperties` and `CategoryStateProperties` name exactly the state schemas' paths, and the query DSL takes them.
- **`:eshop-catalog-server`.** Its commands and rejections over HTTP (`CatalogEndToEndTest`), and the storefront's query contract on the memory backend (`CatalogQueryContract`): the descriptor, lists, pages, cursors and counts, a list as server-sent events, a product's events by version, the operator and the creator from the employee's command to the snapshot query, and the storefront's query written with the DSL and `ProductStateProperties`, run on the node's query gateway.
- **`:eshop-e2e`.** The storefront queries, the DSL's query and the indexes on PostgreSQL, in a container (`CatalogPostgresTest`). The catalog server has no `integrationTest` source set of its own ([G17](DX-GAPS.md)).

The basket, ordering and inventory tests:

- **Aggregates.** Each domain module has `aggregateSpec` specs: `BasketSpec`, `OrderSpec` (the state machine and the grace period, with `advanceTime`), `StockSpec` and `PaymentSpec`.
- **Sagas.** `BasketCheckoutSagaTest`, `OrderProcessSagaTest` (with the `@OnRejected` and `@OnCommitted` handlers), `PaymentGatewaySagaTest` (over the simulated gateway) and `OrderPaymentSagaTest` (with the refund on a rejected `PayOrder`).
- **Servers.** Each server test starts its service on the memory backend. The basket, ordering and payment tests give it in-memory topics and drive it over HTTP: the basket's checkout, the order's flow and grace period without an inventory, and the payment's approval and decline without an ordering. Besides:
  - `OrderLifecycleTest`: the lifecycle routes on an order: deleted, refused with `wow.aggregate.deleted` (410) and restored; moved to another buyer's account, which the former owner then neither reads nor changes; tagged and moved into a fulfilment space by an employee.
  - `PaymentTransformationTest`: the chargeback callback refunds the payment it names through the route without an id, an unknown reference is `wow.aggregate.not-found` naming the callback, and a second transformer of the callback stops the service from starting. `PaymentTransformationAuthorizationTest` gives the service an authorizer that lets only the gateway refund: anyone may call the callback's route, and the refund it becomes is refused to anyone but the gateway.
  - `PaymentJoinsLaterTest`: the ordering's stream already holds an order whose stock was confirmed when the payment service first starts; with no configured start, the order-payment saga reads that stream, another context's, from its beginning, so the payment is still requested and charged.
- **`:eshop-ordering-server` `integrationTest`.** `OrderingAnalyticsTest` runs the ordering in the test's JVM in its `postgres` and `clickhouse` profiles, on PostgreSQL and ClickHouse in containers: the orders' snapshot and event queries are each buyer's own and every order an employee's; the Sink writes each order's current state to the flat view, and the sales dataset is read in the caller's scope, aggregated by buyer, listed and described with its version as ETag.
- **`:eshop-e2e`.** Besides `CatalogPostgresTest`:
  - `EshopE2eTest`: the catalog and inventory, each in a JVM of its own.
  - `EshopSharedJvmTest`: services in the test's JVM, on one class path, each including its own context: the catalog and basket; and the inventory, ordering and payment, whose servers carry each other's Manifests as peers (DX gap G21). Services on one class path read one `application.yaml`, so the test passes each one's configuration as arguments.
  - `EshopOrderFlowTest`: the basket, ordering and inventory, each in a JVM of its own with its `postgres` and `kafka` profiles, on PostgreSQL and Kafka in containers. It covers the checkout end to end, the rejection's compensation and the grace period. No payment service runs, so the test pays the order itself.
  - `EshopPaymentFlowTest`: the ordering, inventory and payment the same way, with a gateway limit of 100.00. An order of 29.00 is paid and shipped; an order of 150.00 is declined, cancelled, and its stock released.
  - `EshopFullChainTest`: all five services the same way (`EshopStack`), the ordering with its `clickhouse` profile, on PostgreSQL, Kafka and ClickHouse in containers. In order:
    1. **Full chain.** Products created and published in the catalog, their stock received, a basket filled at the catalog's prices and checked out with the 15-hop wait of [Waiting for the order](#the-order-flow), which answers with the committed `PayOrder`; then the order shipped and its reservations committed, each order event once.
    2. **Declined payment** ([DX-GAPS.md](DX-GAPS.md)). An order over the gateway's limit: `OrderPaymentSaga.onPaymentSucceeded` sends nothing, so the 15-hop wait ends at that hop, answered `chainEnded` with the ordering's commit as its `pending` hop, at once and as server-sent events alike, and the order is cancelled.
    3. **Rejection.** A stock too short cancels the order, and no payment is requested.
    4. **Sales.** The sales dataset sums the revenue of those two orders by status (29.00 shipped, 6.00 cancelled) and the shipped revenue by day.
    5. **Node killed.** The ordering node owning the order's partition is killed (`SIGKILL`) right after a checkout, before the payment is requested, and a node of another id is started in its place. The order is paid and shipped with every effect once: one order with each event once, the stock reserved and committed once, one payment requested and succeeded once. The test logs how long the order's partition was unavailable. On this machine the replacement served after about 1.7 s and took the partition's commands at once (a node without the lease runs them itself), after about 2 s; the partition's lease, and with it the order process's subscriptions, followed after about 4.6 to 5.2 s (the lease's 5 s TTL), and the order was paid after about 5 to 5.4 s. The test's bound is 90 s, for slow CI runners.

    The ordering runs on two nodes sharing its database: a saga's commands go to the owner of their partition like any other (G32), and the order process's subscriptions save their starting checkpoints as they start, so a node killed before its first save does not make its successor skip the partition's first orders (G33).
- **Load.** `EshopLoad`, in the same source set, starts `EshopStack` and drives checkouts through the full chain, each with the 15-hop wait, and reports their throughput and latency. It is not part of any check; [e2e/README.md](e2e/README.md) has the baseline and how to run it.

```bash
./gradlew :eshop-catalog-domain:check :eshop-catalog-server:check   # one context
./gradlew :eshop-e2e:integrationTest                                # the services together
./gradlew :eshop-e2e:integrationTest --tests '*EshopFullChainTest'  # all five services
./gradlew :eshop-e2e:eshopLoad                                      # the load script
./gradlew :check :integrationTest                               # everything, the eShop included
```

Each service's output of a run of `:eshop-e2e` is kept in `examples/eshop/e2e/build/eshop-logs/`.

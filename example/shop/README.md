# Shop: the Wow V10 example

The V10 example (decision D111): one bounded context, `example`, with two
aggregates, `cart` (a customer's cart, its id the customer's user id) and
`order` (placing, paying, shipping, receiving or cancelling an order). Its
second context, `inventory`, is a service of its own
([`example/inventory`](../inventory/README.md), decision D144): the shop
reserves an order's stock there over Kafka.

| Module        | Holds                                                                                                 |
| ------------- | ----------------------------------------------------------------------------------------------------- |
| `shop-api`    | Commands, events and value types; Jakarta constraints on `AddCartItem`                                |
| `shop-domain` | The aggregates, `CartSaga` and `OrderSaga`, compiled by KSP to models, bindings and a Manifest part   |
| `shop-server` | The Spring Boot application: the Wow host, a stub pricing service, the inventory's Manifest as a peer |

The example is built and tested with the V10 modules and never published.

## Run

From the repository root, with JDK 21:

```bash
./gradlew -Pwow.scope=v10 :shop-server:run
```

It serves the public routes on port 8080 and the internal port (the cluster
channel and the wait signals) on 9090, one node with its data in memory.

On PostgreSQL, as many nodes as share the database, each command routed to the
node that owns its partition:

```bash
./gradlew -Pwow.scope=v10 :shop-server:run --args='--spring.profiles.active=postgres'
```

The `postgres` profile reads `spring.datasource.url`, `username` and `password`
(`jdbc:postgresql://localhost:5432/shop`, `postgres`, `postgres` by default).
Give each further node its own `server.port`, `wow.internal.port` and
`wow.node.id`.

With the inventory, on Kafka at `localhost:9092` (the `kafka` profile; any
other broker through `wow.kafka.bootstrap-servers`):

```bash
./gradlew -Pwow.scope=v10 :shop-server:run --args='--spring.profiles.active=postgres,kafka'
```

Without Kafka the order saga cannot reach the inventory: each placed order
parks `OrderSaga.onOrderCreated` in the failure ledger after its retries.

With ClickHouse at `localhost:8123` (the `clickhouse` profile; any other server
through `wow.clickhouse.endpoint`), the ClickHouse Sink writes the order stream
to `example_order_state_flat`, and the dataset `example.orders`, which
`META-INF/wow/dataset/example.orders.json` declares over that view, is queried
at `/wow/dataset/example/orders/query`:

```bash
./gradlew -Pwow.scope=v10 :shop-server:run --args='--spring.profiles.active=postgres,clickhouse'
```

The server declares no `CallerResolver`, so every request is served as the
anonymous caller (default tenant, no owner), with a warning; CoSec's resolver
takes its place later. The end-to-end tests resolve the caller from an `X-User`
header instead.

## Routes

| Method   | Path                                                                  | Does                                              |
| -------- | --------------------------------------------------------------------- | ------------------------------------------------- |
| `POST`   | `/example/cart/{id}/add-cart-item`                                    | `AddCartItem`, creating the cart on its first one |
| `POST`   | `/example/cart/{id}/change-quantity`, `/remove-cart-item`             | The other cart commands                           |
| `POST`   | `/example/order/create-order`                                         | `CreateOrder` under an id the server generates    |
| `POST`   | `/example/order/{id}/create-order`                                    | `CreateOrder` under that id                       |
| `POST`   | `/example/order/{id}/change-address`, `/pay-order`, `/ship-order`     | The other order commands                          |
| `POST`   | `/example/order/{id}/receipt-order`                                   | Confirms the order was received                   |
| `POST`   | `/example/order/{id}/cancel-order`                                    | Cancels an order not shipped yet                  |
| `DELETE` | `/example/{aggregate}/{id}`                                           | Deletes the aggregate                             |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.restore-aggregate`           | Restores a deleted aggregate                      |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.transfer-owner`, `-space`    | Gives the aggregate another owner or space        |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.apply-tags`                  | Replaces the aggregate's tags                     |
| `GET`    | `/example/{aggregate}/{id}/state`, `?version=n`                       | The latest state, or the one at version `n`       |
| `POST`   | `/example/{aggregate}/snapshot/query`, `/events/query`                | A query of the snapshots or of one's events       |
| `GET`    | `/example/{aggregate}/snapshot/query`, `/events/query`                | The model's capability descriptor, with an ETag   |
| `POST`   | `/wow/dataset/example/orders/query`                                   | A query of the `example.orders` dataset           |
| `GET`    | `/wow/dataset/example/orders/query`                                   | The dataset's capability descriptor, with an ETag |
| `GET`    | `/wow/dataset`                                                        | The datasets the caller sees                      |
| `GET`    | `/wow/manifest`                                                       | The Manifest the routes are built from            |

A command answers its `CommandResult` (200, or 202 for a wait that ends at
`accepted`); a command that fails is an RFC 9457 problem
(`application/problem+json`, `type` `urn:wow:error:<code>`) carrying the result.
The Manifest lists each error code with its status: the framework's at the top,
the shop's (`example.cart.full`, `example.order.wrong-status`, …) under the
context.

```bash
curl -X POST localhost:8080/example/cart/alice/add-cart-item \
  -H 'Content-Type: application/json' -d '{"productId":"apple","quantity":2}'
curl localhost:8080/example/cart/alice/state
```

The stub catalog sells `apple` (2.50), `pear` (4.00) and `durian` (30.00); an
order must quote the current price. Their stock is the inventory's.

## Headers

| Header                     | Means                                                                                         |
| -------------------------- | --------------------------------------------------------------------------------------------- |
| `Wow-Wait`                 | How far to wait: `accepted`, `committed`, `snapshot` or `handled(sub)`, chained with `>`      |
| `Wow-Wait-Timeout`         | How long to wait: `500ms`, `10s` or ISO-8601; 10 s by default                                 |
| `Wow-Request-Id`           | The caller's id of the request: a retry with the same one is answered, not decided again      |
| `If-Match: "n"`            | The version the aggregate must be at; 412 otherwise                                           |
| `Accept: text/event-stream` | One server-sent event per hop reached, until the wait ends                                   |
| `Wow-Scope`                | The tenant, owner and space to act in, for a caller allowed to act across scopes             |
| `Wow-Space`                | The space, among the caller's own, to act in                                                  |

Without `Wow-Wait` a command waits until the snapshot holds its commit, so a
`GET …/state` right after it reads its own write. A state read answers
`ETag: "<version>"` and honours `If-None-Match`.

## Queries

A query names its operation in `kind` (`list`, `paged`, `cursor`, `single`,
`count`, `aggregate`) beside the query itself; `Accept: text/event-stream`
streams a `list`. The caller sees only its own aggregates (its tenant, owner and
space), and the ones not deleted unless the query names its deletion state:

```bash
curl -X POST localhost:8080/example/order/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"lastEventTime","direction":"DESC"}],"size":20}'
curl -X POST localhost:8080/example/order/snapshot/query \
  -H 'Content-Type: application/json' -d '{"kind":"count","filter":{"op":"MATCH_ALL"},"limit":1000}'
curl -X POST localhost:8080/example/order/events/query \
  -H 'Content-Type: application/json' -d '{"kind":"list","filter":{"op":"ID","value":"<order id>"}}'
```

A count answers `{count, exact}`: `exact` is `false` when there are more than
its `limit` (at most `wow.query.http.count-limit`, 10000). The `events` model
reads one aggregate's event streams by version: the filter names its id, and
may bound `version` or `commitTime`, nothing else. A query the model refuses is
400 `wow.query.invalid`, its violations naming each rule (`UNKNOWN_FIELD`, …);
one that does not read as a query is 400 `wow.query.malformed`.

The snapshots are queried on PostgreSQL (and MongoDB); on the memory backend no
query backend serves them, and their routes answer 404 (the end-to-end tests
give the host the in-memory reference backend). Since the Manifest part carries
no state schema (below), a query filters and sorts by the system fields
(`aggregateId`, `ownerId`, `version`, `lastEventTime`, `deleted`, `tags`, …)
only; the records still carry the whole `state`.

The Manifest part `shop-domain` generates names the commands, events, routes,
errors and lifecycle, without JSON schemas: the schemas are introspected by the
Gradle plugin `me.ahoo.wow`, which this build cannot apply to its own projects.

`:example-e2e` (`example/e2e`) runs the shop and the inventory end to end, each
in a JVM of its own, on Kafka and PostgreSQL in containers:

```bash
./gradlew -Pwow.scope=v10 :example-e2e:integrationTest
```

## Sagas

`CartSaga` (subscription `CartSaga.onOrderCreated`) removes the products of an
order placed with `"fromCart":true` from the owner's cart. A wait can follow
the order through it: `Wow-Wait: committed > handled(example/CartSaga.onOrderCreated)`
answers once the saga's command is committed, and `... > snapshot` once the
cart's snapshot holds it.

`OrderSaga` reserves a placed order's stock in the inventory (decision D144):

1. `OrderSaga.onOrderCreated` sends `inventory.stock.ReserveStock` for each
   product to `wow.inventory.commands`. The inventory is another context, so
   the saga's hop ends once the broker has the commands:
   `Wow-Wait: committed > handled(example/OrderSaga.onOrderCreated)` answers
   then. A wait can follow the commands into the inventory:
   `Wow-Wait: committed > handled(example/OrderSaga.onOrderCreated) > committed(inventory)`
   answers once the inventory has committed every reservation, or at once with
   its refusal. The inventory signals the shop node the wait started on, on its
   internal port; a signal to a node that is gone is dropped, and the wait
   ends at its deadline at the latest.
2. A reservation the inventory rejects (`inventory.stock.insufficient`) comes
   back as a receipt on `wow.example.receipts`. `OrderSaga.onReserveRejected`
   takes it and sends `CancelOrder`; the order becomes `CANCELLED`, with the
   reason in `cancelReason`.
3. The inventory reads the order stream, `wow.example.order.state`: a shipped
   order commits its reservations, a cancelled one releases them.
   `Wow-Wait: handled(inventory/OrderStockSaga.onOrderShipped) > committed(inventory)`
   on `ship-order` answers once they are committed.

The saga finds the inventory's commands in the inventory's Manifest, a peer of
this service (decision D139). The Gradle plugin's `wowPeers` puts a peer's
Manifest on an application's class path; this build cannot apply the plugin to
its own projects, so `example/wow-peers.gradle.kts` does it for both servers.

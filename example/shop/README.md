# Shop: the Wow V10 example

The V10 example (decision D111): one bounded context, `example`, with two
aggregates, `cart` (a customer's cart, its id the customer's user id) and
`order` (placing, paying, shipping and receiving an order).

| Module        | Holds                                                                                   |
| ------------- | --------------------------------------------------------------------------------------- |
| `shop-api`    | Commands, events and value types; Jakarta constraints on `AddCartItem`                  |
| `shop-domain` | The aggregates, compiled by KSP into kernel models, a binding index and a Manifest part |
| `shop-server` | The Spring Boot application: the Wow host, stub inventory and pricing services          |

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
| `DELETE` | `/example/{aggregate}/{id}`                                           | Deletes the aggregate                             |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.restore-aggregate`           | Restores a deleted aggregate                      |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.transfer-owner`, `-space`    | Gives the aggregate another owner or space        |
| `POST`   | `/example/{aggregate}/{id}/wow.lifecycle.apply-tags`                  | Replaces the aggregate's tags                     |
| `GET`    | `/example/{aggregate}/{id}/state`, `?version=n`                       | The latest state, or the one at version `n`       |
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

The stub catalog sells `apple` (2.50, 100 in stock), `pear` (4.00, 10) and
`durian` (30.00, 1); an order must quote the current price.

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

The Manifest part `shop-domain` generates names the commands, events, routes,
errors and lifecycle, without JSON schemas: the schemas are introspected by the
Gradle plugin `me.ahoo.wow`, which this build cannot apply to its own projects.

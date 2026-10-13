# Wow eShop

A multi-service shop on Wow V10, modelled on the classic eShop reference application ([dotnet/eShop](https://github.com/dotnet/eShop)). It is the M6e milestone of the V10 roadmap (decisions D158 to D160). It checks two things before M7:

- that everything from M1 to M6 works together across several services;
- that the developer experience holds up for someone who knows Wow only from the public documentation and the Skills. Each gap goes into [DX-GAPS.md](DX-GAPS.md).

The eShop builds on the V10 modules only, and is never published. `example/shop` stays as the minimal example and as the reference for 9.x-to-V10 migration.

> **Status: E.1 done (the catalog).** The catalog has its products, brands and categories, and its storefront queries. The other contexts are still the skeleton of work package E.0, with one aggregate and one creating command each, on the memory backend. Their behaviour arrives in work packages E.2 to E.6.

## Contexts

Five bounded contexts. Each one runs as its own service, and the services will talk over Kafka.

| Context | Projects (`example/eshop/<context>/`) | Aggregate today | Planned behaviour | In the classic eShop |
| --- | --- | --- | --- | --- |
| `eshop-catalog` | `:eshop-catalog-{api,domain,server}` | `product`, `brand`, `category` (see [Catalog](#catalog)) | Done (E.1): products, brands and categories; storefront queries with `@QueryIndex` | `Catalog.API` (brands and types) |
| `eshop-basket` | `:eshop-basket-{api,domain,server}` | `basket` (`AddBasketItem`), one per buyer | Checkout sends a cross-context command to ordering (E.2) | `Basket.API` |
| `eshop-ordering` | `:eshop-ordering-{api,domain,server}` | `order` (`PlaceOrder`) | The order state machine, and a placement saga with a grace period (E.3) | `Ordering.API`, `Ordering.Domain`, `OrderProcessor` |
| `eshop-inventory` | `:eshop-inventory-{api,domain,server}` | `stock` (`ReceiveStock`), one per product | Stock reservation and confirmation. A shortfall is rejected, and ordering's `@OnRejected` cancels the order (E.4) | The `AvailableStock` of `Catalog.API` and its stock-confirmation events, split out into a context of their own |
| `eshop-payment` | `:eshop-payment-{api,domain,server}` | `payment` (`RequestPayment`) | A reactor over a simulated payment gateway (E.5) | `PaymentProcessor` |

The rest of the classic eShop maps like this:

- **Identity.** `Identity.API` becomes Wow's `:cosec`. It is not a context of its own.
- **Event bus.** The RabbitMQ event bus becomes Kafka.
- **Sales data.** Sales data reaches ClickHouse through the ClickHouse Sink, as a dataset.
- **Web front ends.** They come after the backend, written with the TypeScript client that work package 5a.3 generates (D160).
- **End-to-end tests.** `:eshop-e2e` (`example/eshop/e2e`) holds them. Today it starts the catalog and basket services, each in its own JVM, and sends one command to each; it also runs both in the test's JVM, each selecting its own context with `wow.contexts.include`. Work package E.6 extends it to all five services on PostgreSQL, Kafka and ClickHouse, with the full order flow, failure scenarios and a load test.

### Naming

- **Context names.** Each context is named `eshop-<context>` (`@BoundedContext` in each `-domain`), so it does not clash with the shop's `example`.
- **No dots.** A context name holds no dot, because Wow writes a type as `<context>.<aggregate>`.
- **Commands and routes.** Commands and events are named `eshop-catalog.product.CreateProduct`. Routes start with `/eshop-catalog/product/`.
- **Gradle projects.** Every Gradle project is named `eshop-*`, so none clashes with the shop's projects, `:inventory-*` included.

### Dependencies

Each `-api` depends on `:api`. Each `-domain` depends on its API and on `:kernel`, and the compiler (KSP) generates its models, its binding index and its Manifest fragment. Each `-server` is a Spring Boot application on the host, `:spring-boot`. The architecture tests (`test/architecture`, `V10Rules`) enforce the rest:

- The eShop reaches V10 modules and other eShop projects only.
- No `-api` or `-domain` reaches `:runtime`.

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
curl -s -X POST localhost:8081/eshop-catalog/brand/wow/create-brand \
  -H 'Content-Type: application/json' -d '{"name":"Wow"}'
curl -s -X POST localhost:8081/eshop-catalog/category/mugs/create-category \
  -H 'Content-Type: application/json' -d '{"name":"Mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/create-product \
  -H 'Content-Type: application/json' -d '{"name":"Wow mug","price":8.50,"brandId":"wow","categoryId":"mugs"}'
curl -s -X POST localhost:8081/eshop-catalog/product/mug/publish-product
curl -s localhost:8081/eshop-catalog/product/mug/state
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item \
  -H 'Content-Type: application/json' -d '{"productId":"mug","quantity":2}'
```

## Tests

The catalog's tests are in three places:

- **`:eshop-catalog-domain`.** The specs of the three aggregates (`aggregateSpec`), and a test of the binding index and fragment.
- **`:eshop-catalog-server`.** Its commands and rejections over HTTP (`CatalogEndToEndTest`), and the storefront's query contract on the memory backend (`CatalogQueryContract`).
- **`:eshop-e2e`.** The storefront queries and the indexes on PostgreSQL, in a container (`CatalogPostgresTest`). The catalog server has no `integrationTest` source set of its own ([G17](DX-GAPS.md)).

```bash
./gradlew -Pwow.scope=v10 :eshop-catalog-domain:check :eshop-catalog-server:check   # one context
./gradlew -Pwow.scope=v10 :eshop-e2e:integrationTest                                # the services together
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest                               # everything V10, the eShop included
```

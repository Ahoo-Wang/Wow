---
title: Service Routes
description: Serve an HTTP route of your own code with @ServiceRoute, its parameters, body, answers and refusals, and read what the Manifest, the OpenAPI document and the generated TypeScript client say of it.
outline: deep
---

# Service Routes

A command or a query covers most of what a client asks. When a read is shaped for one screen, or a computation is no command of any aggregate, a service route serves it: an HTTP route of the context whose handler is a function of your own. It is part of the service's contract like any other route: the Manifest lists it, the edge mounts it and admits its caller, the OpenAPI document describes it, and the generated clients call it. This page follows the eShop catalog's storefront, which lists a product as a shopper sees it, lists a category's products and prices a basket's lines.

## Declare a route

A route is a `suspend` member function annotated `@ServiceRoute`, of a class the host provides:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#listing -->

```kotlin
/** The product [id] as the storefront shows it; none (`204 No Content`) while it is not published. */
@ServiceRoute(method = RouteMethod.GET, path = "listings/{id}")
suspend fun listing(call: RouteCall, id: String): ProductListing? {
    val product = call.queries.snapshots<ProductState>().state(id)?.state ?: return null
    return product.takeIf { it.status == ProductStatus.PUBLISHED }?.listing(id)
}
```

- **The method and the path.** `method` is `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. `path` is below the context, with no leading slash: lower-kebab words and `{variables}`. The route is served at `<METHOD> /<context>/<path>`, here `GET /eshop-catalog/listings/{id}`.
- **The name.** `name` is the route's name in the Manifest, its identity for compatibility and its generated client's method; the function's name when empty. It is lowerCamelCase.
- **The errors.** `errors` lists the codes the route refuses with ([below](#refusals-and-error-codes)).
- **The module.** The function belongs to the domain module, which the compiler processes: the model processor lists the route in the context's Manifest with the schemas of its body and answer, and generates its binding.

The compiler refuses a route that breaks these rules: a function that is not `suspend`, a path variable that binds no parameter, a parameter that binds nothing, two bodies, or a type a route cannot carry.

### Give the host its instance

The generated binding calls the function on the instance of its class the host's dependencies hold: on the Spring Boot host, a bean. The catalog's server declares one in a configuration of its own:

<!-- sample: examples/eshop/catalog/catalog-server/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogRoutes.kt#storefront -->

```kotlin
/** The storefront's service routes: their generated binding calls them on this bean. */
@Bean
fun storefront(): Storefront = Storefront()
```

The node binds every route of its Manifest to its handler when it starts, and does not start when a route has no handler or a handler no route.

Every host that serves the context needs that instance, not only the service's own server: a second application or a test host with the domain module on its class path does not start without it, and fails with a `MissingDependencyException` that names the class ("No dependency of … Storefront to build the service routes of … Storefront"). So a module's first route is a change to each of its hosts, which declare the bean as the server does. A node that leaves the context out (`wow.contexts.include`, `wow.contexts.exclude`) builds none of its routes and needs none.

## Parameters

Each parameter binds by its kind:

| The parameter | Binds |
| --- | --- |
| Named as a variable of the path (`{categoryId}`) | That path segment: a `String`, an `Int`, a `Long`, a `Double`, a `Boolean` or an enum; never nullable |
| Annotated `@RouteQuery` | The query parameter of its name, or of `@RouteQuery(name)`, of the same types: optional when the parameter is nullable, required otherwise |
| A `RouteCall` | The call: the admitted caller, its tenant and scope, the request id, and the commands and queries the handler runs as that caller |
| The one other parameter, on a `POST`, `PUT` or `PATCH` | The request body ([below](#bodies)) |

The storefront's category listing takes a path variable, an optional query parameter and the call:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#category -->

```kotlin
/** The published products of [categoryId], cheapest first: [limit] of them, 20 when it is not given. */
@ServiceRoute(
    method = RouteMethod.GET,
    path = "categories/{categoryId}/listings",
    errors = [Refusals.LIMIT_OUT_OF_RANGE],
)
suspend fun categoryListings(call: RouteCall, categoryId: String, @RouteQuery limit: Int?): List<ProductListing> {
    val size = limit ?: DEFAULT_LIMIT
    if (size !in 1..MAX_LIMIT) {
        throw RouteRefusal(Refusals.LIMIT_OUT_OF_RANGE, "A page holds 1 to $MAX_LIMIT products, not $size.")
    }
    val query = listQuery {
        filter {
            ProductStateProperties.STATE__CATEGORY_ID eq categoryId
            ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
        }
        sort { ProductStateProperties.STATE__PRICE.asc() }
        limit(size)
    }
    return call.queries.snapshots<ProductState>().list(query).map { it.state.listing(it.ref.id) }.toList()
}
```

- A handler sees no HTTP request. `call.queries` reads as the caller, so the route sees what the caller may see ([Query Snapshots](./queries)); `call.commands` sends as the caller ([Call Other Services](./calling-services)).
- A path variable or query parameter that does not read as its type, or a body that does not read as the route's request, is answered `400 wow.route.malformed`, its violation naming the parameter (`body` for the body). A missing required query parameter is answered the same.

## Bodies

A `POST`, `PUT` or `PATCH` route takes its body as its one parameter of no other kind. The quote reads the lines of a basket:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#quote -->

```kotlin
/** The price of [request]'s lines, each at its product's price; refused when one is not on the storefront. */
@ServiceRoute(
    method = RouteMethod.POST,
    path = "quotes",
    errors = [Refusals.NOT_ON_SALE, Refusals.QUANTITY_NOT_POSITIVE],
)
suspend fun quote(call: RouteCall, request: QuoteRequest): Quote {
    val products = call.queries.snapshots<ProductState>()
    val lines = request.lines.map { line ->
        if (line.quantity < 1) {
            throw RouteRefusal(
                Refusals.QUANTITY_NOT_POSITIVE,
                "Product [${line.productId}] is quoted ${line.quantity}.",
            )
        }
        val product = products.state(line.productId)?.state?.takeIf { it.status == ProductStatus.PUBLISHED }
            ?: throw RouteRefusal(Refusals.NOT_ON_SALE, "Product [${line.productId}] is not on the storefront.")
        QuotedLine(line.productId, line.quantity, product.price, product.price * line.quantity.toBigDecimal())
    }
    return Quote(lines, lines.fold(BigDecimal.ZERO) { total, line -> total + line.amount })
}
```

A body, like an answer, is a class of its own, or a `List`, a `Set` or a `Collection` of one; a map, a scalar, a bare `JsonNode` or a stream (a `Flow`, a `Publisher`) fails the compilation. Keep the classes in the api module beside the commands (`ProductListing`, `QuoteRequest` and `Quote` are in `eshop-catalog-api`), so the Manifest carries their schemas and peers get them. A Jackson `ObjectNode`, a JSON object of any shape, is for a route that relays a body it does not own, such as another service's command; a route of your own takes a class, so its contract is typed.

## Answers

The function's return type is the route's answer:

| The function returns | The route answers | The Manifest says |
| --- | --- | --- |
| A class, a `List`, a `Set` or a `Collection` of one | `200` with the value as JSON | its `response` schema |
| `Unit` | `204 No Content` | no `response` |
| A nullable type (`ProductListing?`) | `200` with the value, and `204 No Content` when it is `null` | its `response` schema and `responseOptional: true` |

The storefront's `listing` answers `204` for a product that is not published, a draft or a delisted one, and for an id the caller cannot see. A client of the route reads the `204` as no value: the generated TypeScript method resolves `undefined` ([below](#the-generated-typescript-client)).

## Refusals and error codes

A handler refuses a call by throwing a `RouteRefusal` with a code, a message shown to the caller, and optional `extensions`, which the problem writes as its members. The codes are `@ErrorCode` constants of the module, full codes of the context:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Storefront.kt#refusals -->

```kotlin
/** The codes the storefront refuses a call with: full codes of the context, which its routes list. */
object Refusals {
    @ErrorCode(description = "A page of listings holds 1 to 50 products.", status = 400)
    const val LIMIT_OUT_OF_RANGE = "eshop-catalog.limit-out-of-range"

    @ErrorCode(description = "A quote's product is not published on the storefront.", status = 409)
    const val NOT_ON_SALE = "eshop-catalog.not-on-sale"

    @ErrorCode(description = "A quote's line asks for one or more of its product.", status = 400)
    const val QUANTITY_NOT_POSITIVE = "eshop-catalog.quantity-not-positive"
}
```

- **A full code.** A local name (no dot) is qualified with the aggregate that rejects with it, and a route belongs to no aggregate: give a route's codes as `<context>.<code>`, declared outside an aggregate as here ([Rejections and Error Codes](./rejections#declare-the-codes)).
- **List them.** `@ServiceRoute(errors)` lists each code the route refuses with: the Manifest, the OpenAPI document and the clients read the route's refusals from it. The service's merged Manifest refuses a listed code its context does not declare. A code the handler throws that the route does not list is still answered, and the node logs it once.
- **The answer.** A refusal is answered as an RFC 9457 problem of its code, with the code's `@ErrorCode(status)`; `422` when the context declares no status for it. The storefront's `limit=0` is `400 eshop-catalog.limit-out-of-range`.
- **A relayed answer.** A `WowProblemException` the handler lets through, another service's answer to a call it made, passes through with its status, code and result.

## Who may call it

The edge admits a route's caller as it admits a query's: it authenticates the request, takes the scope it may name in `Wow-Scope`, and asks the node's `ServiceRouteAuthorizer`s, whose first refusal is the answer. With none registered, every authenticated caller may call the context's routes. On the public port, a request that carries `Wow-Call`, a service's synchronous call, is refused with `403 wow.auth.forbidden`. CoSec names a route `<context>:<route>` ([Secure a Deployment](../operate/security#where-cosec-decides)).

## What the Manifest says

The context's `routes` in the Manifest list each route ([Manifest reference](../reference/manifest#serviceroute)):

| Field | Holds |
| --- | --- |
| `name` | The route's name |
| `route` | Its method and full path: `GET /eshop-catalog/listings/{id}` |
| `parameters` | Each path variable and query parameter: its `name`, where it is read (`in`), its schema and whether it is `required` |
| `request` | The schema of the body; none without one |
| `response` | The schema of the answer; none for `Unit` |
| `responseOptional` | `true` when the answer may be absent (`204` for `null`) |
| `errors` | The codes the route refuses with |
| `description` | The function's KDoc |
| `deprecated` | `true` when the function is `@Deprecated` |

The compatibility check compares a route with its baseline by name ([The Manifest and Compatibility](./manifest)):

| The change | Is |
| --- | --- |
| A route added, an optional query parameter added, a code added or no longer listed, a response made required | Additive |
| A route removed, its method or path changed, a parameter made required or its schema changed, a required parameter added | Breaking |
| A body or an answer added or removed; a body that accepts less, an answer that may write more | Breaking |
| A response made optional: a reader that expects a body may get `204` | Breaking |

## What the OpenAPI document says

`GET /wow/openapi` describes each route as an operation of its path and method ([OpenAPI and springdoc](./openapi)):

- `operationId` is `<context>.<name>` (`eshop-catalog.listing`), the context its tag, the name its summary and the KDoc its description.
- Its parameters are the path variables and query parameters with their schemas, and its `requestBody`, required, the body's schema.
- Its answers are `200` with the answer's schema, `204` beside `200` for a `responseOptional` route, and `204` alone for one of no answer. It lists `400` (`wow.route.malformed`), `401` and `403`, the framework's answers to a route, and the status of each code in its `errors`, with that code alone: the category listing's `400` lists `wow.route.malformed` and `eshop-catalog.limit-out-of-range`, the quote's `409` lists `eshop-catalog.not-on-sale`, and no route lists another route's codes or a command's. A code whose `@ErrorCode` declares no status is `422`. `x-wow-route.errors` holds the route's codes too.

## The generated TypeScript client

`wow-generator` writes the context's routes into `routes.ts`: a class `<Context>Routes` with a method per route, which `<Context>Client` holds as `routes` ([Generate Clients](../typescript/generate)). The catalog's are `EshopCatalogRoutes`: `listing(id)` answers `Promise<ProductListing | undefined>`, `categoryListings(categoryId, query?)` takes its query parameters as an object, and `quote(body)` its body. The eShop's web app calls them:

<!-- sample: examples/eshop/web/src/shop/storefront.test.ts#listing -->

```ts
const mug = await catalog.routes.listing("mug");
const draft = await catalog.routes.listing("draft");
```

`draft`, answered `204`, is `undefined`. A refusal rejects with the problem, a `WowProblemError` with its status and code:

<!-- sample: examples/eshop/web/src/shop/storefront.test.ts#refusal -->

```ts
const refused = catalog.routes.categoryListings("mugs", { limit: 0 });
await expect(refused).rejects.toMatchObject({
  status: 400,
  code: "eshop-catalog.limit-out-of-range",
});
```

A `BigDecimal` of the body or the answer reads as text, as everywhere in the generated clients: `ProductListing.price` is a `string`.

## Test a route

A handler is tested by calling it with a call the test DSL builds, `routeCall(caller, queries, commands, requestId)` of `wow-test`: it acts in its caller's tenant and scope, and a handler that reads or sends what the test gave none of fails, saying what to give. The queries can be a real query side over snapshots held in memory (`memoryQueries` of `wow-test-query`), and the commands an `answeringCommandClient { call -> … }`, which answers each request and keeps it in `calls` ([Testing](./testing#service-routes)):

<!-- sample: examples/eshop/catalog/catalog-domain/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/StorefrontTest.kt#call -->

```kotlin
/** A shopper's call: its reads run on a query side in memory as the anonymous caller; it sends no command. */
private val call = routeCall(
    queries = memoryQueries {
        snapshot(model, "mug", created("8.50"), ProductPublished)
        snapshot(model, "cup", created("5.00"), ProductPublished)
        snapshot(model, "bowl", created("7.00"), ProductPublished)
        snapshot(model, "plate", created("3.00", categoryId = "plates"), ProductPublished)
        snapshot(model, "draft", created("1.00"))
        snapshot(model, "old", created("2.00"), ProductPublished, ProductDelisted("Out of season"))
    }.of(Caller.ANONYMOUS),
)
```

`StorefrontTest` gives it `memoryQueries { snapshot(productModel(), "mug", ProductCreated(...), ProductPublished) }.of(Caller.ANONYMOUS)`, so each product's state is the one its own events build, and asserts each route's answer and refusal. `CatalogEndToEndTest` calls the routes over HTTP on the started server: the `204`, the listing and the `400` problem.

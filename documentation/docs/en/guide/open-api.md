---
title: OpenAPI
description: Publish Wow route contracts from generated metadata while keeping compile-time metadata, runtime WebFlux routes, schemas, and clients distinct.
---

# OpenAPI

`wow-openapi` builds OpenAPI 3.1 operations and components for Wow's route contracts. The complete path is:

```text
Wow annotations
  -> KSP: META-INF/wow-metadata.json
  -> MetadataSearcher: runtime aggregate metadata
  -> RouterSpecs / RouteCatalog
       -> WebFlux RouterFunction
       -> OpenAPI paths and components
  -> API client or external client generator
```

The shared `RouteCatalog` is the important boundary: runtime WebFlux handlers and the OpenAPI renderer consume the same route contracts. KSP does not generate a running HTTP server, and OpenAPI does not prove that a backend, query capability, authentication policy, or client deployment works.

Generated templates can overlap without being equal: a command whose path ends in `count` generates `POST …/{resource}/{id}/count`, which also matches the query path `POST …/{resource}/snapshot/count`. In catalog order the command came first, so the query could never be reached. The WebFlux router therefore tries routes in the catalog's dispatch order (`RouteCatalog.dispatchRoutes`): where every path one template matches is also matched by another, the narrower template is tried first; every other pair keeps the catalog order, so no request that reached a route before goes elsewhere. Two templates of the same method that differ only in their variable names match exactly the same paths; the catalog rejects them at startup and names both routes.

## Installation

`wow-openapi` supplies route contracts and schema components:

::: code-group

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-openapi")
```

```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-openapi'
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-openapi</artifactId>
    <version>${wow.version}</version>
</dependency>
```

:::

For a Spring Boot application, use the starter and WebFlux runtime. Add Springdoc only when the service should publish `/v3/api-docs` or Swagger UI:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter")
implementation("me.ahoo.wow:wow-webflux")
implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
```

`OpenAPIAutoConfiguration` creates `RouterSpecs`; `WebFluxAutoConfiguration` materializes the catalog into `RouterFunction`; `WowOpenApiCustomizer` merges the same catalog into Springdoc. `wow.openapi.enabled=false` disables Springdoc customization, not the WebFlux route catalog itself. The route catalog is built without generating any JSON Schema; schemas and components are generated only when the document is served (at startup, when Springdoc is present and `wow.openapi.enabled` is not `false`), so a service without the document pays no schema generation.

`RouterSpecs` renders the catalog into a document once, by `buildDocumentation()` at startup (or the first `RouterSpecs.mergeOpenAPI(openAPI)`); that render generates the schemas, which may block. Every `mergeOpenAPI` call then merges a copy of that document: it never generates a schema or blocks, may be called concurrently (for example by several Springdoc groups), and gets its own path items, operations and components, which a customizer may change without affecting other documents. Only the `Schema` instances are shared: copy a schema before changing it. Called on an event-loop thread before the document is rendered, `mergeOpenAPI` fails and asks for `buildDocumentation()`.

A custom route is added by a `RouteContributor` bean. A contributor only returns route contracts, which are plain data: it generates no schema and registers no component. A body or response type is referenced with `HttpSchema.TypeRef` (nested generics through `typeArguments`), and a reusable parameter, header, request body or response with an `HttpComponent` (`HttpComponent.parameter`, `header`, `requestBody`, `response`): its key is written once and the renderer builds and registers it once. The builder's `context` (`HttpComponentContext`) generates schemas (`schema`, `arraySchema`, `resolveType`, `componentSchema`) and references another component with `ref`; it cannot register components itself. Keys must be unique per kind: two component instances of one kind and key are both built and, once the schemas are generated, the render fails unless they built equal components, including the schemas they reference. A route's `handlerKey` names the `HttpRouteHandlerFunctionFactory` bean (from `wow-webflux`) that serves it; without one, the router fails at startup:

```kotlin
val reportResponse = HttpComponent.response("example.ReportResponse") { context ->
    description("Report")
    header(WowHeaders.ERROR_CODE, context.ref(WowComponents.errorCodeHeaderComponent))
    content(schema = context.schema(Report::class.java))
}

@Bean
fun reportRouteContributor(): RouteContributor = object : RouteContributor {
    override fun contributeGlobal(currentContext: NamedBoundedContext) = listOf(
        HttpRouteContract(
            routeId = "example.report.get",
            method = "GET",
            path = "/report",
            handlerKey = "example.report",
            responses = listOf(
                HttpResponse("200", component = reportResponse),
                WowComponents.badRequestResponse,
                WowComponents.notFoundResponse,
            ),
        )
    )
}

@Bean
fun reportHandlerFunctionFactory(reportService: ReportService): HttpRouteHandlerFunctionFactory =
    object : NoMetadataRouteHandlerFunctionFactorySupport("example.report") {
        override fun create(contract: HttpRouteContract) = HandlerFunction { _ ->
            ServerResponse.ok().body(reportService.report(), Report::class.java)
        }
    }
```

The components the built-in routes share are public in `WowComponents` (`me.ahoo.wow.openapi.contract`): the error responses `badRequestResponse`, `notFoundResponse`, `requestTimeoutResponse`, `tooManyRequestsResponse` and `unsupportedMediaTypeResponse` (`HttpResponse`s with the `DefaultErrorInfo` body and the `Wow-Error-Code` header), the `errorCodeHeader` response header and its `errorCodeHeaderComponent` (to reference with `context.ref` in your own response), the `spaceIdHeaderParameter` request header and the `idPathParameter`, `tenantIdPathParameter`, `ownerIdPathParameter` and `versionPathParameter` path parameters. They are the instances the built-in routes use, so a custom route that references them shares `wow.BadRequest`, `wow.Wow-Error-Code` and the rest instead of redefining them under the same key. In the builders, `ApiResponseBuilder` sets `description`, `header`, `content` and `extension`, and `RequestBodyBuilder` sets `description`, `required`, `content` and `extension`.

A contract's method, status codes and media types are plain strings (`"GET"`, `"200"`, `"application/json"`). The public API of `wow-openapi` is `RouterSpecs`, `RouteContributor`, `RouteCatalog`, the contract types in `me.ahoo.wow.openapi.contract` (with `BuiltInHttpRouteHandlerKeys` and `HttpComponent`), the route metadata (`aggregateRouteMetadata()`, `commandRouteMetadata()`), `DefaultRouteContributors` (the built-in routes, to combine with your own contributors when building `RouterSpecs` yourself), `OpenAPIComponentContext`, the built-in shared components `WowComponents`, the component builders `ApiResponseBuilder` and `RequestBodyBuilder`, `OpenAPISchemaBuilder` and `BoundedContextSchemaNameConverter`. The renderer, the catalog builder and the helpers behind the built-in routes are internal; the built-in contributor objects are internal or `@InternalWowApi`, shared with the Spring Boot starter but not part of the API.

Modules containing Wow annotations still need KSP plus `wow-compiler`, and their generated `META-INF/wow-metadata.json` resources must be present on the service runtime classpath. Do not hand-write or commit generated resources.

## Swagger-UI

Swagger UI is a Springdoc application feature, not part of the route contract itself. The default Springdoc pages are normally available at `/swagger-ui.html` and `/v3/api-docs` when the matching starter is present and enabled.

![Swagger-UI](/images/compensation/open-api.png)

Use the JSON document as the source for exact paths, methods, parameters, media types, operation IDs, and component references. A screenshot is not contract evidence.

## Aggregate Resource Ownership

Aggregate metadata combines `@AggregateRoute`, the aggregate's policies (`@Spaced`, `@AggregateOwner`, static tenant), command-level `@CommandRoute`, tenant metadata, and generated command/event types. Route ownership affects path shape; it is not caller authorization.

The route catalog also controls whether an aggregate is published at all (`@AggregateRoute(enabled = false)`). This does not remove command handling or storage behavior outside HTTP.

## RESTful URL PATH Spec

The general aggregate route shape is:

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

The default route starts at the resource name. Wow does not prepend a bounded-context alias to local paths. Do not construct paths from naming conventions in client code; inspect generated OpenAPI.

On a route that declares `{tenantId}`, `{ownerId}` or `{id}`, the path segment is the value: the `Command-Tenant-Id`, `Command-Owner-Id` and `Command-Aggregate-Id` headers are not read in its place, and a segment that decodes to a blank value (such as `%20`) answers `400` with error code `IllegalArgument` instead of falling back to the header or to the default tenant. Routes that do not declare the variable keep reading the header (and a static tenant always applies).

### Request Identity

Each route decides, once, when the router is built, where it takes each identity fact from: the path variables its contract declares, the aggregate's static tenant, ownership policy and space. Commands, queries, point reads, snapshot regeneration and event compensation all read the same binding, so every route kind follows one rule:

| Fact | Source, first that applies |
| --- | --- |
| Tenant | the static tenant (a tenant header is ignored) → `{tenantId}` → `Command-Tenant-Id` |
| Owner | `{ownerId}` → `{id}` when the owner is the aggregate ID (`OwnerPolicy.AGGREGATE_ID`) → `Command-Owner-Id` |
| Aggregate ID | owner is the aggregate ID: `{ownerId}` → `{id}` → `Command-Owner-Id` → `Command-Aggregate-Id`; otherwise `{id}` → `Command-Aggregate-Id` |
| Space | spaced aggregate only: `Wow-Space-Id` → header aliases (CoSec's `CoSec-Space-Id`) |
| Request ID | `Command-Request-Id` → header aliases (CoSec's `CoSec-Request-Id`) |
| Operator | the authenticated principal |

A blank header counts as absent. For a command, the body's `@TenantId` / `@OwnerId` / `@AggregateId` still comes first, as for any `CommandGateway` caller.

Since 9.3.0, a request that contradicts the tenant or owner its route fixes is rejected with `400` and error code `IllegalArgument`: a `Command-Tenant-Id` header that differs from the `{tenantId}` segment, a `Command-Owner-Id` header that differs from the `{ownerId}` segment (or from `{id}` of an aggregate owned by its ID), and a command body whose `@TenantId` differs from the static tenant or the `{tenantId}` segment, or whose `@OwnerId` differs from the owner segment. Before, the body silently won and the header was ignored. A tenant header sent to an aggregate with a static tenant is still ignored. The same value, or no value (an absent header or body property), is accepted. A blank `@OwnerId` in the body counts as no value only on an aggregate owned by its ID, whose owner comes from `{id}` (the command's owner is then its aggregate ID, as in 9.2); against an `{ownerId}` segment, or a blank `@TenantId` against the static tenant or `{tenantId}`, a blank body value is a contradiction. Where the route fixes nothing, the body still wins over a header. Who this hits: a client or gateway that sends one global `Command-Owner-Id` or `Command-Tenant-Id` header on every request, which now contradicts the `{ownerId}` / `{tenantId}` of routes for other owners or tenants (drop the header where the path states the fact); and a non-ASCII ID that the client percent-encodes in the path and also in a header or body: the server decodes the path segment but not the header or body value, so the two differ (send the header or body value unencoded, or leave it out).

On an aggregate owned by its ID, a command on a route that states `{id}` but not `{ownerId}` now takes the aggregate and its owner from the path; before 9.3.0 a `Command-Owner-Id` header replaced both. A read on such a route (an event stream load, say) does not filter by that derived owner, since the ID already selects the aggregate and one created in-process may store a blank owner; a `Command-Owner-Id` that agrees with `{id}` still narrows it, as before.

### Tenant Resources

A dynamic tenant aggregate's default command/state routes receive the `tenant/{tenantId}` prefix. Snapshot query contributors also retain a base route and add the tenant-scoped variant. Tenant path data is passed to runtime handlers and query rewriting, but the application must bind it to the authenticated principal and protect the unscoped query route explicitly.

### Space Resources

Spaced routes declare the `Wow-Space-Id` request header. Space does not add a path segment. On a spaced aggregate's routes the header participates in command context and query scoping; it is not authentication. The routes of an aggregate that is not spaced neither declare nor read it. The global command facade declares it for every command, since the target aggregate is known only from the request, and applies it only when that aggregate is spaced.

### Owner Resources

`@AggregateOwner(OwnerPolicy.ALWAYS)` adds `owner/{ownerId}` and keeps the resource ID on default owned routes; snapshot and event-stream queries publish both base and owner-scoped variants, and, when the aggregate also has a dynamic tenant, a `tenant/{tenantId}/owner/{ownerId}` variant that narrows to both:

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders")
@AggregateOwner(OwnerPolicy.ALWAYS)
class Order(private val state: OrderState)
```

`AGGREGATE_ID` uses owner ID as aggregate ID and omits the separate resource-ID segment:

```kotlin
@AggregateRoot
@StaticTenantId
@AggregateRoute(resourceName = "cart")
@AggregateOwner(OwnerPolicy.AGGREGATE_ID)
class Cart(private val state: CartState)
```

Query-schema routes are an exception: `/{aggregate}/snapshot/schema`, `/{aggregate}/event/schema` describe query models and therefore do not have tenant/owner path variants. A spaced aggregate's common contract may still declare `Wow-Space-Id`.

## Global Routes

Global contracts are contributed independently of aggregate routes:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/wow/command/send` | Generic command facade used by the API client |
| `POST` | `/wow/command/wait` | Wait-signal receiving endpoint |
| `GET` | `/wow/metadata` | Loaded Wow metadata |
| `POST` | `/wow/bi/script` | BI synchronization script generation (only with `wow-bi` on the classpath) |
| `GET` | `/wow/id/global` | Global ID generation |

Publishing one of these routes does not secure it. Apply the service's authentication, authorization, rate-limit, and network-exposure policy.

### Get Wow Metadata

`GET /wow/metadata` returns the `WowMetadata` assembled from compile-time resources on the runtime classpath. It is useful for diagnosing missing annotated modules:

```shell
curl 'http://localhost:8080/wow/metadata' \
  -H 'accept: application/json'
```

Representative response shape:

```json
{
  "contexts": {
    "example-service": {
      "alias": "example",
      "scopes": ["me.ahoo.wow.example.api"],
      "aggregates": {
        "order": {
          "scopes": ["me.ahoo.wow.example.api.order"],
          "type": "me.ahoo.wow.example.domain.order.Order",
          "tenantId": null,
          "id": null,
          "commands": ["me.ahoo.wow.example.api.order.CreateOrder"],
          "events": ["me.ahoo.wow.example.api.order.OrderCreated"]
        }
      }
    }
  }
}
```

The response is runtime evidence that metadata was loaded. It is not proof that every generated route was materialized; inspect `/v3/api-docs` or the route catalog as the next gate.

### Generate BI Sync Script

`POST /wow/bi/script` generates ClickHouse synchronization and expansion SQL for current local aggregates. The route and OpenAPI operation are present when `wow-bi` is on the classpath (add it, or request the Starter's `bi-support` capability) and `wow.bi.script.enabled` is not `false`; otherwise both are absent. Enabling the route does not authorize it.

The endpoint requires an `application/json` body. `{}` means `DEPLOY` with server options unchanged. Request fields include deployment overrides, `operation`, and `replayFromEarliestConfirmed`; `previousManifest` is not part of the contract. `topology.mode` is required when `topology` is present. `STANDALONE` rejects a cluster object; `CLUSTER` accepts only cluster `name` and `installation` overrides.

`maxExpansionDepth` may be lowered by a request but cannot exceed the server ceiling. Length limits apply equally to server configuration and non-null overrides: `database` and `consumerDatabase` 128, `timezone` 64, `topicPrefix` 128, `kafkaBootstrapServers` 4096, and cluster `name`/`installation` 128. An over-limit server value fails startup; an over-limit request returns `400`.

| Status | Contract |
|---|---|
| `200 application/sql` | SQL text; `Wow-BI-Diagnostic-Count` reports omitted diagnostics |
| `200 application/json` | SQL, destructive flag, diagnostics, and the same count header |
| `400` | malformed body, invalid override/topology, or unsupported RESET precondition |
| `406` | no acceptable representation; `Wow-Error-Code: NotAcceptable` |
| `415` | missing/unsupported content type; `Wow-Error-Code: UnsupportedMediaType` |
| `500` | unexpected generation failure |
| `502` / `503` / `504` | catalog inconsistency / unavailable inspection / timeout |

`RESET` requires `replayFromEarliestConfirmed=true`, a configured server-side `consumerGroupNamespace`, and an available inspector. `DEPLOY` and `RESET` do not migrate databases, consumer-group namespace, or topology. The old `GET` method has no route.

```shell
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

See [Business Intelligence](./bi) for expansion semantics and [BI Script Configuration](./configuration#bi-script-configuration) for server options.

### Generate Global ID

```shell
curl 'http://localhost:8080/wow/id/global' \
  -H 'accept: text/plain'
```

```text
0U2MNGBQ0001001
```

The returned value is text. Client code should treat its layout as opaque unless a separate CosId contract is explicitly required.

## Aggregate Routing Specification

The catalog contributes command, state, event, snapshot, and query routes from aggregate metadata. Common query suffixes are:

| Method | Suffix | Request / response |
|---|---|---|
| `GET` | `snapshot/schema` | Snapshot capability descriptor (`QueryModelDescriptor`), with ETag |
| `GET` | `event/schema` | EventStream capability descriptor (`QueryModelDescriptor`), with ETag |
| `POST` | `snapshot/single` | `SingleQuery` -> materialized snapshot |
| `POST` | `snapshot/single/state` | `SingleQuery` -> state only |
| `POST` | `snapshot/list` / `list/state` | `ListQuery` -> array or SSE |
| `POST` | `snapshot/paged` / `paged/state` | `PagedQuery` -> `PagedList` |
| `POST` | `snapshot/cursor` / `cursor/state` | `CursorQuery` -> complete-snapshot / state-only `CursorPage` |
| `POST` | `event/cursor` | `CursorQuery` -> EventStream `CursorPage` |
| `POST` | `snapshot/count` | `FilterExpression` -> exact count |
| `POST` | `snapshot/aggregation` | `AggregationQuery` -> dynamic rows or SSE |

Query contracts appear in three distinct layers:

1. Generic query component schemas define the canonical request JSON shapes.
2. Every aggregate-specific query request-body component references a generic schema and exposes static `x-wow-query-fields`, whose enum combines system fields with fields inferred by `InferredQuerySchemaSource`.
3. The runtime `snapshot/schema` and `event/schema` routes publish the capability descriptor of the HTTP entry, derived from the merged schema and backend-proven capabilities.

`x-wow-query-fields` is OpenAPI design-time metadata on the request-body component; it is not embedded as JSON request properties and is not a backend capability claim.

The `CursorQuery` component contains `filter`, `projection`, `sort`, `size`, and an optional `cursor`, but no `pagination`. `CursorPage` contains only `list` and nullable `nextCursor`, with no total. These cursor routes declare `application/json` only; there is no SSE cursor contract.

`wow-apiclient` contains hand-maintained CoApi interfaces for Wow command and snapshot contracts. [`wow-generator`](./typescript/generated-client.md) generates TypeScript clients from the published OpenAPI document, and other tools may generate clients for other languages. Client generation is downstream of OpenAPI: KSP metadata does not generate those clients, and regenerating a client does not change server field semantics. Review generated diffs whenever the OpenAPI contract changes.

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

`OpenAPIAutoConfiguration` creates `RouterSpecs`; `WebFluxAutoConfiguration` materializes the catalog into `RouterFunction`; `WowOpenApiCustomizer` merges the same catalog into Springdoc. `wow.openapi.enabled=false` disables Springdoc customization, not the WebFlux route catalog itself.

Modules containing Wow annotations still need KSP plus `wow-compiler`, and their generated `META-INF/wow-metadata.json` resources must be present on the service runtime classpath. Do not hand-write or commit generated resources.

## Swagger-UI

Swagger UI is a Springdoc application feature, not part of the route contract itself. The default Springdoc pages are normally available at `/swagger-ui.html` and `/v3/api-docs` when the matching starter is present and enabled.

![Swagger-UI](/images/compensation/open-api.png)

Use the JSON document as the source for exact paths, methods, parameters, media types, operation IDs, and component references. A screenshot is not contract evidence.

## Aggregate Resource Ownership

Aggregate metadata combines `@AggregateRoute`, command-level `@CommandRoute`, tenant metadata, and generated command/event types. Route ownership affects path shape; it is not caller authorization.

The route catalog also controls whether an aggregate is published at all (`@AggregateRoute(enabled = false)`). This does not remove command handling or storage behavior outside HTTP.

## RESTful URL PATH Spec

The general aggregate route shape is:

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

The default route starts at the resource name. Wow does not prepend a bounded-context alias to local paths. Do not construct paths from naming conventions in client code; inspect generated OpenAPI.

### Tenant Resources

A dynamic tenant aggregate's default command/state routes receive the `tenant/{tenantId}` prefix. Snapshot query contributors also retain a base route and add the tenant-scoped variant. Tenant path data is passed to runtime handlers and query rewriting, but the application must bind it to the authenticated principal and protect the unscoped query route explicitly.

### Space Resources

Spaced routes declare the `Wow-Space-Id` request header. Space does not add a path segment. The header participates in command context and query scoping; it is not authentication.

### Owner Resources

`AggregateRoute.Owner.ALWAYS` adds `owner/{ownerId}` and keeps the resource ID on default owned routes; snapshot queries publish both base and owner-scoped variants:

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders", owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)
```

`AGGREGATE_ID` uses owner ID as aggregate ID and omits the separate resource-ID segment:

```kotlin
@AggregateRoot
@StaticTenantId
@AggregateRoute(resourceName = "cart", owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState)
```

Query-schema routes are an exception: `/{aggregate}/snapshot/schema`, `/{aggregate}/event/schema`, and their `/refresh` routes describe query models and therefore do not have tenant/owner path variants. A spaced aggregate's common contract may still declare `Wow-Space-Id`.

## Global Routes

Global contracts are contributed independently of aggregate routes:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/wow/command/send` | Generic command facade used by the API client |
| `POST` | `/wow/command/wait` | Wait-signal receiving endpoint |
| `GET` | `/wow/metadata` | Loaded Wow metadata |
| `POST` | `/wow/bi/script` | BI synchronization script generation |
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

`POST /wow/bi/script` generates ClickHouse synchronization and expansion SQL for current local aggregates. The route and OpenAPI operation are present by default and are both removed when `wow.bi.script.enabled=false`. Enabling the route does not authorize it.

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

The catalog contributes command, state, event, snapshot, and query routes from aggregate metadata. Common snapshot query suffixes are:

| Method | Suffix | Request / response |
|---|---|---|
| `GET` | `snapshot/schema` | runtime `QueryModelSchemaMetadata` |
| `POST` | `snapshot/schema/refresh` | refreshed query-model schema |
| `GET` | `event/schema` | runtime EventStream `QueryModelSchemaMetadata` |
| `POST` | `event/schema/refresh` | refreshed EventStream query-model schema |
| `POST` | `snapshot/single` | `SingleQuery` -> materialized snapshot |
| `POST` | `snapshot/single/state` | `SingleQuery` -> state only |
| `POST` | `snapshot/list` / `list/state` | `ListQuery` -> array or SSE |
| `POST` | `snapshot/cursor` / `cursor/state` | `CursorQuery` -> JSON `CursorPage` |
| `POST` | `snapshot/paged` / `paged/state` | `PagedQuery` -> `PagedList` |
| `POST` | `event/cursor` | `CursorQuery` -> JSON `CursorPage` |
| `POST` | `snapshot/count` | `FilterExpression` -> exact count |
| `POST` | `snapshot/aggregation` | `AggregationQuery` -> dynamic rows or SSE |

Query contracts appear in three distinct layers:

1. Generic query component schemas define the canonical request JSON shapes; `CursorQuery` and each target-specific `CursorPage<T>` retain the generic response structure.
2. Every aggregate-specific query request-body component references a generic schema and exposes static `x-wow-query-fields`, whose enum combines system fields with fields inferred by `JsonQuerySchemaSource`.
3. The runtime `snapshot/schema` and `event/schema` routes publish their merged `QueryModelSchemaMetadata` and backend-proven capabilities.

`x-wow-query-fields` is OpenAPI design-time metadata on the request-body component; it is not embedded as JSON request properties and is not a backend capability claim.

OpenAPI sets `maxItems: 32` on `CursorQuery.sort`. A `nextCursor` is only an opaque string: built-in backends generate it with the AES-256-GCM key configured at `wow.query.cursor.encryption-key`. OpenAPI exposes neither the key nor the backend payload or a filter/sort binding. The cursor routes remain discoverable when the key is absent, but calls fail explicitly as unsupported; see [Snapshot Queries](./query/snapshot-query.md#cursor-pagination) and [Infrastructure Configuration](../reference/config/infrastructure.md#cursor-encryption).

`wow-apiclient` contains hand-maintained CoApi interfaces for Wow command and snapshot contracts. External tools such as Fetcher may generate other clients from the published OpenAPI document. Client generation is downstream of OpenAPI: KSP metadata does not generate those clients, and regenerating a client does not change server field semantics. Review generated diffs whenever the OpenAPI contract changes.

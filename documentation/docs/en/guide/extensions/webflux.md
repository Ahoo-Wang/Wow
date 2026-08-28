---
title: WebFlux
description: Materialize Wow runtime route contracts as Spring WebFlux functional endpoints.
---

# WebFlux

`wow-webflux` creates command, state, snapshot, event, and query handlers from `wow-openapi` runtime route contracts. Use it for Wow's declarative HTTP API. Omit it when custom controllers already own the required surface.

The module owns reactive handlers, request extraction, wait policy, and error mapping. Spring WebFlux/Netty owns ports, connections, codecs, resources, and network timeouts. The application owns authentication, authorization, and gateway policy.

## Installation

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:webflux-support") }
}
```

A direct `wow-webflux` dependency provides APIs and implementations only; it does not provide Starter's `WebFluxAutoConfiguration` or property binding.

## Automatic Route Registration

Auto-configuration merges ordered `WebFluxRouteModule` instances and extra `HttpRouteHandlerFunctionFactory` beans, then builds one `RouterFunction` from `RouterSpecs`. Only loaded metadata and registered contributors create routes. Module presence does not prove a business endpoint exists.

### Route Patterns

Aggregate and command metadata determine paths and add tenant, owner, or space scope when required. The candidate runtime `/v3/api-docs` is available only when the application separately installs a Springdoc WebFlux starter compatible with its Spring WebFlux version. With `webflux-support` alone, inspect `RouterSpecs.toRouteCatalog()` or an equivalent route-catalog diagnostic instead of guessing production paths.

#### Aggregate Route Pattern

Metadata such as `AggregateRoute.Owner.AGGREGATE_ID` determines how owner and aggregate ID are represented. The command handler still builds a `CommandMessage` from path, headers, and body.

#### Owner Route Pattern

Owner, tenant, and space routes express resource scope only; they do not authenticate the caller. Security must bind the principal to those values and reject escalation.

### HTTP Method Mapping

Methods and paths generated for `@CreateAggregate` and `@CommandRoute` come from compiler metadata; WebFlux executes the contract. Rebuild and inspect OpenAPI after annotation or metadata changes.

## Configuration

The capability is sufficient for installation; `wow.webflux.enabled=true` by default. Common defaults are:

```yaml
wow:
  webflux:
    global-error:
      enabled: true
    batch:
      concurrency: 1
      prefetch: 1
    query:
      max-list-size: 1000
      max-page-size: 100
      max-page-window: 10000
      max-condition-nodes: 64
      max-condition-values: 1000
      allow-expensive-operators: true
      idle-timeout: 10s
```

`0` disables each numeric HTTP guard; `idle-timeout=0s` disables idle timeout. Do not duplicate backend field-type, mapping, or uniqueness checks. `HttpQueryGuardFilter` protects HTTP queries with WebFlux request context; programmatic query services retain their public behavior.

## Aggregation Query Routes

Both Snapshot and EventStream routes accept `AggregationQuery`:

- `.../snapshot/aggregation` aggregates the snapshot model;
- `.../event/aggregation` aggregates the event-stream model and follows the same base, tenant, and owner route rules as event/list, event/paged, and event/count.

Normal JSON collects dynamic rows into an array; `Accept: text/event-stream` streams rows. Query guards also limit conditions, values, limits, Elements, metric sorting, and expensive expressions.

An EventStream root filter applies to the event-stream document. After `elements = [{"path":"body"}]` expands the event array, group and metric fields are relative to each event item. Elasticsearch currently does not index the `body.body` payload, so the cross-backend aggregation scope is the event-stream envelope and `body` event metadata. Payload aggregation requires a separate mapping and historical reindex design first.

## Wait Plan Integration

Command headers choose a wait stage and timeout. The handler delegates to `CommandGateway`/`WaitCoordinator`. Client disconnect, timeout, or runtime shutdown can end the HTTP wait while later business processing still continues.

### Supported Wait Plans

Supported stages are `SENT`, `PROCESSED`, `SNAPSHOT`, `PROJECTED`, `EVENT_HANDLED`, and `SAGA_HANDLED`. A stage means its notifier condition completed, not that an arbitrary cross-service transaction committed. See [Command Gateway](../command-gateway.md#wait-plans).

## Error Handling

The default `RequestExceptionHandler` converts framework failures to `ErrorInfo` and writes `Wow-Error-Code`; a global `WebExceptionHandler` is enabled by default. Verified mappings include argument/state errors 400, not found 404, wait timeout 408, and unknown errors 500. A custom error strategy must preserve the original failure when response rendering is empty or fails.

## OpenAPI Integration

Runtime metadata and route contracts assemble OpenAPI. Schema refresh updates only the receiving instance's query-schema cache and retains the old cache on failure. It does not broadcast or modify backend mappings. Authorize refresh separately from ordinary query routes.

## Performance Optimization

Observe event-loop blocking, serialization, handler latency, open wait connections, and guard rejections before changing batch or server settings.

### Reactive Processing

Built-in paths return `Mono`/`Flux` and avoid blocking in core handlers. Custom controllers must preserve that boundary. Isolate blocking cache or driver work on an appropriate scheduler and verify it under load.

## Monitoring and Debugging

Record route ID, request ID, error code, HTTP status, latency, and cancellation. Add `opentelemetry-support` for framework spans; WebFlux capability does not configure an exporter.

### Request Logging

Temporarily set `me.ahoo.wow.webflux=DEBUG`, but do not log command secrets, authentication headers, or complete sensitive payloads. Restore the production level after diagnosis.

## Best Practices

- Verify routes from candidate runtime OpenAPI when a matching Springdoc WebFlux starter is installed; otherwise inspect the `RouterSpecs` route catalog.
- Authenticate before WebFlux handlers and authorize query/command routes.
- Keep the reactive path non-blocking and test cancellation and timeout.
- Change HTTP guards from evidence; do not use `0` as a default escape hatch.

Focused check:

```bash
./gradlew :wow-webflux:check
```

This does not prove target gateway, security policy, or deployed routes. Next, read [OpenAPI](../open-api.md) and [Data access](../data-access.md).

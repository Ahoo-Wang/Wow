---
title: Query Backend
description: Learn how QueryService, Spring typed Beans, Factory caching, and storage implementations fit together.
---

# Query Backend

## QueryService contract

`QueryService<R>` is the aggregate query-backend contract. It provides typed and dynamic single, list, paged, count, and aggregation operations. `SnapshotQueryService<S>` returns `MaterializedSnapshot<S>`, while `EventStreamQueryService` returns `DomainEventStream`. Aggregation always returns dynamic-document rows; the default `aggregate` fails when a backend does not support it.

## Injecting a typed SnapshotQueryService Bean

Spring can inject a snapshot query service by its state type:

```kotlin
@Component
class OrderReader(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun find(query: PagedQuery): Mono<PagedList<MaterializedSnapshot<OrderState>>> =
        queryService.paged(query)
}
```

This is an in-process JVM entry point; a managed service can enter the [Query Gateway](query-gateway.md) through its proxy.

## Bean registration and naming

`SnapshotQueryServiceRegistrar` registers `SnapshotQueryService<STATE>` with `ResolvableType`; its Bean name is `{contextAlias.}{aggregateName}.SnapshotQueryService`. This lets Spring select a snapshot service by its state generic.

When a same-name Bean exists or its corresponding Gateway is unavailable, the raw, unproxied query service is retained. Do not describe either case as a normal business extension point.

## How QueryServiceProxy routes

`QueryServiceProxy` preserves the backend service's `name` and `namedAggregate`, then delegates single, list, paged, count, and aggregation to the corresponding Gateway. The proxy does not execute backend queries itself or combine the two query models into one service.

## Factories, caching, and storage routing

`SnapshotQueryServiceFactory` and `EventStreamQueryServiceFactory` create raw services; their abstract base classes cache services by materialized aggregate. A Routing Factory first looks for an aggregate-specific route and otherwise uses its default Factory. MongoDB, Elasticsearch, or another configured implementation ultimately executes the query.

Factory-created results do not pass through the Gateway. Application code should prefer Spring-registered typed services; backend selection and physical-query compilation belong to storage extensions.

## EventStreamQueryService Beans

`EventStreamQueryServiceRegistrar` also registers one Bean per aggregate, using the `.EventStreamQueryService` naming rule. Event-stream services have no `STATE` generic; when multiple candidates exist, qualify by Bean name instead of relying on generic disambiguation.

## Raw backend access

Direct Factory access is for trusted infrastructure extensions or cases that explicitly require raw backend semantics. It bypasses Gateway query rewriting, ABAC, and result masking; a same-name custom Bean and a missing Gateway have the same unproxied boundary.

## Schema Provider differences

The snapshot proxy does not implement a Schema Provider, while the event-stream proxy implements a Provider by delegating to the raw service. An event-stream Provider is a JVM-only capability: it publishes no HTTP route, OpenAPI Schema, or Client capability. WebFlux currently exposes only snapshot Schema routes; [WebFlux](../extensions/webflux.md) is authoritative for runtime routes, [OpenAPI](../open-api.md) for published HTTP/OpenAPI contracts, and [API Client](../extensions/apiclient.md) for client boundaries. Do not infer event-stream Schema HTTP, OpenAPI, or Client exposure from the event-stream proxy having a Provider.

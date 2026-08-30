---
title: V9 Query Migration
description: Migrate the V8 query JVM API to aggregate Gateways and ObjectNode Backends.
---

# V9 Query Migration

## Migration Boundary

V9 removes the old JVM types without a bridge, type alias, or deprecation window. This breaks JVM source and binary users of those types. Recompile downstream code and migrate directly with the tables below.

HTTP paths, request/response JSON structure, generated OpenAPI, wire structure, storage layouts, and existing data do not change because of this JVM refactor. No storage-data migration is required. The current V9 also temporarily removes all Mask capability, so response fields that were previously hidden return raw values; HTTP value and confidentiality semantics are not preserved. A separate follow-up task will deliver the static-annotation replacement.

## JVM Type Mapping

| V8 source | V9 source |
| --- | --- |
| `QueryService<R>` | Removed; responsibilities split between `QueryBackend` and an aggregate-bound `QueryGateway<R>` |
| `QueryGateway<R>` / `AbstractQueryGateway<R>` | Names retained, but the contract becomes aggregate-bound |
| `SnapshotQueryService<S>` | `SnapshotQueryGateway<S>` |
| `EventStreamQueryService` | `EventStreamQueryGateway` |
| `QueryServiceCacheSource` | `QueryGatewayCacheSource` |
| `SnapshotQueryServiceFactory` | `SnapshotQueryBackendFactory` |
| `EventStreamQueryServiceFactory` | `EventStreamQueryBackendFactory` |
| `AbstractSnapshotQueryServiceFactory` | `AbstractSnapshotQueryBackendFactory` |
| `AbstractEventStreamQueryServiceFactory` | `AbstractEventStreamQueryBackendFactory` |
| `RoutingSnapshotQueryServiceFactory` | `RoutingSnapshotQueryBackendFactory` |
| `RoutingEventStreamQueryServiceFactory` | `RoutingEventStreamQueryBackendFactory` |
| `AbstractMongoQueryService` | `AbstractMongoQueryBackend` |
| `MongoSnapshotQueryService` | `MongoSnapshotQueryBackend` |
| `MongoEventStreamQueryService` | `MongoEventStreamQueryBackend` |
| `MongoSnapshotQueryServiceFactory` | `MongoSnapshotQueryBackendFactory` |
| `MongoEventStreamQueryServiceFactory` | `MongoEventStreamQueryBackendFactory` |
| `AbstractElasticsearchQueryService` | `AbstractElasticsearchQueryBackend` |
| `ElasticsearchSnapshotQueryService` | `ElasticsearchSnapshotQueryBackend` |
| `ElasticsearchEventStreamQueryService` | `ElasticsearchEventStreamQueryBackend` |
| `ElasticsearchSnapshotQueryServiceFactory` | `ElasticsearchSnapshotQueryBackendFactory` |
| `ElasticsearchEventStreamQueryServiceFactory` | `ElasticsearchEventStreamQueryBackendFactory` |
| `SnapshotQueryServiceFactoryBinding` | `SnapshotQueryBackendFactoryBinding` |
| `EventStreamQueryServiceFactoryBinding` | `EventStreamQueryBackendFactoryBinding` |
| `NoOpSnapshotQueryService<S>` | `NoOpSnapshotQueryBackend` |
| `NoOpEventStreamQueryService` | `NoOpEventStreamQueryBackend` |
| `NoOpSnapshotQueryServiceFactory` | `NoOpSnapshotQueryBackendFactory` |
| `NoOpEventStreamQueryServiceFactory` | `NoOpEventStreamQueryBackendFactory` |
| `QueryServiceRegistrar` | `QueryGatewayRegistrar` |
| `SnapshotQueryServiceRegistrar` | `SnapshotQueryGatewayRegistrar` |
| `EventStreamQueryServiceRegistrar` | `EventStreamQueryGatewayRegistrar` |
| `QueryServiceProxy` / `SnapshotQueryServiceProxy` / `EventStreamQueryServiceProxy` | Removed; inject the aggregate-bound Gateway directly |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | Removed; no replacement in the current version |
| `AggregateDynamicDocumentMasker` | Removed; no replacement in the current version |
| `StateDynamicDocumentMasker` | Removed; no replacement in the current version |
| `EventStreamDynamicDocumentMasker` | Removed; no replacement in the current version |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | Removed; no replacement in the current version |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` | Removed; no replacement in the current version |
| `StateDataMaskerRegistry` / `EventStreamMaskerRegistry` | Removed; no replacement in the current version |
| `DataMasker` / `DataMasking` / `tryMask` | Removed; no replacement in the current version |
| `MaskingDynamicDocumentQueryFilter` | Removed; no replacement in the current version |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |
| `QueryType.isDynamic` | Removed; typed and node paths share operation types |

Typed and node results share the `SINGLE`, `LIST`, and `PAGED` operation types. A Backend always returns `ObjectNode`; the Gateway optionally uses Jackson to materialize typed results after generic result filters complete.

There is no one-to-one replacement for `QueryService<R>`: move storage queries and Schema capability to an `ObjectNode`-returning `QueryBackend`, while the managed entry, filter chain, and typed materialization remain in the aggregate `QueryGateway<R>`. The old `QueryGateway` accepted a `NamedAggregate` on every call; V9 binds the `NamedAggregate` and routed Backend when constructing the Gateway, so `single`, `list`, `paged`, `count`, and `aggregate` calls no longer pass an aggregate argument. A custom `AbstractQueryGateway` subclass must supply the new `namedAggregate`, `backend`, `targetType`, `filters`, `filterType`, and `errorHandler` constructor contract; use the default Snapshot/EventStream Gateway when no custom entry policy is required.

Filters no longer use `QueryType.isDynamic` to distinguish a final typed result from a node result. Both paths traverse the same ObjectNode FilterChain and differ only by optional Jackson materialization after the chain. Remove branches used only for typed/dynamic dispatch; do not invent a replacement result-type discriminator.

Delete old Mask types, implementations, Beans, registries, and custom filters. The current version creates no ObjectNode Mask compatibility layer and provides no built-in replacement. Snapshot, EventStream, and direct aggregate-state loads do not mask values automatically. Until the static-annotation replacement ships, callers must treat raw field values as an accepted temporary downgrade and protect sensitive endpoints with access control or external isolation.

## Spring Bean Mapping

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |

The exact new Bean names are `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the prefix when there is no context alias. No alias is registered after the old Beans are removed.

## Binding Configuration Values Stay Unchanged

The JVM Factory types are renamed to Backend Factories, but public binding strings intentionally retain the `*-query-service-factory` suffix, for example `mongo-snapshot-query-service-factory` and `elasticsearch-event-stream-query-service-factory`. Do not change an existing routing configuration value merely because the JVM type was renamed.

## Call Entries

Application code injects an aggregate Gateway so request filters, ABAC, generic result handling, and error observation run in one around chain. Only trusted low-level diagnostics, Backend contract tests, and storage extensions call a Backend Factory directly; that path bypasses Gateway governance.

Schema handlers also use the routed Backend Factory, so Schema and queries select the same Backend for a `NamedAggregate`. A generic `QueryFilter` has no `@FilterType`; only a model-specific filter targets its Gateway type.

## ObjectNode ownership

Every subscription to a publisher returned by a custom Backend must create mutable `ObjectNode` values owned exclusively by that subscription. Subscriptions created by `retry`, `repeat`, and concurrent callers must also receive fresh nodes. Do not cache or share nodes across subscriptions, publish cached nodes, or continue mutating a node asynchronously after emission.

Only standard JSON trees may cross the Backend boundary. Storage-driver `Map`/`Document` values, BSON values, `POJONode`, and arbitrary POJOs must be normalized or rejected inside the Backend.

## Transport and Error Semantics

JSON-array and SSE streaming behavior is unchanged. If a stream fails after emitting some elements, those elements are not rolled back. SSE attempts to emit an `ErrorInfo` error event. A `RequestExceptionHandler` failure or a failure while generating, rendering, or serializing that error event is attached to the original as a suppressed error only when distinct and not already recorded. The original terminal error is always propagated; migration must not rewrite that partial failure as an empty result or successful completion.

## Minimal Migration Steps

1. Replace imports, constructor parameters, Bean qualifiers, and Factory implementations according to the tables.
2. Make every custom Backend subscription return fresh, exclusively owned `ObjectNode` values containing only standard JSON-tree data, leaving typed conversion to the Gateway.
3. Remove every old Mask implementation, Bean, and registry; confirm that queries and aggregate-state loads in this temporary version return raw field values, and tighten access to sensitive endpoints.
4. Recompile, start the Spring context, and separately verify JVM, HTTP/OpenAPI, Schema, actual storage routing, and the temporary Mask downgrade boundary.

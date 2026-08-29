---
title: V9 Query Migration
description: Migrate the V8 query JVM API to aggregate Gateways and ObjectNode Backends.
---

# V9 Query Migration

## Migration Boundary

V9 removes the old JVM types without a bridge, type alias, or deprecation window. This breaks JVM source and binary users of those types. Recompile downstream code and migrate directly with the tables below.

HTTP paths and request/response JSON, generated OpenAPI, wire formats, storage layouts, and existing data do not change because of this JVM refactor. No storage-data migration is required.

## JVM Type Mapping

| V8 source | V9 source |
| --- | --- |
| `SnapshotQueryService<S>` | `SnapshotQueryGateway<S>` |
| `EventStreamQueryService` | `EventStreamQueryGateway` |
| `SnapshotQueryServiceFactory` | `SnapshotQueryBackendFactory` |
| `EventStreamQueryServiceFactory` | `EventStreamQueryBackendFactory` |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | `ObjectNodeMasker` |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |

Typed and node results share the `SINGLE`, `LIST`, and `PAGED` operation types. A Backend always returns `ObjectNode`; the Gateway optionally uses Jackson to materialize typed results after result masking.

## Spring Bean Mapping

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |

The exact new Bean names are `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the prefix when there is no context alias. No alias is registered after the old Beans are removed.

## Binding Configuration Values Stay Unchanged

The JVM Factory types are renamed to Backend Factories, but public binding strings intentionally retain the `*-query-service-factory` suffix, for example `mongo-snapshot-query-service-factory` and `elasticsearch-event-stream-query-service-factory`. Do not change an existing routing configuration value merely because the JVM type was renamed.

## Call Entries

Application code injects an aggregate Gateway so request filters, ABAC, Backend node-result masking, and error observation run in one around chain. Only trusted low-level diagnostics, Backend contract tests, and storage extensions call a Backend Factory directly; that path bypasses Gateway governance.

Schema handlers also use the routed Backend Factory, so Schema and queries select the same Backend for a `NamedAggregate`. A generic `QueryFilter` has no `@FilterType`; only a model-specific filter targets its Gateway type.

## Transport and Error Semantics

JSON-array and SSE streaming behavior is unchanged. If a stream fails after emitting some elements, those elements are not rolled back. SSE attempts to emit an `ErrorInfo` error event. Whether or not that event can be produced, the original terminal error is always propagated, with an error-handler failure attached as a suppressed error. Migration must not rewrite that partial failure as an empty result or successful completion.

## Minimal Migration Steps

1. Replace imports, constructor parameters, Bean qualifiers, and Factory implementations according to the tables.
2. Make custom Backends return `ObjectNode`, leaving typed conversion to the Gateway.
3. Move result masking to `ObjectNodeMasker` and verify generic versus model-specific filter selection.
4. Recompile, start the Spring context, and separately verify JVM, HTTP/OpenAPI, Schema, and actual storage routing.

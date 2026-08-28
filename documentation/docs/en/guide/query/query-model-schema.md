---
title: Query Model Schema
description: Understand runtime query-field sources, backend capabilities, validation modes, and the Snapshot and EventStream Schema HTTP contracts.
---

# Query Model Schema

## What the Schema Solves

Query Model Schema is the runtime query-capability contract for `QueryModel.SNAPSHOT` and `QueryModel.EVENT_STREAM`. It resolves logical request fields to backend physical paths and records value types, cardinality, temporal semantics, dynamic children, projection paths, and the capabilities available for each operation. `QuerySchemaResolver` uses it to rewrite and validate filters, projections, sorting, and [aggregation queries](./aggregation-query.md), rather than assuming that a property is queryable merely because it exists in a DTO.

It differs from [general JSON Schema](../advanced/schema.md): JSON Schema describes serialization shape and can contribute to OpenAPI generation, while Query Model Schema must also be resolved by the selected MongoDB or Elasticsearch adapter against actual storage facts before an operation is proven available.

## Source Priority and Merging

The runtime source chain is below. A larger number means a higher priority:

```text
System
 + JsonQuerySchemaSource (100)
 + ClasspathQuerySchemaSource (200)
 + BeanQuerySchemaSource (300)
 + WorkingDirectoryQuerySchemaSource (400)
 -> QuerySchemaMerger
 -> MongoDB / Elasticsearch Adapter
 -> QueryModelSchema
 -> QuerySchemaResolver
```

- `System` supplies model-specific fields for Snapshot and EventStream. Extensions must remain under the Snapshot `state` root or the EventStream `body.body` root; a field leaf already set by System cannot be overwritten.
- `JsonQuerySchemaSource (100)` infers Snapshot fields from the aggregate state's JSON shape and EventStream `body.body.*` fields from domain-event payloads.
- `ClasspathQuerySchemaSource (200)` reads `wow-query-schema/{context}/{aggregate}/{model}.json`; `WorkingDirectoryQuerySchemaSource (400)` reads the same relative path under `config/`. The model filename is lowercase, for example `snapshot.json` or `event_stream.json`.
- `BeanQuerySchemaSource (300)` merges `QuerySchemaRegistration` entries for the current context.

`QuerySchemaMerger` processes priorities from low to high. A later, higher-priority source overrides only leaves that it explicitly sets; unset leaves keep their lower-priority values. Different values for the same leaf at the same priority raise a Schema conflict instead of depending on load order. Refresh reloads sources and backend facts for the current process and replaces its cache; it does not change indexes, mappings, validators, or historical data.

## Backend Adaptation

The [MongoDB](../extensions/mongo.md) adapter maps logical fields through a `FieldConverter` and reads collection indexes plus an optional `$jsonSchema` validator to prove storage types. An Element-scope candidate first comes from a logical declaration with `MANY` + `OBJECT`. When the validator supplies physical type constraints for that field, the adapter uses array/object types to confirm or reject the candidate. Without a validator or a field type constraint, it retains the logical candidate without physical-type proof. The adapter publishes model-level full-text capabilities only when a suitable text index exists.

The [Elasticsearch](../extensions/elasticsearch.md) adapter reads the target mapping and separately accounts for field types, multi-fields, nested mappings, doc values, aliases, and runtime fields. Full text may bind to a text path, while exact matching, sorting, or TERMS aggregation may bind to a keyword multi-field. An object array receives Element scope only when the corresponding nested mapping supports it.

The adapters share public capability names but do not produce identical physical paths, full-text behavior, array scopes, or temporal capabilities. A custom filter converter makes the built-in Query Model Schema unavailable. The capability contract exists again only if the caller also supplies a Provider/adapter implementation consistent with that converter.

## Field Capabilities

There are eleven built-in capabilities:

| Capability | Purpose |
|---|---|
| `PRESENCE` | Test existence, absence, null, or empty values, and provide the default physical projection path |
| `EXACT_MATCH` | Exact-value operations such as `EQ`, `NE`, `IN`, `NOT_IN`, and collection contains-all |
| `LITERAL_MATCH` | Literal string operations such as `CONTAINS`, `STARTS_WITH`, and `ENDS_WITH` |
| `RANGE` | Comparisons, `BETWEEN`, and relative-time ranges |
| `FULL_TEXT_TERMS` | Full-text terms search |
| `FULL_TEXT_PHRASE` | Full-text phrase search |
| `SORT` | Field sorting |
| `ELEMENT_SCOPE` | Establish an independent array/nested-object scope for `elementMatch` and aggregation Elements |
| `AGGREGATE_TERMS` | TERMS grouping and `ANY` display values |
| `AGGREGATE_NUMERIC` | Numeric histograms, numeric metrics, and numeric expressions |
| `AGGREGATE_TEMPORAL` | Date histograms and temporal buckets |

Fields also carry `valueTypes`, `cardinality`, `semanticType`, and `dynamicChildren`. Even when a capability exists, a value-type, collection-cardinality, or current Element-scope mismatch can still resolve as `INCOMPATIBLE`.

## COMPATIBLE and STRICT

Every resolution has one compatibility level:

- `EXACT`: the field and required capability have a proven physical binding;
- `COMPATIBLE`: no exact binding was found, but compatible mode may preserve the original path, for example for an undeclared field or an accepted dynamic child;
- `INCOMPATIBLE`: the field is known but lacks the required capability, or its value type, cardinality, or Element scope violates the contract.

`QuerySchemaValidationMode.COMPATIBLE` accepts both `EXACT` and `COMPATIBLE` and rejects `INCOMPATIBLE`. `QuerySchemaValidationMode.STRICT` accepts only `EXACT`. The mode controls whether a resolution is accepted; it never creates an index or mapping for the backend.

## System Tags and Fallback

When a Schema source or backend fact is unavailable, only `COMPATIBLE` mode can fall back to the unchanged path for a filter that does not reference system `tags`. A filter that resolves to root system `tags` or `tags.*`, directly or through logical composition, search, relative time, or an Element predicate, still propagates `QuerySchemaUnavailableException` and remains fail-closed; a business field named `tags` inside an element is not the root system tag field. `STRICT` never uses this fallback.

Fallback therefore does not mean "all fields are queryable when Schema is disabled." It preserves the original request without proving capability and does not relax system-tag queries. A field that resolves as `INCOMPATIBLE`, a source conflict, or an ordinary validation failure does not trigger the unavailable fallback either. See [Data Access Control](../data-access.md) for the authorization semantics of system tags.

## HTTP and the OpenAPI Extension

Snapshot and EventStream both publish unscoped Schema and refresh HTTP routes:

| Model | Read the current Schema | Refresh the current-process cache |
|---|---|---|
| Snapshot | `GET /{aggregate}/snapshot/schema` | `POST /{aggregate}/snapshot/schema/refresh` |
| EventStream | `GET /{aggregate}/event/schema` | `POST /{aggregate}/event/schema/refresh` |

These four model-level routes have no tenant, owner, or aggregate-ID variants. Their response is public `QueryModelSchemaMetadata`, including model capabilities and field capabilities. Use the generated [OpenAPI](../open-api.md) as the source of truth for concrete paths and operation IDs.

`x-wow-query-fields` is a static OpenAPI extension on aggregate-specific Snapshot query request-body components. It combines Snapshot system fields with fields inferred by `JsonQuerySchemaSource` so generators can discover candidate logical fields. It is not a JSON request property, contains no backend physical binding, and does not prove runtime capability. EventStream requests have no corresponding extension, and there is currently no EventStream API Client or client-side field discovery. The Snapshot API Client likewise does not read runtime Schema in place of server validation. See [API Client](../extensions/apiclient.md) for the client boundary.

## Provider Differences

Spring `SnapshotQueryServiceProxy` does not implement `QueryModelSchemaProvider`. `EventStreamQueryServiceProxy` does implement the Provider and delegates `schema()` and `refresh()` to the raw EventStream service. This difference describes Provider access through the proxy Bean only.

HTTP exposure does not depend on that difference. `SnapshotSchemaHandlerFunction` obtains its Provider from a raw service created by `SnapshotQueryServiceFactory`; the EventStream handler does the same through `EventStreamQueryServiceFactory`. Both handlers can therefore publish their Schema and refresh routes. Whether a proxy implements Provider cannot be used to infer HTTP/OpenAPI exposure. See [Query Backend](./query-backend.md) for the Factory and proxy responsibilities.

## Troubleshooting an Unqueryable Field

1. Call `GET .../schema` for the correct model and verify that the field exists with the capability required by the operation. Snapshot `state.*` and EventStream `body.body.*` are not interchangeable.
2. Inspect the `100/200/300/400` source chain. Verify the extension root, same-priority conflicts, and unexpected higher-priority leaf overrides.
3. Inspect actual backend facts: MongoDB indexes and validators, or Elasticsearch mappings, multi-fields, nested mappings, doc values, and runtime fields. Do not extrapolate from the other backend.
4. Distinguish `INCOMPATIBLE`, Schema conflict, Schema unavailable, and request-DTO errors, and verify whether the current mode is `COMPATIBLE` or `STRICT`.
5. After changing declarations or mappings, refresh the current-process view. Refresh cannot repair a mapping or historical document that still violates the required capability.
6. With a custom converter, verify that the raw Factory-created service still supplies a `QueryModelSchemaProvider` consistent with the converter.

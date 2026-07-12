# BI Script Request Safety Design

## Goal

Close the remaining BI script POST review findings without weakening module
boundaries: bound request-controlled SQL fragments at the domain boundary and
make the generated OpenAPI contract declare the runtime `415 Unsupported Media
Type` response.

## Scope

This change covers `BiScriptOptions` and `ClickHouseTopology.Cluster` string
invariants, their unit and real HTTP regression tests, the common OpenAPI error
response catalog, the BI route contributor, generated OpenAPI snapshots, and
the affected English and Chinese documentation.

It does not add an absolute `maxExpansionDepth` limit. The server-configured
value remains the trusted HTTP safety ceiling. It also does not move script
generation to another scheduler: synchronous generation predates the POST
contract, and bounding request-controlled amplification closes the new input
risk without expanding this correction into a runtime execution redesign.

## Architecture

String safety is a domain invariant, not a transport concern. `wow-bi` remains
the single source of truth for values used by the SQL renderer:

```text
Spring configuration ----\
                         +--> BiScriptOptions / ClickHouseTopology --> renderer
POST request -> mapper --/
```

The transport DTO stays nullable and transport-only. `wow-webflux` continues to
merge request values over server options; the resulting domain constructors
reject unsafe values regardless of whether they came from HTTP, Spring
configuration, or direct library use.

OpenAPI error semantics remain centralized in `wow-openapi`. A reusable common
`UnsupportedMediaType` response component carries the standard JSON error body
and `Wow-Error-Code` header. The BI contributor references that component next
to its existing `200` and `400` responses.

## Domain String Limits

Limits use Kotlin `String.length`. This directly bounds JVM allocation and is
deterministic for all callers; SQL escaping may expand a value by a small
constant factor but cannot make it unbounded.

| Field | Maximum characters | Rationale |
|---|---:|---|
| `database` | 128 | Conservative ClickHouse identifier budget |
| `consumerDatabase` | 128 | Same identifier contract as `database` |
| `timezone` | 64 | Sufficient for IANA-style timezone identifiers |
| `topicPrefix` | 128 | Leaves space for generated aggregate/topic suffixes |
| `kafkaBootstrapServers` | 4096 | Supports large broker lists without request-scale amplification |
| Cluster `name` | 128 | Bounds repeated cluster SQL literals |
| Cluster `installation` | 128 | Bounds replicated-table paths |
| Cluster `shard` | 128 | Bounds replicated-table paths |
| Cluster `replica` | 128 | Bounds replicated-engine literals |

Existing non-blank and control-character checks remain. Each failure message
names the field, actual length, and maximum so Spring startup failures and HTTP
400 responses are diagnosable. Values exactly at the maximum are accepted;
values one character above it are rejected.

The limits are constants owned by the domain types that enforce them. The
transport layer does not copy the numbers and the OpenAPI DTO does not acquire
validation annotations tied to a second validation mechanism.

## HTTP and OpenAPI Behavior

Runtime behavior remains:

| Request | Result |
|---|---|
| Valid `application/json` body | `200 application/sql` |
| Domain value over its maximum | `400` using the existing global error path |
| Unsupported request media type | `415` using the existing global error path |

The OpenAPI operation for `POST /wow/bi/script` declares:

- `200 application/sql`;
- the common `400 BadRequest` response;
- the common `415 UnsupportedMediaType` response.

`UnsupportedMediaType` uses the same common error schema and
`Wow-Error-Code` header shape as other framework errors. The component key and
runtime error code are both `wow.UnsupportedMediaType`.

## Testing Strategy

Implementation follows strict RED, GREEN, and REFACTOR cycles.

### Domain Tests

- Every bounded `BiScriptOptions` field accepts its exact maximum.
- Every bounded `BiScriptOptions` field rejects maximum plus one.
- Every Cluster field accepts its exact maximum.
- Every Cluster field rejects maximum plus one.
- Existing blank and control-character behavior remains green.

### Real HTTP Tests

- An over-limit POST field returns `400` through the registered RouterFunction
  and global error handler.
- A boundary-length value is accepted and appears safely in generated SQL.
- The existing unsupported media type test remains `415`.

### OpenAPI Tests

- The contributor declares response codes `200`, `400`, and `415`.
- The common `UnsupportedMediaType` component includes the error header and
  JSON error schema.
- The final materialized OpenAPI snapshot contains the `415` component
  reference for `/wow/bi/script`.
- The route-contract snapshot records all three response codes.

## Documentation and Compatibility

English and Chinese BI/OpenAPI documentation publish the field limits and the
complete `200`/`400`/`415` response contract.

This is intentionally breaking for configurations or direct API calls that
currently exceed the new limits. No compatibility adapter or migration layer is
added. Rollback consists of reverting the domain limits and the matching
contract/documentation changes together.

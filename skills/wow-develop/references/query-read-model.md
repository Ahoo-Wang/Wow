# Query and Read-Model Decisions

Use this reference for `FilterExpression`, Query DSL, snapshot or event-stream aggregation, runtime query schemas, projection, pagination, sorting, query rewriting, and read-model access.

## Stable decisions

- Keep query construction separate from backend conversion and execution.
- Use `FilterExpression` and `FilterDsl` as the canonical JVM contract. V9.x retains deprecated `Condition`/`Operator` types, `ConditionDsl`, legacy query constructors, count client overloads, and REST `condition`/`operator` input only as adapters to `FilterExpression`; they are scheduled for removal in 10.0.0. Do not add new Condition-based APIs.
- On targets that provide them, use `isEmptyString()` for exact `""` and `isNotEmptyString()` for fields that are present, non-null, and non-empty strings. Require an exact-match, single-valued String field; whitespace, null, missing fields, and empty collections have separate semantics. HTTP may reject `IS_NOT_EMPTY_STRING` when expensive operators are disabled even though an in-process query is valid.
- Apply tenant, owner, deletion, and authorization filters at the boundary that cannot be bypassed by callers.
- Treat pagination ordering as a correctness contract; define a deterministic tie-breaker when records can share the primary sort value.
- Use `PagedQuery` for totals and page jumps; use `CursorQuery` for forward traversal without totals: the first page has no token, later requests preserve filter/sort, and `nextCursor = null` ends traversal.
- A cursor token is an opaque Backend position: rerun scope, authorization, filters, and masking on every page; never decode, rewrite, or cross Backend boundaries with it.
- The effective cursor sort must be Schema `EXACT`, `SINGLE`, unmasked, and stably unique (including a tie-breaker); fail invalid sort or token rather than restarting or falling back to offsets.
- Project only fields supported by downstream mapping and serialization.
- Verify count and page semantics together when presenting totals.
- Preserve backend-specific null, collection, date/time, and nested-field semantics through focused converter tests.
- Resolve logical fields through the runtime `QueryModelSchema`: default `COMPATIBLE` accepts `EXACT` and `COMPATIBLE`, while `STRICT` accepts only `EXACT`; declaration conflicts fail before validation mode applies. OpenAPI `x-wow-query-fields` is a static catalog, not backend capability proof. Schema refresh updates only the receiving instance's cache and never changes backend mappings or data. `QueryModelSchemaProvider` only loads and refreshes Schema; direct callers explicitly resolve, admit, and wrap a query in `ResolvedQuery`, with no unavailable fallback.
- For snapshot aggregation, the first Element path is absolute and later Element paths are relative to the current element. Group and metric fields are relative to the innermost element. Keep aliases unique and use the query's effective sort rather than inventing backend-specific ordering.
- Event-stream aggregation uses the same `AggregationQuery`, but its document root and schema are `QueryModel.EVENT_STREAM`: expand `body` for events, then address event fields relatively and payload fields under `body.body`. Verify the generated EventStream aggregation and Schema HTTP/OpenAPI routes from the target application.
- Wow validates aggregation structure, not field existence, collection shape, or physical type. MongoDB compiles field paths directly; Elasticsearch resolves executable mappings. Do not add a field catalog or duplicate backend compilers in downstream code.
- A generated aggregation OpenAPI route does not prove that the routed `QueryBackend` can execute the target fields against the selected storage. Prove the Backend, Schema binding, and aggregation contract together.
- Aggregation reuses root route and ABAC filtering, while masking intentionally ignores aggregation results. HTTP cost guards apply only when the query carries a WebFlux `ServerRequest`; read exact defaults from the target version.
- Treat the aggregate-bound `SnapshotQueryGateway` or `EventStreamQueryGateway` as the application execution entry. One around chain encloses the bound `ObjectNode` Backend; every result query reads current Schema, reuses a Masker for the same Schema instance, and recompiles after refresh publishes a new instance before optional typed Jackson materialization. If that Schema is unavailable, managed `single`/`list`/`paged`/`cursor`/`count`/`aggregate` all fail closed without subscribing to the Backend; `count` skips result masking but still reads Schema for admission. WebFlux applies request-scope rewriting before invoking the Gateway. Factories return and cache `QueryBackendBinding` by materialized aggregate; use `factory.create(namedAggregate).backend` only for trusted low-level diagnostics, Backend contract tests, or storage extensions because that raw path bypasses Gateway governance.
- Routing happens once when the Gateway is assembled for a `NamedAggregate`: the Registrar passes one routed `QueryBackendBinding` intact to the Gateway, while Schema lifecycle handlers unwrap that binding's `schemaProvider`. A custom Factory explicitly constructs both objects; a custom Backend never implements a Provider. A generic `QueryFilter` has no `@FilterType`; only model-specific filters target the corresponding Gateway.

## Discover the actual DSL

```bash
rg -n "FilterExpression|filterExpression|singleQuery|listQuery|pagedQuery|aggregation|pagination|projection|sort" . -g '*.kt' -g '*.java'
# Run this from a separate checkout of the pinned Wow source:
rg -n "FilterDsl|AggregationQuery|QueryGateway|QueryBackend|SnapshotQueryGateway|EventStreamQueryGateway|QueryBackendFactory|QueryModelSchema|QuerySchemaSource|QueryFilter|HttpQueryGuardFilter|QueryComponent" wow-api wow-query wow-spring wow-webflux wow-openapi -g '*.kt'
```

Inspect the downstream usage plus DSL builders, filter types, snapshot/event query extensions, backend converters, service interfaces, generated OpenAPI, and tests from a separate pinned Wow source checkout or resolved dependency sources. Use the generated OpenAPI path and HTTP method as the transport source of truth; default local aggregate routes do not prepend the context alias. Never invent an operator or copy a complete method list into a Skill.

## Verification boundary

- Unit-test filter composition and scopes, enforced filters, projection, pagination, aggregation effective sort, and Element path construction.
- Test backend conversion when semantics differ by MongoDB, Elasticsearch, or another store.
- For a custom `SnapshotQueryBackend` or `EventStreamQueryBackend`, prove the selected routed Factory path and run the target aggregation contract or equivalent integration coverage; compilation and route publication are insufficient.
- For query schema changes, prove declaration merge, validation mode, backend bindings, runtime metadata, and old/new request behavior for every selected backend. Test refresh per target instance when freshness is part of the claim.
- For custom query entry or filtering, verify the target version's `QueryGateway` API, model-specific `@FilterType` targets, exact aggregate Bean names, and whether the caller uses a managed Gateway or a raw Backend Factory.
- Use integration data for index usage, performance claims, collation, null handling, or backend-specific consistency.
- For production-performance conclusions, require reproducible queries and execution/profile evidence; green unit tests are insufficient.

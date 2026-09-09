# Query and Read-Model Decisions

Use this reference for `FilterExpression`, Query DSL, snapshot or event-stream aggregation, runtime query schemas, projection, pagination, sorting, query rewriting, and read-model access.

## Target contract

Pin the downstream dependency and the matching Wow tag or explicitly supplied commit. A version in a build file is not proof that its artifact has been published. The fixed pipeline below applies to targets with `QueryFilter.prepare`, `QueryPolicy.evaluate`, and explicit Backend `(query, schema)` arguments. Earlier targets may have around filters or schema validation modes; verify those from their own source instead of applying one implementation contract to every V9 release.

## Stable decisions

- Keep query construction separate from backend conversion and execution.
- Use `FilterExpression` and `FilterDsl` as the canonical JVM contract. V9.x retains deprecated `Condition`/`Operator` types, `ConditionDsl`, legacy query constructors, count client overloads, and REST `condition`/`operator` input only as adapters to `FilterExpression`; they are scheduled for removal in 10.0.0. Do not add new Condition-based APIs.
- On targets that provide them, use `isEmptyString()` for exact `""` and `isNotEmptyString()` for fields that are present, non-null, and non-empty strings. Require an exact-match, single-valued String field; whitespace, null, missing fields, and empty collections have separate semantics. HTTP may reject `IS_NOT_EMPTY_STRING` when expensive operators are disabled even though an in-process query is valid.
- Put mandatory tenant, owner, lifecycle, or business constraints in `QueryPolicy`; `QueryFilter.prepare` prepares a replaceable request. Keep built-in model defaults in the Gateway.
- Treat pagination ordering as a correctness contract; define a deterministic tie-breaker when records can share the primary sort value.
- Use `PagedQuery` for totals and page jumps; use `CursorQuery` for forward traversal without totals: the first page has no token, later requests preserve filter/sort, and `nextCursor = null` ends traversal.
- A cursor token is an opaque Backend position: rerun scope, authorization, filters, and masking on every page; never decode, rewrite, or cross Backend boundaries with it.
- The effective cursor sort requires proven `CURSOR_SORT` capability, single-valued ordered data, no Mask or protected alias, and a stable unique tie-breaker. Ordinary `SORT` capability alone is insufficient; reject invalid sort or tokens instead of restarting or falling back to offsets.
- Project only fields supported by downstream mapping and serialization.
- Verify count and page semantics together when presenting totals.
- Preserve backend-specific null, collection, date/time, and nested-field semantics through focused converter tests.
- `QueryModelSchema` holds immutable logical value structure, native bindings/capabilities, and protection facts. It does not admit requests, select policies, compile queries, or execute storage. The Gateway invokes public validation against these facts; the Backend owns native compilation and execution. `QueryModelSchemaProvider` loads and refreshes Schema, not queries. OpenAPI `x-wow-query-fields` is a static catalog, not backend capability proof. Refresh updates the receiving instance's cache and never changes mappings or data.
- For snapshot aggregation, the first Element path is absolute and later Element paths are relative to the current element. Group and metric fields are relative to the innermost element. Keep aliases unique and use the query's effective sort rather than inventing backend-specific ordering.
- Event-stream aggregation uses the same `AggregationQuery`, but its document root and schema are `QueryModel.EVENT_STREAM`: expand `body` for events, then address event fields relatively and payload fields under `body.body`. Verify the generated EventStream aggregation and Schema HTTP/OpenAPI routes from the target application.
- Gateway validation checks declared logical fields, value domains, element scope, capabilities, and protected aggregation inputs. Both Backends use their Schema bindings and enforce native constraints before execution. Do not duplicate a field catalog or backend compiler in downstream code.
- A generated aggregation OpenAPI route does not prove that the routed `QueryBackend` can execute the target fields against the selected storage. Prove the Backend, Schema binding, and aggregation contract together.
- Aggregation reuses request scope and policies. Its results are not masked; public validation rejects protected grouping, field metrics, and expressions before execution. HTTP Handlers apply `HttpQueryGuard` independently of the Gateway; a raw request in Reactor Context is not the switch that enables cost protection.
- Application queries use the aggregate-bound `SnapshotQueryGateway` or `EventStreamQueryGateway`. Each subscription captures one Schema and identity, runs prepare, appends trusted scope and policy filters with AND, applies model defaults, validates, then invokes Backend `(query, schema)`. Node results are masked before optional typed materialization; `QueryObserver` observes termination only. Schema or policy failure stops Backend invocation. Retry/repeat starts a fresh subscription. Count uses the same admission stages without result masking.
- Both default Gateways and Spring registrars consume `QueryPolicy`. Each policy reads the same prepared/scoped `QueryContext` and captured identity; it emits an additional filter or error, never a replacement query or result. It determines applicability from that context and emits `MatchAllFilter` when not applicable; `Mono.empty()` is a protocol error. `AbacQueryPolicy` reads tags only for Snapshot. A generic policy need not use access-control concepts or principal tags.
- WebFlux extracts HTTP scope into Reactor Context; the Gateway appends it after prepare. JVM callers supply trusted context explicitly. Factory raw access bypasses Gateway scope, policies, defaults, public validation, Mask and observation: a direct caller supplies the Schema, admission, and required predicates itself. Use it only for an explicitly intended infrastructure path.
- Routing happens once when the Gateway is assembled for a `NamedAggregate`: the Registrar passes one routed `QueryBackendBinding` intact to the Gateway, while Schema lifecycle handlers unwrap that binding's `schemaProvider`. A custom Factory explicitly constructs both objects; a custom Backend never implements a Provider. A generic `QueryFilter` has no `@FilterType`; only model-specific filters target the corresponding Gateway.

## Discover the actual DSL

```bash
rg -n "FilterExpression|filterExpression|singleQuery|listQuery|pagedQuery|aggregation|pagination|projection|sort" . -g '*.kt' -g '*.java'
# Run this from a separate checkout of the pinned Wow source:
rg -n "FilterDsl|AggregationQuery|QueryGateway|QueryBackend|QueryPolicy|QueryModelSchema|QuerySchemaSource|QueryFilter|QueryObserver|HttpQueryGuard|QueryRequestScope" wow-api wow-query wow-spring wow-webflux wow-openapi -g '*.kt'
```

Inspect the downstream usage plus DSL builders, filter types, snapshot/event query extensions, backend converters, service interfaces, generated OpenAPI, and tests from a separate pinned Wow source checkout or resolved dependency sources. Use the generated OpenAPI path and HTTP method as the transport source of truth; default local aggregate routes do not prepend the context alias. Never invent an operator or copy a complete method list into a Skill.

## Verification boundary

- Unit-test filter composition and scopes, enforced filters, projection, pagination, aggregation effective sort, and Element path construction.
- Test backend conversion when semantics differ by MongoDB, Elasticsearch, or another store.
- For a custom `SnapshotQueryBackend` or `EventStreamQueryBackend`, prove the selected routed Factory path and run the target aggregation contract or equivalent integration coverage; compilation and route publication are insufficient.
- For query schema changes, prove declaration merge, strict public admission, native bindings, recursive runtime metadata, and supported old/new requests for each selected Backend. Current targets reject the removed `wow.query.schema.validation-mode` setting, including `strict`; inspect older target behavior separately. Test refresh per target instance when freshness is part of the claim.
- For custom query entry or filtering, verify the target version's `QueryGateway` API, model-specific `@FilterType` targets, exact aggregate Bean names, and whether the caller uses a managed Gateway or a raw Backend Factory.
- For mandatory policies, test both models with prepare replacing the input filter, fresh identities on repeated subscriptions, AND composition, and empty/error results stopping Backend invocation. To claim HTTP wiring, register a Policy Bean, call the actual Handler, and inspect the final Backend constraint and rejection response. Gateway-only tests do not prove Spring/HTTP wiring; an in-process client with manual route contracts does not prove complete application startup, route discovery, storage execution, or performance.
- Use integration data for index usage, performance claims, collation, null handling, or backend-specific consistency.
- For production-performance conclusions, require reproducible queries and execution/profile evidence; green unit tests are insufficient.

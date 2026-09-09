# Migration Risk Map

Use this reference to build a target-specific migration matrix. Every concrete API, version, default, storage layout, and removed symbol must be verified against the existing application and the pinned target tag or release.

## Platform and dependency surface

Inventory resolved versions and compatibility for Java, Kotlin/KSP, Spring Boot, Jackson, Reactor, build plugins, Wow BOM/modules, and third-party integrations. Inspect dependency resolution rather than declarations alone, and keep compile/test configurations separate from the launched application's `runtimeClasspath`.

Compare the pinned Wow tag, BOM, official template, selected starters/storage modules, and published metadata. For Gradle feature variants, verify from the target tag whether the application needs both the base starter and a capability-qualified starter declaration; prove both `compileClasspath` and `runtimeClasspath` instead of assuming the capability retains the base API. When a target Spring Boot release splits auto-configuration into new modules, map every critical class referenced by the selected Wow modules to its owning target artifact and prove that artifact is present at runtime. A successful build or compile classpath is not evidence of runtime presence.

## Source and generated contracts

Search for:

- removed or changed annotations, handlers, gateway/wait APIs, `FilterExpression`/legacy `Condition` rewrite APIs, Query DSL, lifecycle ownership, stores, buses, and extension points;
- custom compiler/KSP assumptions and generated metadata;
- OpenAPI, JSON Schema, client SDK, serialization, and event revision outputs;
- custom auto-configuration, `@ConfigurationProperties`, exclusions, and bean overrides;
- custom `SnapshotQueryBackend`, `SnapshotStore`, `EventStore`, bus, processor, lifecycle, or routing implementations.

For each item record current evidence, target-tag evidence, required action, owner, verification, and rollback effect.

Source compatibility is not runtime capability: a generated route can compile while a custom `SnapshotQueryBackend` still fails to compile or execute the target aggregation. Prove the routed Backend and call the generated endpoint for every snapshot backend actually used.

## Wow 8.12.x to 8.13.0 negotiated query schema

Inventory every system, JSON, classpath, working-directory, and Bean `QuerySchemaSource`, then resolve the schema against each selected backend. The default `COMPATIBLE` mode accepts `EXACT` and `COMPATIBLE`; `STRICT` accepts only `EXACT`, while conflicting declarations fail before validation mode applies. Treat OpenAPI `x-wow-query-fields` as a static design-time catalog and runtime `QueryModelSchema` as backend capability evidence.

Verify old and new requests against every MongoDB or Elasticsearch mapping actually used. A Schema refresh changes only the receiving instance's cache, retains the old cache on failure, and neither broadcasts nor changes mappings or historical data. Do not add data conversion when source, mapping, storage shape, and writers are unchanged; require explicit mapping migration or reindex evidence when they are not.

## Wow 8.13.x to 8.14.0 event-stream aggregation

In 8.14, EventStream aggregation is an in-process query entry over the selected storage query implementation and the `EVENT_STREAM` query schema. Prove the aggregate invocation reaches that selected storage implementation and that its aggregation contract works with the configured schema mode.

Event-stream aggregation uses persisted event-stream documents: expand `body`, then use event-relative fields and declared payload fields under `body.body`. This release train adds no EventStream aggregation HTTP, OpenAPI, or Schema HTTP route; do not demand or invent a generated route, transport, or data migration.

## Wow 8.14.x to 8.15.0 query entry rename

When the pinned source is 8.14.x and the target is 8.15.0 or later, apply this source/configuration migration without compatibility aliases:

| 8.14.x | 8.15.0+ |
|---|---|
| `me.ahoo.wow.query.filter.QueryHandler` / `AbstractQueryHandler` | `me.ahoo.wow.query.QueryGateway` / `AbstractQueryGateway` |
| `me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler` / `DefaultSnapshotQueryHandler` | `me.ahoo.wow.query.snapshot.SnapshotQueryGateway` / `DefaultSnapshotQueryGateway` |
| `me.ahoo.wow.query.event.filter.EventStreamQueryHandler` / `DefaultEventStreamQueryHandler` | `me.ahoo.wow.query.event.EventStreamQueryGateway` / `DefaultEventStreamQueryGateway` |
| `snapshotQueryHandler` / `eventStreamQueryHandler` bean | `snapshotQueryGateway` / `eventStreamQueryGateway` bean |

Change custom query-filter `@FilterType` targets to the corresponding renamed Gateway. The renamed query entry no longer extends `Handler` or exposes `handle(QueryContext)`; direct implementations must implement `aggregate`, and `count` accepts only `FilterExpression`. Preserve the aggregate query beans, their registrars, selected storage query implementations, and storage query factories. Managed aggregate query beans traverse the renamed Gateway/filter chain; direct storage query factory access remains a trusted raw path that bypasses that policy chain.

The rename alone does not change HTTP/OpenAPI query shapes, wire formats, or stored events/snapshots, so it needs source compilation, Spring bean/qualifier startup, and representative managed-service/WebFlux query verification, but no data conversion. Reassess that conclusion if the same release also changes application schemas, storage layouts, or writers.

## Wow 8.16.x to V9 query gateway/backend split

Pin the exact V9 tag or commit first. When that target contains the V9 query split, migrate the V8.16.x JVM API directly; do not add aliases, adapters, duplicate beans, or compatibility proxies:

| V8.16.x | V9 |
|---|---|
| `QueryService<R>` | Deleted; split into `QueryBackend` and an aggregate-bound `QueryGateway<R>` |
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
| `QueryServiceProxy` / snapshot / event-stream proxies | Deleted; inject the aggregate-bound Gateway directly |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` and Aggregate/State/EventStream subtypes | Deleted; annotate domain fields with `@Mask`, `@KeepMask`, or custom `@Masking(strategy)` annotations |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | Deleted; no runtime object-mask SPI is retained |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` and model registries | Deleted; Query Schema discovers field annotations at runtime |
| `DataMasker` / `DataMasking` / `tryMask` | Deleted; migrate rules to static field annotations |
| `MaskingDynamicDocumentQueryFilter` | Deleted; use Gateway-owned masking before typed materialization |
| `QueryType.DYNAMIC_SINGLE` / `DYNAMIC_LIST` / `DYNAMIC_PAGED` | `SINGLE` / `LIST` / `PAGED` |
| `QueryType.isDynamic` | Deleted; typed and node paths share operation types |
| `SnapshotRepository` | `SnapshotStore` |
| `NoOpSnapshotRepository` | `NoOpSnapshotStore` |
| `InMemorySnapshotRepository` | `InMemorySnapshotStore` |
| `DelaySnapshotRepository` | `DelaySnapshotStore` |
| `ElasticsearchSnapshotRepository` | `ElasticsearchSnapshotStore` |
| `TracingSnapshotRepository` | `TracingSnapshotStore` |
| `SnapshotRepositoryInstrumenter` and span-name extractors | `SnapshotStoreInstrumenter` and matching Store extractors |
| `SnapshotRepositorySpec` | `SnapshotStoreSpec` |
| `createSnapshotRepository()` in `SnapshotStoreSpec`, `CommandDispatcherSpec`, and `SnapshotQueryBackendSpec` | `createSnapshotStore()` |
| Mongo `createAggregateIdIndex()`, `createAggregateIdAndVersionUniqueIndex()`, `createRequestIdUniqueIndex()`, `createAggregateIdAndRequestIdUniqueIndex()`, `createTenantIdIndex()`, `createOwnerIdIndex()` | Removed; reconcile the complete managed index set through `EventStreamSchemaInitializer` / `SnapshotSchemaInitializer` `initSchema()` or `initAll()`. For EventStream request id uniqueness, set `enableRequestIdUniqueIndex = true` for the request-id index or keep `false` (default) for the aggregate-id/request-id compound index |
| Elasticsearch `UNLIMITED_SIZE` / `Int.searchSize()` | Removed; use `ListQuery.limit` (`0` remains unlimited); the Backend owns PIT / `search_after` paging |
| `IndexTemplateInitializer.InitSubscriber` | Removed; compose and await `ensureAllTemplates()`, or call blocking `initAll()`; propagate failures |
| `EventStoreSpec.TIMES` / `DEFAULT_PARALLELISM` | `DEFAULT_CONCURRENCY_TEST_ITERATIONS` / `DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY` |

`QueryService<R>` has no one-to-one replacement. Move storage execution to an `ObjectNode`-returning `QueryBackend` and application calls to the aggregate-bound `QueryGateway<R>`. V8 passed `NamedAggregate` on each method call; V9 binds it and the routed `QueryBackendBinding` when constructing the Gateway. Factories return complete Backend/Provider bindings and cache them by materialized aggregate. Spring registers `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the alias prefix when absent. Verify exact names and qualifiers from the target, including Snapshot Repository-to-Store renames.

Gateway implementation constructors are separate from the public QueryGateway method contract. Inspect the target constructor instead of copying parameters from a different V9 revision. Implement only the compatibility scope authorized for this migration; an explicitly accepted SPI break requires updating and recompiling custom implementations, not adding constructor, override, or proxy bridges. Ordinary source callers and precompiled callers need separate evidence.

Delete old Mask types, Beans, registries, and custom result filters; move rules to `@Mask`, `@KeepMask`, or runtime-retained custom annotations using `@Masking`. Schema discovers and compiles the static rules without KSP. Verify complete value domains, Unicode-code-point handling, nested containers, protection inheritance, conflicts and unknown EventStream bodyType rejection. Result Mask runs before typed materialization; aggregate results are not masked, so protected group/metric/expression inputs must be rejected before execution. Verify aggregate-state loading's own protection path rather than inferring QueryPolicy coverage from the presence of masking.

Remove `QueryType.isDynamic` branches: typed and node queries share operation types and differ only in optional result materialization. WebFlux imports `getRawRequest`/`writeRawRequest` from `me.ahoo.wow.webflux.route`; the raw request uses a private Reactor Context key and is not a field of the current QueryContext.

### Fixed query pipeline targets

Use this section when the inspected target provides `QueryFilter.prepare`, `QueryPolicy.evaluate`, and Backend `(query, schema)` methods. Earlier V9 targets can expose around filters, `ResolvedQuery`, and negotiated validation modes; those are historical contracts, not requirements for the refactored target. A candidate commit or version property is not publication evidence.

| Prior implementation | Migration for the fixed pipeline |
|---|---|
| Around QueryFilter with `next` or result Publisher ownership | `prepare(QueryContext<Q>): Mono<Q>` returns one replaceable logical request; no continuation or result mutation |
| Mandatory rules inside ordinary prepare, or custom authorization hooks | `QueryPolicy.evaluate(ContextView, QueryContext<*>): Mono<FilterExpression>` returns an additional condition or error, AND-composed after all prepare steps |
| Snapshot-only policy wiring | Both default Gateways/registrars consume QueryPolicy; a policy determines applicability from QueryContext and returns MatchAllFilter when not applicable. ABAC reads tags only for Snapshot |
| Result/error filters | Fixed Gateway Mask and typed materialization; QueryObserver observes termination without owning execution or results |
| ResolvedQuery Backend argument or Schema request resolver | Explicit logical `(query, schema)` inputs; Gateway calls public validation, Schema holds facts, Backend compiles and executes |
| `validationMode` / `errorHandler` constructor parameters | Inspect the target's filter/policy/observer constructor. Remove the old `wow.query.schema.validation-mode` setting; even `strict` and camelCase forms fail startup |
| Flat Schema field metadata/constructors | Recursive logical value declarations and native bindings; HTTP metadata exposes `root` with properties/items/additionalProperties/alternatives and public protection/capability facts |

The shared Gateway captures one Schema and identity per subscription, runs prepare, appends trusted scope and policy filters with AND, applies model defaults, calls public validation, executes the Backend, then masks node results and optionally materializes typed results. Schema is an immutable fact model, not a request validator, policy selector or query compiler. Provider `schema/refresh` owns fact loading; refresh publishes a new instance locally, leaves existing subscriptions on their captured instance, and changes neither mappings nor stored data. Errors and empty policy completion stop Backend invocation; retry/repeat starts a fresh subscription.

For raw infrastructure execution, obtain `factory.create(namedAggregate).backend` and its paired `schemaProvider`. The caller supplies the Schema, public admission and required deletion/access predicates; raw execution does not inherit Gateway policy, defaults, Mask or observation. Both Backends compile logical paths using their native bindings and enforce native constraints before execution. Every Backend subscription owns fresh standard-JSON ObjectNodes; do not share mutable nodes, expose BSON/POJONode, or mutate published results.

Factory/route binding renames alone do not change stored documents. Compare actual Schema metadata and request contracts for the selected target: do not infer wire compatibility from unchanged Gateway methods. Retain the agreed V9 Condition/Operator/ConditionDsl adapters, legacy query constructors and count client overloads until 10.0.0. The existing REST compatibility window covers list/paged/single `condition` and bare count `operator`, with canonical `filter`/`op` output and no mixed forms; do not expand it to new cursor or aggregation shapes. Rebuild extensions and verify both canonical and supported legacy requests.

Minimum migration evidence: custom SPI compilation and runtime classpath, exact Spring registration, policy/scope preservation after prepare replacement, empty/error policies stopping Backend, recursive Schema metadata and refresh, and a Policy Bean→HTTP Handler→Backend regression for each application path claimed. Distinguish manual-route WebTestClient evidence from complete startup/route discovery and from real storage integration. Exercise each actually routed Backend. Skip data conversion only when mappings, storage layouts and writers are unchanged; otherwise establish the specific data/reindex requirement independently.

`CursorQuery`/`CursorPage` are V9 additions, not a replacement for totals/page jumps. In fixed-pipeline targets, cursor sorting requires independent `CURSOR_SORT` capability, single-valued unmasked ordered data, no protected alias and a stable unique tie-breaker; `SORT` alone is insufficient. Older EXACT/SINGLE status contracts apply only where the inspected target exposes them. Keep filter/sort unchanged and pass the Backend's opaque nextCursor unchanged; null ends traversal. Do not wrap/decode/rewrite or bridge tokens across routes, versions or Backends. Cursor adoption alone does not rewrite stored data.

When the exact V9 target contains `IS_EMPTY_STRING` and `IS_NOT_EMPTY_STRING`, use them only for explicit empty-string intent on exact-match, single-valued String fields. Do not mechanically rewrite V8 `EQ ""` or `NE ""`: first preserve the source contract for null, missing, whitespace, collections, MongoDB, Elasticsearch, and HTTP guard behavior, then prove the chosen V9 expression with contract tests. `isEmptyString()` matches only `""`; `isNotEmptyString()` requires present, non-null, non-empty String semantics. These query-expression changes do not require data conversion unless separate target evidence identifies a mapping or stored-data change.

## Runtime and data coupling

Identify every writer, reader, database/namespace, bounded context, aggregate route, ownership marker, stream/topic, snapshot/event format, PrepareKey store, index, and background process. Determine whether source and target versions can safely coexist; assume they cannot unless the pinned contract proves otherwise.

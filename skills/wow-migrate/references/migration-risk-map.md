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
| `MaskingDynamicDocumentQueryFilter` | Deleted; the Gateway masks after all result filters and before typed materialization |
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

`QueryService<R>` has no one-to-one replacement. Move storage execution and Schema capability to an `ObjectNode`-returning `QueryBackend`; keep the managed entry, filtering, and typed materialization in the aggregate-bound `QueryGateway<R>`. The V8 Gateway accepted a `NamedAggregate` on every method call. V9 binds only the `NamedAggregate` and routed Backend when constructing the Gateway, so callers remove that method argument. Custom `AbstractQueryGateway` subclasses supply `namedAggregate`, `backend`, `targetType`, `filters`, `filterType`, and `errorHandler`; use the default Snapshot/EventStream Gateway when no custom entry policy is required. `QueryFieldSchemaMetadata.masked`, `QueryFieldDeclaration.maskRule`, `QueryFieldSchema.maskRule`, and `LogicalQueryFieldSchema.maskRule` are independent Schema constructor contracts with added masking fields and no V8 JVM constructor overload. Filters cannot use `QueryType.isDynamic`: typed and node calls share one ObjectNode chain and differ only by optional Jackson materialization after it. Remove typed/dynamic dispatch branches instead of inventing a replacement discriminator.

Delete old Mask types, implementations, Beans, registries, and custom filters without creating an ObjectNode compatibility layer. Move each rule to `@Mask`, `@KeepMask(prefix, suffix)`, or a runtime-retained custom annotation carrying `@Masking(strategy)`. Query Schema discovers, validates, and compiles these rules at runtime without KSP. Every managed Snapshot/EventStream typed, dynamic, and aggregate-state result query reads current Schema after result filters and before typed materialization; the same Schema instance reuses its Masker and a refresh-published instance recompiles it. Unavailable Schema fails managed `single`/`list`/`paged` closed without subscribing to the Backend, while `count` does not load masking Schema. The `COMPATIBLE` unavailable fallback applies only to direct `QueryModelSchemaProvider.resolve(...)` request resolution. Direct Backend Factory access and a custom Backend without `QueryModelSchemaProvider` remain trusted raw-value boundaries.

Schema metadata exposes only field-level `masked: Boolean`; Strategy details and executable rules stay in memory. A root Schema with no masked fields takes the no-walk fast path. Ordinary filter/search/sort and count remain valid; reject groups, field metrics, and expressions that reference masked fields. Verify full Unicode-code-point masking, `KeepMask` edge preservation and short-value full masking, null/empty behavior, nested collections, String-only validation, conflicting rules, and unknown EventStream `bodyType` fail-closed behavior.

Spring registers `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the alias prefix when absent, and do not retain the old `*.QueryService` bean names. Snapshot Store qualifiers replace the corresponding Repository qualifiers: `noOpSnapshotRepository`, `inMemorySnapshotRepository`, `delaySnapshotRepository`, `mongoSnapshotRepository`, and `elasticsearchSnapshotRepository` become the matching `*SnapshotStore` names. A `QueryFilter` without `@FilterType` applies generally; a model-specific filter targets `SnapshotQueryGateway` or `EventStreamQueryGateway`.

Each aggregate-bound Gateway captures the routed `ObjectNode` Backend during bean construction and runs one around chain. Request filters rewrite or reject before `next`; the terminal invokes the Backend and stores its result Publisher; result filters run after `next` and may rewrite that Publisher. The Gateway then masks managed data results and optionally materializes typed results with Jackson. Application and transport code use the Gateway. Direct Backend Factory access is a trusted low-level path that bypasses request filtering, authorization, result filters, masking, and error observation. Schema handlers use the same routed Backend Factory and `NamedAggregate` selection as queries.

Every Backend subscription must produce fresh, exclusively owned `ObjectNode` values containing only standard JSON tree nodes. Do not cache or share mutable nodes across retry, repeat, or concurrent subscriptions, expose `Map`, BSON, `POJONode`, or arbitrary POJOs, or mutate a node after publication.

Factory JVM names and public route binding values both use Backend naming. Migrate `*-query-service-factory` values to `*-query-backend-factory`, including `mongo-snapshot-query-backend-factory` and `elasticsearch-event-stream-query-backend-factory`; no old binding alias is retained. V9.x retains deprecated `Condition`/`Operator` JVM types, `ConditionDsl`, legacy query constructors, count client overloads, and V8 REST `condition`/`operator` input as adapters to `FilterExpression`; all are scheduled for removal in 10.0.0. Rebuild downstream code, migrate to `FilterExpression`/`FilterDsl`, and verify both canonical and compatible requests before that removal. These source/configuration changes do not alter Backend wire trees, storage layouts, or existing data and therefore require no data conversion. Migrating old Mask rules to field annotations restores managed-response confidentiality without rewriting raw Backend or stored values. Verify compilation, exact Spring bean/qualifier startup, managed Gateway filtering and masking, trusted raw Backend behavior, Schema metadata, generated HTTP routes, and every routed MongoDB/Elasticsearch Backend.

`CursorQuery`/`CursorPage` are V9 additions with no V8 token or service replacement. Keep `PagedQuery` when callers require totals or page jumps; adopt cursor traversal only when forward keyset semantics are intended. For an adopted cursor path, verify the exact target provides the JVM, Gateway, Backend, WebFlux/OpenAPI, and client surface; keep `filter` and `sort` unchanged between pages, require an EXACT/SINGLE/unmasked stable unique effective sort, and stop at `nextCursor == null`. Do not issue compatibility or application cursor tokens/codecs: the first request cursor is null/omitted, and every later request passes the Backend-returned `nextCursor` unchanged. Application code does not wrap, bind, interpret, decode, or rewrite it; the token carries no authorization state. Do not bridge tokens across MongoDB, Elasticsearch, versions, or routes. Cursor adoption changes request/response and operational behavior but does not by itself rewrite stored query documents.

When the exact V9 target contains `IS_EMPTY_STRING` and `IS_NOT_EMPTY_STRING`, use them only for explicit empty-string intent on exact-match, single-valued String fields. Do not mechanically rewrite V8 `EQ ""` or `NE ""`: first preserve the source contract for null, missing, whitespace, collections, MongoDB, Elasticsearch, and HTTP guard behavior, then prove the chosen V9 expression with contract tests. `isEmptyString()` matches only `""`; `isNotEmptyString()` requires present, non-null, non-empty String semantics. These query-expression changes do not require data conversion unless separate target evidence identifies a mapping or stored-data change.

## Runtime and data coupling

Identify every writer, reader, database/namespace, bounded context, aggregate route, ownership marker, stream/topic, snapshot/event format, PrepareKey store, index, and background process. Determine whether source and target versions can safely coexist; assume they cannot unless the pinned contract proves otherwise.

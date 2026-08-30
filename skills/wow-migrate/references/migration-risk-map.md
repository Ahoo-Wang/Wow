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
| `UnavailableQueryService` | `UnavailableQueryBackend` |
| `QueryServiceRegistrar` | `QueryGatewayRegistrar` |
| `SnapshotQueryServiceRegistrar` | `SnapshotQueryGatewayRegistrar` |
| `EventStreamQueryServiceRegistrar` | `EventStreamQueryGatewayRegistrar` |
| `QueryServiceProxy` / snapshot / event-stream proxies | Deleted; inject the aggregate-bound Gateway directly |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | `ObjectNodeMasker` |
| `QueryType.DYNAMIC_SINGLE` / `DYNAMIC_LIST` / `DYNAMIC_PAGED` | `SINGLE` / `LIST` / `PAGED` |

Spring registers `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the alias prefix when absent, and do not retain the old `*.QueryService` bean names. A `QueryFilter` without `@FilterType` applies generally; a model-specific filter targets `SnapshotQueryGateway` or `EventStreamQueryGateway`.

Each aggregate-bound Gateway captures the routed `ObjectNode` Backend during bean construction and runs one around chain. Request filters rewrite or reject before `next`; the terminal invokes the Backend and stores its result Publisher; result filters run after `next` and may rewrite that Publisher. The built-in snapshot/event-stream masker applies to `SINGLE`, `LIST`, and `PAGED`, before the Gateway optionally materializes typed results with Jackson; it does not mask `COUNT` or `AGGREGATION`. Application and transport code use the Gateway. Direct Backend Factory access is a trusted low-level path that bypasses request filtering, authorization, result masking, and error observation. Schema handlers use the same routed Backend Factory and `NamedAggregate` selection as queries.

Every Backend subscription must produce fresh, exclusively owned `ObjectNode` values containing only standard JSON tree nodes. Do not cache or share mutable nodes across retry, repeat, or concurrent subscriptions, expose `Map`, BSON, `POJONode`, or arbitrary POJOs, or mutate a node after publication. An `ObjectNodeMasker` may mutate its input or return a replacement, but must preserve required snapshot/event-stream envelope fields and typed field shapes; typed materialization fails closed after masking.

Factory JVM names change, but public route binding values deliberately retain the `*-query-service-factory` suffix, including `mongo-snapshot-query-service-factory` and `elasticsearch-event-stream-query-service-factory`. This split is JVM source- and binary-breaking, so rebuild downstream code. By itself it does not change HTTP paths, request/response JSON, generated OpenAPI, wire formats, storage layouts, or existing data, and therefore requires no data conversion. Verify compilation, exact Spring bean/qualifier startup, managed Gateway filtering and masking, trusted raw Backend behavior, generated HTTP routes, Schema routes, and every routed MongoDB/Elasticsearch Backend actually used.

## Runtime and data coupling

Identify every writer, reader, database/namespace, bounded context, aggregate route, ownership marker, stream/topic, snapshot/event format, PrepareKey store, index, and background process. Determine whether source and target versions can safely coexist; assume they cannot unless the pinned contract proves otherwise.

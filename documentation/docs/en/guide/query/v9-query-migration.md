---
title: V9 Query Migration
description: Migrate the V8 query JVM API to aggregate Gateways and ObjectNode Backends.
---

# V9 Query Migration

## Migration Boundary

Except for the `Condition` migration window below, V9 removes old JVM types without bridges, type aliases, or deprecation windows. This breaks JVM source and binary users of those types, so recompile downstream code and migrate directly with the tables below. `QueryFieldSchemaMetadata.masked`, `QueryFieldDeclaration.maskRule`, `QueryFieldSchema.maskRule`, and `LogicalQueryFieldSchema.maskRule` are Schema constructor contracts with added masking fields and no V8 JVM constructor overload.

V9.x provides an explicit query-condition migration window: deprecated `Condition`/`Operator` JVM types, `ConditionDsl`, legacy query constructors, count client overloads, and existing deserialization remain available and are normalized to `FilterExpression`. WebFlux list/paged/single requests may still submit `condition`, and count requests may still submit the bare `operator` shape. These compatibility APIs are scheduled for removal in 10.0.0; new code should use `FilterExpression`/`FilterDsl` immediately. Canonical `filter`, OpenAPI, and outbound JSON use only `op`.

### ConditionDsl Migration

| V8.16.3 `ConditionDsl` | V9 `FilterDsl` | Migration note |
| --- | --- | --- |
| Standalone `condition { ... }` | `filterExpression { ... }` | An empty legacy block meant match-all; an empty V9 block is invalid, so use `matchAll()` explicitly |
| `listQuery` / `pagedQuery` / `singleQuery` / `cursorQuery` `{ condition { ... } }` | The same query builder with `filter { ... }` | Calling `filterExpression { ... }` inside a query builder creates and discards a standalone value |
| `condition(existingCondition)` inside a Condition block | `expression(existingFilter)` | The deprecated `existingCondition.toFilterExpression()` adapter is available only through V9.x; a query builder instead uses `filter(existingFilter)` |
| `all()` | `matchAll()` | `matchNone()` is also available in V9 |
| `and { ... }` / `or { ... }` / `nor { ... }` | Same calls when at least one predicate is emitted | If predicates are conditional, guard the whole logical-block call and omit it when none apply; inserting `matchAll()` changes `or`/`nor` semantics |
| `id(value)`, `ids(values)`, `aggregateId(value)`, `aggregateIds(values)`, `tenantId(value)`, `ownerId(value)`, `spaceId(value)` | Same calls | For empty `ids` or `aggregateIds`, call `matchNone()` instead; `SpaceId` was a `String` type alias, so V9 accepts the string value directly |
| `deleted(state)` | `deletion(state)` | `DeletionState` is unchanged |
| `field nested { ... }` | `field.path { ... }` only when AND grouping is intended | V8 flattens nested children into the surrounding block; V9 `path` groups multiple children with implicit AND and must be omitted when no child is emitted |
| `field eq value`, `ne`, `gt`, `gte`, `lt`, `lte` | Same infix calls for scalar values | `KCallable` overloads are removed; structured JVM equality and range operands need the explicit expressions described below |
| `field.contains(value, ignoreCase)` | `field.containsText(value, StringComparison.CASE_*)` | Select `CASE_SENSITIVE` or `CASE_INSENSITIVE` explicitly |
| `field startsWith value` / `field endsWith value` | `field.startsWithText(value)` / `field.endsWithText(value)` | The V9 text helpers are not infix; pass `StringComparison` when case-insensitive |
| `field isIn values` / `field notIn values` | Same infix calls | V9 accepts non-empty `Iterable<*>`; map empty `isIn` to `matchNone()` and empty `notIn` to `matchAll()` |
| `field between (lower to upper)` / `field between lower to upper` | `field.between(lower, upper)` | The intermediate `BetweenStart` form is removed |
| `field all values` | `field containsAll values` | This is the collection contains-all predicate; map an empty collection to `matchNone()` |
| `field match query` | `field search query` | Or call `search(query, field)`; for the legacy blank field, use `search(query)` to retain global search; the default mode is `SearchMode.TERMS` |
| `field elemMatch { ... }` | `field.elementMatch { ... }` | `elementMatch` is not infix and cannot contain root filters; replace a legacy empty block with `field.elementMatch { matchAll() }` |
| `field.isNull()`, `field.notNull()`, `field.isTrue()`, `field.isFalse()` | `field.isNull()`, `field.isNotNull()`, `field eq true`, `field eq false` | V9 equality accepts nullable values directly |
| `field.exists(true)` / `field.exists(false)` | `field.exists()` / `field.notExists()` | The Boolean selector is replaced by explicit operations |
| `field beforeToday time` | `field.beforeToday(localTime, ...)` | The V9 helper is not infix and requires `LocalTime`; it also accepts `ZoneId`, `String?` date pattern, and `TimeUnit` |
| `field recentDays days` / `Property::field recentDays days` | `field.recentDays(days, ...)` | The V9 helper is not infix and has no `KCallable` overload |
| `field.today(pattern)`, `tomorrow`, week/month helpers | `field.today(datePattern = pattern)`, and matching named-argument calls | V9 inserts `ZoneId?` before `datePattern`; do not keep the old positional pattern argument |
| `field.recentDays(days, pattern)` / `field.earlierDays(days, pattern)` | `field.recentDays(days, datePattern = pattern)` / `field.earlierDays(days, datePattern = pattern)` | V9 also accepts `ZoneId` and `TimeUnit` |

Remove property-reference wrappers instead of recreating the deleted `KCallable` overloads. Use the stable logical field path required by Query Schema, such as `"state.status"`, and verify every migrated expression against its selected Backend.

`ConditionDsl.nested` flattened its child predicates into the surrounding logical block. A direct `path` replacement is equivalent at the root, inside `and`, or for one child. Inside `or` or `nor`, keep the predicates as separate operands by writing their qualified paths at that same level; for example, migrate `or { "state" nested { "a" eq 1; "b" eq 2 } }` to `or { "state.a" eq 1; "state.b" eq 2 }`, not to one `"state".path { ... }` operand.

If every predicate inside a legacy `nested` block is conditional, guard the entire `path` invocation and omit it when none apply. An empty V8 `nested` block was a no-op, while an empty V9 `path` block is invalid.

When a logical block is populated conditionally, move the same guard around the block invocation so an empty block is omitted, as V8 did. For example, use `if (includeName || includeStatus) { or { if (includeName) "name" eq name; if (includeStatus) "status" eq status } }`. Do not put `matchAll()` into an empty `or` or `nor`.

V9 collection filters reject empty values at construction time. Preserve V8 semantics with ordinary Kotlin branches inside the DSL: `if (ids.isEmpty()) matchNone() else ids(ids)`, `if (values.isEmpty()) matchNone() else "field" isIn values`, and `if (excluded.isEmpty()) matchAll() else "field" notIn excluded`.

`FilterDsl` serializes arbitrary Kotlin objects and maps as JSON objects, which canonical `EQ`/`NE` reject. Scalar and scalar-array equality keeps the DSL form. To preserve a V8 in-process POJO/map equality comparison, construct `EqualFilter` or `NotEqualFilter` explicitly with `LogicalField(field)` and `JsonNodeFactory.instance.pojoNode(value)`. `POJONode` and scalar-array equality are available only to JVM construction and legacy `Condition` compatibility; canonical V9 REST filter equality accepts a JSON scalar.

Structured V8 operands for `gt`, `gte`, `lt`, `lte`, or either `between` bound need the same JVM-only treatment. Construct the matching `GreaterThanFilter`, `GreaterThanOrEqualFilter`, `LessThanFilter`, `LessThanOrEqualFilter`, or `BetweenFilter` explicitly and wrap each POJO/map operand with `JsonNodeFactory.instance.pojoNode(value)`. Canonical REST range operands remain non-null JSON scalars.

The same boundary applies to structured elements in `isIn`, `notIn`, and collection `all`: `FilterDsl` converts them to rejected JSON objects. For an in-process native-value collection, construct `InFilter`, `NotInFilter`, or `ContainsAllFilter` explicitly and map every structured element with `JsonNodeFactory.instance.pojoNode(value)`; for example, `InFilter(LogicalField(field), values.map(JsonNodeFactory.instance::pojoNode))`. Keep the empty-list branches above. `POJONode` collection elements are JVM-only; canonical REST collections contain non-null JSON scalars.

When V8 passes a `DateTimeFormatter` rather than a pattern string, use the matching relative-time filter class directly with its named `dateFormatter` property, for example `TodayFilter(LogicalField(field), dateFormatter = formatter)` or `RecentDaysFilter(LogicalField(field), days, dateFormatter = formatter)`. `BeforeTodayFilter` additionally takes `time = localTime.toString()`. `dateFormatter` is JVM-only and ignored on the wire; canonical REST uses `datePattern`.

### Direct Condition JVM Migration

`Condition`, `ICondition`, `Operator`, and the generic `ConditionOptions` map are compatibility APIs only through V9.x. Replace them with the closed `FilterExpression` hierarchy; downstream code cannot add another `FilterExpression` subtype. If a custom `ICondition` only models built-in operators, translate it to the corresponding built-in expression. Move genuinely custom query semantics to a request `QueryFilter` or the selected Backend rather than extending the canonical wire AST.

`FilterOperator` is metadata exposed by a concrete expression, not a selector for a generic constructor. Remove code that builds or interprets one generic condition from an operator/options tuple. Inspect typed properties instead: `DeletionFilter.deletionState`, text-filter `stringComparison`, relative-time `zoneId`/`datePattern`/`dateFormatter`/`timeUnit`, and each concrete expression's `value`, `values`, `operands`, `predicate`, `query`, or `fields` property.

| V8 JVM surface | V9 canonical JVM surface |
| --- | --- |
| `Condition(...)` / custom `ICondition` with `field`, `operator`, `value`, `children`, `options` | Construct the concrete `FilterExpression` below; no generic condition constructor or custom expression subtype |
| `Operator` | `FilterOperator`; notable renames are `ALL → MATCH_ALL`, `DELETED → DELETION`, `ALL_IN → CONTAINS_ALL`, `ELEM_MATCH → ELEMENT_MATCH`, `NULL → IS_NULL`, `NOT_NULL → IS_NOT_NULL`, and `MATCH → SEARCH`; `TRUE`/`FALSE` become `EQ` Boolean values |
| `ConditionOptions`, option-key constants, `ignoreCaseOptions`, `datePatternOptions` | Typed properties: `stringComparison`, `zoneId`, `datePattern`, `dateFormatter`, and `timeUnit` |
| `valueAs`, `deletionState`, `ignoreCase`, `zoneId`, `datePattern` inspectors | Pattern-match the concrete expression and read its typed property |
| `Condition.ALL` / `all()` | `MatchAllFilter` |
| `Condition.ACTIVE` / `active()` / `deleted(false)` | `DeletionFilter(DeletionState.ACTIVE)` |
| `deleted(true)` / `deleted(state)` | `DeletionFilter(DeletionState.DELETED)` / `DeletionFilter(state)` |
| `and`, `or`, `nor` | `AndFilter`, `OrFilter`, `NorFilter` with non-empty operand lists |
| `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId`, `spaceId` | `IdFilter`, `IdsFilter`, `AggregateIdFilter`, `AggregateIdsFilter`, `TenantIdFilter`, `OwnerIdFilter`, `SpaceIdFilter`; preserve the empty-list branches documented above |
| `eq`, `ne` | `EqualFilter`, `NotEqualFilter`; use scalar `JsonNode` values, scalar arrays, or the JVM-only `POJONode` migration described above |
| `gt`, `gte`, `lt`, `lte` | `GreaterThanFilter`, `GreaterThanOrEqualFilter`, `LessThanFilter`, `LessThanOrEqualFilter`; use the JVM-only `POJONode` migration above for structured operands |
| `contains`, `startsWith`, `endsWith` | `ContainsFilter`, `StartsWithFilter`, `EndsWithFilter` with explicit `StringComparison` |
| `isIn`, `notIn`, `between`, collection `all` | `InFilter`, `NotInFilter`, `BetweenFilter`, `ContainsAllFilter`; use the JVM-only `POJONode` migration above for structured bounds |
| `match(field, query)` | Non-blank field: `SearchFilter(query, setOf(LogicalField(field)), SearchMode.TERMS)`; blank field: `SearchFilter(query)` or `filterExpression { search(query) }` |
| `elemMatch(field, condition)` | `ElementMatchFilter(LogicalField(field), predicate)`; combine multiple children with a non-empty `AndFilter`, and map `Condition.ALL` from an empty legacy DSL block to `MatchAllFilter` |
| `isNull`, `notNull`, `isTrue`, `isFalse`, `exists(true)`, `exists(false)` | `IsNullFilter(LogicalField(field))`, `IsNotNullFilter(LogicalField(field))`, `filterExpression { field eq true }`, `filterExpression { field eq false }`, `ExistsFilter(LogicalField(field))`, `NotExistsFilter(LogicalField(field))` |
| `today`, `beforeToday`, `tomorrow`, week/month, `recentDays`, `earlierDays` | Matching `TodayFilter`, `BeforeTodayFilter`, `TomorrowFilter`, `ThisWeekFilter`, `NextWeekFilter`, `LastWeekFilter`, `ThisMonthFilter`, `LastMonthFilter`, `RecentDaysFilter`, `EarlierDaysFilter`; use typed constructor properties and the formatter boundary above |
| `condition.toFilterExpression()` | Transitional V9.x adapter only; replace stored/public `Condition` values with their concrete expression before 10.0.0 |

Data-query HTTP request and result envelopes, Backend wire trees, storage layouts, and existing data do not change because of this JVM refactor or static-annotation masking. Query Schema HTTP metadata and its generated OpenAPI component do change: each field adds `masked: Boolean`. No storage-data migration is required, and raw values in the Backend and storage are not rewritten. After old mask rules move to field annotations, the managed Gateway restores response confidentiality semantics.

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
| `DynamicDocumentMasker` | Removed; use `@Mask`, `@KeepMask`, or a custom `@Masking` meta-annotation on domain fields |
| `AggregateDynamicDocumentMasker` | Removed; built-in `SchemaMaskQueryFilter` masks Snapshot and EventStream results from Query Schema |
| `StateDynamicDocumentMasker` | Removed; declare static mask annotations on state fields |
| `EventStreamDynamicDocumentMasker` | Removed; declare static mask annotations on event-payload fields |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | Removed; no runtime object-mask SPI is retained |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` | Removed; Query Schema discovers rules from field annotations |
| `StateDataMaskerRegistry` / `EventStreamMaskerRegistry` | Removed; model maskers are no longer registered |
| `DataMasker` / `DataMasking` / `tryMask` | Removed; migrate to static field annotations |
| `MaskingDynamicDocumentQueryFilter` | Removed; replaced by the framework-owned outermost `SchemaMaskQueryFilter` |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |
| `QueryType.isDynamic` | Removed; typed and node paths share operation types |
| `SnapshotRepository` | `SnapshotStore` |
| `NoOpSnapshotRepository` | `NoOpSnapshotStore` |
| `InMemorySnapshotRepository` | `InMemorySnapshotStore` |
| `DelaySnapshotRepository` | `DelaySnapshotStore` |
| `ElasticsearchSnapshotRepository` | `ElasticsearchSnapshotStore` |
| `TracingSnapshotRepository` | `TracingSnapshotStore` |
| `SnapshotRepositoryInstrumenter` | `SnapshotStoreInstrumenter` |
| `SnapshotRepositorySaveSpanNameExtractor` | `SnapshotStoreSaveSpanNameExtractor` |
| `SnapshotRepositoryLoadSpanNameExtractor` | `SnapshotStoreLoadSpanNameExtractor` |
| `SnapshotRepositorySpec` | `SnapshotStoreSpec` |
| `SnapshotStoreSpec.createSnapshotRepository()` / `CommandDispatcherSpec.createSnapshotRepository()` / `SnapshotQueryBackendSpec.createSnapshotRepository()` | `createSnapshotStore()` |
| Mongo `createAggregateIdIndex()`, `createAggregateIdAndVersionUniqueIndex()`, `createRequestIdUniqueIndex()`, `createAggregateIdAndRequestIdUniqueIndex()`, `createTenantIdIndex()`, `createOwnerIdIndex()` | Removed; use `EventStreamSchemaInitializer` / `SnapshotSchemaInitializer` `initSchema()` or `initAll()` to reconcile the complete managed index set. For EventStream request id uniqueness, `enableRequestIdUniqueIndex = true` selects the request-id index; `false` (default) selects the aggregate-id/request-id compound index |
| Elasticsearch `UNLIMITED_SIZE` / `Int.searchSize()` | Removed; pass `ListQuery.limit` directly (`0` remains unlimited) and let the Backend page with PIT / `search_after` |
| `IndexTemplateInitializer.InitSubscriber` | Removed; compose and await `ensureAllTemplates()`, or call blocking `initAll()`; initialization failures propagate |
| `EventStoreSpec.TIMES` | `EventStoreSpec.DEFAULT_CONCURRENCY_TEST_ITERATIONS` |
| `EventStoreSpec.DEFAULT_PARALLELISM` | `EventStoreSpec.DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY` |

Typed and node results share the `SINGLE`, `LIST`, `PAGED`, and `CURSOR` operation types. A Backend always returns `ObjectNode`; the Gateway optionally uses Jackson to materialize typed results after generic result filters complete.

There is no one-to-one replacement for `QueryService<R>`: move storage queries and Schema capability to an `ObjectNode`-returning `QueryBackend`, while the managed entry, filter chain, and typed materialization remain in the aggregate `QueryGateway<R>`. The old `QueryGateway` accepted a `NamedAggregate` on every call; V9 binds only the `NamedAggregate` and routed Backend when constructing the Gateway, so `single`, `list`, `paged`, `cursor`, `count`, and `aggregate` calls no longer pass an aggregate argument. A custom `AbstractQueryGateway` subclass must supply the new `namedAggregate`, `backend`, `targetType`, `filters`, `filterType`, and `errorHandler` constructor contract; use the default Snapshot/EventStream Gateway when no custom entry policy is required.

Filters no longer use `QueryType.isDynamic` to distinguish a final typed result from a node result. Both paths traverse the same ObjectNode FilterChain and differ only by optional Jackson materialization after the chain. Remove branches used only for typed/dynamic dispatch; do not invent a replacement result-type discriminator.

Delete old Mask types, implementations, Beans, registries, and custom filters without creating an ObjectNode Mask compatibility layer. Once old rules are declared on domain fields, Snapshot and EventStream typed, dynamic, and aggregate-state load entries mask automatically on the same managed Gateway path: the framework-owned `SchemaMaskQueryFilter` reads current Schema for every result query, the same instance reuses its Masker, a refresh-published instance recompiles it, unavailable Schema fails result queries closed without subscribing to the Backend, and count does not read masking Schema. A direct Backend Factory or a custom Backend without `QueryModelSchemaProvider` remains a trusted low-level boundary that returns raw values; `COMPATIBLE` unavailable fallback belongs only to direct `QueryModelSchemaProvider.resolve(...)` request resolution.

## Static Mask Migration

After removing the old Registry/filter, migrate full masking to `@Mask`, edge-preserving rules to `@KeepMask(prefix, suffix)`, and domain-specific rules to runtime field annotations carrying `@Masking(strategy)`. Do not add an ObjectNode compatibility layer or a new Registry. See [Field Masking](./masking.md) for the complete API, Unicode/empty-value semantics, behavior matrix, and fail-closed contract.

## Spring Bean Mapping

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |
| `noOpSnapshotRepository` | `noOpSnapshotStore` |
| `inMemorySnapshotRepository` | `inMemorySnapshotStore` |
| `delaySnapshotRepository` | `delaySnapshotStore` |
| `mongoSnapshotRepository` | `mongoSnapshotStore` |
| `elasticsearchSnapshotRepository` | `elasticsearchSnapshotStore` |

The exact new Bean names are `{contextAlias.}{aggregateName}.SnapshotQueryGateway` and `{contextAlias.}{aggregateName}.EventStreamQueryGateway`; omit the prefix when there is no context alias. Old QueryService and SnapshotRepository bean aliases are not registered.

## Binding Configuration Values

Factories and public binding strings consistently use the Backend concept and the `*-query-backend-factory` suffix, for example `mongo-snapshot-query-backend-factory` and `elasticsearch-event-stream-query-backend-factory`. Migrate existing route values; no old binding alias is retained.

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
3. Remove every old Mask implementation, Bean, registry, and filter; use [Field Masking](./masking.md) to migrate each rule to `@Mask`, `@KeepMask`, or a custom `@Masking(strategy)` field annotation.
4. Check Schema `masked` metadata; separately verify Snapshot/EventStream typed, dynamic, state-only/aggregate-state load results, and the direct-Backend raw-value boundary.
5. Verify that ordinary filter/search/sort and count remain usable, that a group, field metric, or expression referencing a masked field fails closed, and then check actual MongoDB/Elasticsearch routing, HTTP/OpenAPI, and raw stored values.

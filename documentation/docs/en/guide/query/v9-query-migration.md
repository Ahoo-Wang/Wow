---
title: V9 Query Migration
description: Move historical query implementations to the current aggregate Gateway, recursive Schema, and native Backend.
---

# V9 Query Migration

## Migration boundary

This page retains the V8→V9 Condition/DSL migration notes while updating extension examples to the current implementation. Application `QueryGateway` method contracts remain; old Backend and Schema constructors, filter chains, and validation modes are not implementation compatibility contracts. Recompile downstream extensions after updating them.

The legacy `Condition` compatibility stack remains until 10.0.0 under the agreed migration window. This internal architecture cleanup does not remove it early; deprecation is a migration notice, not unfinished cleanup for this round.

Use `QueryField.path`. Projection/Sort fields still serialize as strings, but public `state.*` wildcard paths are not allowed. Unknown logical fields do not pass through as native names.

Numeric migration preserves singleton-array metrics: direct FIELD inputs and arithmetic leaves both accept exactly one numeric value after ignoring nulls, and duplicates count separately. Native scalar aggregation and finite Double arithmetic do not promise arbitrary-precision algebraic identity; numeric filtering follows native storage precision. See [Aggregation Queries](./aggregation-query.md#numeric-contributions) and [Filter Expressions](./filter-expression.md).

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

`FilterDsl` serializes arbitrary Kotlin objects and maps as JSON objects, which canonical `EQ`/`NE` reject. Scalar and scalar-array equality keeps the DSL form. To preserve a V8 in-process POJO/map equality comparison, construct `EqualFilter` or `NotEqualFilter` explicitly with `QueryField(field)` and `JsonNodeFactory.instance.pojoNode(value)`. `POJONode` and scalar-array equality are available only to JVM construction and legacy `Condition` compatibility; canonical V9 REST filter equality accepts a JSON scalar.

Structured V8 operands for `gt`, `gte`, `lt`, `lte`, or either `between` bound need the same JVM-only treatment. Construct the matching `GreaterThanFilter`, `GreaterThanOrEqualFilter`, `LessThanFilter`, `LessThanOrEqualFilter`, or `BetweenFilter` explicitly and wrap each POJO/map operand with `JsonNodeFactory.instance.pojoNode(value)`. Canonical REST range operands remain non-null JSON scalars.

The same boundary applies to structured elements in `isIn`, `notIn`, and collection `all`: `FilterDsl` converts them to rejected JSON objects. For an in-process native-value collection, construct `InFilter`, `NotInFilter`, or `ContainsAllFilter` explicitly and map every structured element with `JsonNodeFactory.instance.pojoNode(value)`; for example, `InFilter(QueryField(field), values.map(JsonNodeFactory.instance::pojoNode))`. Keep the empty-list branches above. `POJONode` collection elements are JVM-only; canonical REST collections contain non-null JSON scalars.

When V8 passes a `DateTimeFormatter` rather than a pattern string, use the matching relative-time filter class directly with its named `dateFormatter` property, for example `TodayFilter(QueryField(field), dateFormatter = formatter)` or `RecentDaysFilter(QueryField(field), days, dateFormatter = formatter)`. `BeforeTodayFilter` additionally takes `time = localTime.toString()`. `dateFormatter` is JVM-only and ignored on the wire; canonical REST uses `datePattern`.

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
| `match(field, query)` | Non-blank field: `SearchFilter(query, setOf(QueryField(field)), SearchMode.TERMS)`; blank field: `SearchFilter(query)` or `filterExpression { search(query) }` |
| `elemMatch(field, condition)` | `ElementMatchFilter(QueryField(field), predicate)`; combine multiple children with a non-empty `AndFilter`, and map `Condition.ALL` from an empty legacy DSL block to `MatchAllFilter` |
| `isNull`, `notNull`, `isTrue`, `isFalse`, `exists(true)`, `exists(false)` | `IsNullFilter(QueryField(field))`, `IsNotNullFilter(QueryField(field))`, `filterExpression { field eq true }`, `filterExpression { field eq false }`, `ExistsFilter(QueryField(field))`, `NotExistsFilter(QueryField(field))` |
| `today`, `beforeToday`, `tomorrow`, week/month, `recentDays`, `earlierDays` | Matching `TodayFilter`, `BeforeTodayFilter`, `TomorrowFilter`, `ThisWeekFilter`, `NextWeekFilter`, `LastWeekFilter`, `ThisMonthFilter`, `LastMonthFilter`, `RecentDaysFilter`, `EarlierDaysFilter`; use typed constructor properties and the formatter boundary above |
| `condition.toFilterExpression()` | Transitional V9.x adapter only; replace stored/public `Condition` values with their concrete expression before 10.0.0 |

Data-query HTTP request and result envelopes, Backend wire trees, storage layouts, and existing data do not change because of this JVM refactor or static-annotation masking. Query Schema HTTP metadata and its generated OpenAPI component do change: each field adds `masked: Boolean`. No storage-data migration is required, and raw values in the Backend and storage are not rewritten. After old mask rules move to field annotations, the managed Gateway restores response confidentiality semantics.

## Historical types and current replacements

The left column lists removed historical APIs, not current callable contracts:

| Historical type or pattern | Current implementation |
| --- | --- |
| QueryService / SnapshotQueryService / EventStreamQueryService | Application aggregate QueryGateway; storage QueryBackend |
| ResolvedQuery | Explicit `(query, schema)` arguments on every Backend operation |
| QueryFilterChain / around filter | `QueryFilter.prepare(QueryContext<Q>): Mono<Q>` for request preparation only |
| RewriteRequestFilter / HttpQueryGuardFilter | Handler-level QueryRequestScope / HttpQueryGuard |
| AbacQueryFilter | AbacQueryPolicy implementing QueryPolicy |
| SchemaMaskQueryFilter / custom result Mask Filter | Fixed Gateway Mask stage and static domain declarations |
| validation-mode / QuerySchemaValidationMode | Removed; strict validation of final logical requests |
| Flat fields metadata / dynamicChildren | Recursive `QueryModelSchemaMetadata.root` with properties/items/additionalProperties/alternatives |

Any value of the old `wow.query.schema.validation-mode` property, including `strict`, fails startup with an instruction to remove it. CamelCase spellings are rejected too; the setting is not silently ignored.

## Custom QueryBackend migration

All six operations explicitly receive the logical Query and Schema:

```kotlin
fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode>
fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode>
fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>>
fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>>
fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long>
fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode>
```

The Backend consumes native bindings, checks native parameters and physical scope, and executes. It does not fetch a Provider or perform whole-query public validation, authorization, Mask, or typed materialization. The Factory pairs Backend and Provider in `QueryBackendBinding`. Every subscription emits independently owned standard JSON ObjectNodes.

## Request extensions and entry points

`QueryContext<Q>` contains only query, namedAggregate, and schema. Move request processing into prepare; put trusted identity scope in Reactor `withQueryScope` or a Snapshot `QueryPolicy` (including `AbacQueryPolicy`). Observers only observe termination. The Gateway fixes the sequence: prepare, scope/policy, defaults, public validation, Backend, Mask, and typed materialization.

Applications keep using typed, dynamic, paged, cursor, count, and aggregate methods on SnapshotQueryGateway / EventStreamQueryGateway. Direct Backend access is a trusted low-level boundary; callers supply the Schema and own all governance responsibilities. The Gateway appends the cursor's unique sort field; the Backend does not.

## Static Mask migration

Move rules to `@Mask`, `@KeepMask`, or custom `@Masking` annotations instead of restoring historical registries or result Filters. Current recursive value metadata exposes masked markers and public capabilities, not strategies or native paths. See [Field Masking](./masking.md) for value-domain, union, and alias boundaries.

## Minimal migration steps

1. Keep declared logical paths; remove reliance on unknown-field passthrough and native aliases.
2. Update Backend signatures and Factory bindings; declare actual arrays, Maps, and union branches.
3. Separate prepare, scope, Snapshot policy, and Observer handling, using the default Gateway's fixed sequence.
4. Verify queries, collection/element scopes, cursors, aggregation, metadata, and Mask failures, then validate actual storage behavior.

See [Query Gateway](./query-gateway.md), [Query Backend](./query-backend.md), and [Query Model Schema](./query-model-schema.md) for current extension contracts.

The `policies` parameter of `DefaultSnapshotQueryGateway` and the Spring registrar now use `QueryPolicy`. Existing `AbacQueryPolicy` classes implement it; other access policies implement `resolveFilter(ContextView, QueryContext<*>): Mono<FilterExpression>` directly. The fixed authorization stage, captured identity, AND composition and empty-publisher rejection remain unchanged.

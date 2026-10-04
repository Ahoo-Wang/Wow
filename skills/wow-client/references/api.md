# Wow Client API Reference

What an agent needs to write correct `@ahoo-wang/wow-client` and `@ahoo-wang/wow-react` code: names, signatures and the gotchas. The full reference is https://wow.ahoo.me/reference/typescript/wow-client/ (commands, snapshot queries, events and history, filters, aggregations, cursor queries, errors) and https://wow.ahoo.me/reference/typescript/wow-react/ ; the task guides are under https://wow.ahoo.me/guide/typescript/ . Sources: `typescript/wow-client/src` and `typescript/wow-react/src` in the Wow repository.

## Contents

- [Names and entry points](#names-and-entry-points)
- [Constructors and paths](#constructors-and-paths)
- [CommandClient](#commandclient)
- [Errors](#errors)
- [Query clients](#query-clients)
- [QueryClientFactory](#queryclientfactory)
- [Cursor queries](#cursor-queries)
- [Filter expressions](#filter-expressions)
- [Aggregation](#aggregation)
- [Legacy Condition API](#legacy-condition-api)
- [Key types](#key-types)
- [Generated clients](#generated-clients)
- [React query hooks](#react-query-hooks)
- [Complete flow](#complete-flow)

## Names and entry points

```typescript
import { HttpMethod } from '@ahoo-wang/fetcher';
import {
  CommandClient, CommandHeaders, CommandStage, commandHeaders, waitStrategy, WowHeaders,
  ErrorCodes, WowError, toWowError, isErrorInfo,
  SnapshotQueryClient, EventStreamQueryClient, QueryClientFactory, LoadStateAggregateClient,
  LoadOwnerStateAggregateClient, WowMetadataClient, ResourceAttributionPathSpec,
  // Query DSL (also exported by '@ahoo-wang/wow-client/dsl')
  SortDirection, aggregation, AGGREGATION_LIMITS, AggregationGroupType, AggregationMetricType,
  AggregationExpressionType, AggregationExpressionOperator, AggregationDateUnit, AggregationFunction,
  ComparisonOperator, DerivedExpressionType, HavingExpressionType,
  DeletionState, FilterOperator, SearchMode, StringComparison, TimeUnit, filter,
  listQuery, pagedQuery, singleQuery, DEFAULT_CURSOR_SIZE, MAX_CURSOR_SIZE, MAX_CURSOR_SORT_FIELDS,
  cursorQuery, asc, desc,
  type CommandRequest, type CommandRequestHeaders, type CommandResult, type CommandBody,
  type ErrorCode, type ErrorInfo, type MaterializedSnapshot, type PagedList, type FilterExpression,
  type QueryField, type ElementFilterExpression, type MetadataFilter, type EqualityFilterValue,
  type SearchFilterOptions, type RelativeTimeFilterOptions, type FilterListQuery, type FilterPagedQuery,
  type FilterSingleQuery, type CursorQuery, type CursorPage, type AggregationQuery, type AggregationElement,
  type AggregationGroup, type AggregationMetric, type AggregationMetricOptions, type TermsAggregationOptions,
  type HistogramAggregationOptions, type DateHistogramAggregationOptions, type PercentileAggregationOptions,
  type DynamicDocument,
} from '@ahoo-wang/wow-client';

// Deprecated Condition API, only for Wow 8.10 servers; removed in v10.
import {
  and, or, nor, raw, eq, ne, gt, lt, gte, lte, between, contains, startsWith, endsWith, match,
  isIn, notIn, allIn, elemMatch, isNull, notNull, isTrue, isFalse, exists,
  today, beforeToday, tomorrow, thisWeek, nextWeek, lastWeek, thisMonth, lastMonth, recentDays, earlierDays,
  active, all, deleted, spaceId, id, ids, aggregateId, aggregateIds, tenantId, ownerId,
  Operator, en_US, zh_CN,
  type Condition, type ListQuery, type PagedQuery, type SingleQuery,
  type ListQueryRequest, type PagedQueryRequest, type SingleQueryRequest,
} from '@ahoo-wang/wow-client/legacy';
```

| Import | Contents |
|---|---|
| `@ahoo-wang/wow-client` | Everything except `/legacy`: clients, `QueryClientFactory`, `WowMetadataClient`, errors, headers and their builders, the stream endpoint presets `QUERY_STREAM_ENDPOINT` / `COMMAND_STREAM_ENDPOINT`, and the query DSL. |
| `@ahoo-wang/wow-client/dsl` | The query DSL alone (`filter`, `aggregation`, sort, projection, pagination, `cursorQuery`, the query factories and `Filter*` types, `DeletionState`, `DynamicDocument`, `SnapshotMetadataFields`, `DomainEventStreamMetadataFields`) with no HTTP code: no Fetcher, decorators, `reflect-metadata` or event-stream patches. Use it in code that only builds queries. |
| `@ahoo-wang/wow-client/legacy` | The deprecated `Condition` API for Wow 8.10 servers. Its `listQuery`, `pagedQuery` and `singleQuery` build `Condition` queries; import them under another name if a file needs both. Removed in v10. |

The package ships ESM (`dist/index.es.js`) and CommonJS (`require('@ahoo-wang/wow-client')`, `dist/index.cjs`). Types such as `FilterExpression` and `PagedList` are TypeScript-only; use `import type`.

## Constructors and paths

Every client takes `constructor(apiMetadata?: ApiMetadata)` (`ApiMetadata` from `@ahoo-wang/fetcher-decorator`); a plain `{ fetcher, basePath }` object works.

```typescript
const commandClient = new CommandClient({ fetcher, basePath: 'owner/{ownerId}/cart' });
await commandClient.send({
  path: 'items',
  urlParams: { path: { ownerId: 'owner-123' } }, // → POST {baseURL}/owner/owner-123/cart/items
  body: { productId: 'p-1', quantity: 2 },
});
```

- Templates in `basePath` and `path` are filled from `urlParams.path` per request; an unbound placeholder is left as-is and logs a warning. CoSec's resource-attribution interceptor (`@ahoo-wang/fetcher-cosec`) can fill `{tenantId}`/`{ownerId}` from the JWT instead.
- Space-scoped aggregates: `commandHeaders({ spaceId })` sets `CommandHeaders.SPACE_ID` (`Wow-Space-Id`, same value as `WowHeaders.SPACE_ID`); `filter.spaceId(value)` filters queries; `MaterializedSnapshot` exposes `spaceId`.

## CommandClient

Not generic: the body type is chosen per call, so one client sends every command of an aggregate.

- `send<C>(commandRequest, attributes?)` resolves to the `CommandResult` of the stage it waited for (`PROCESSED` unless the headers say otherwise); a resolved result always has `errorCode` `'Ok'`. A refused or failed command rejects: Wow answers the failed `CommandResult` (an `ErrorInfo`) with the status its `errorCode` maps to — 400 `IllegalArgument` for a handler that throws, 400 `CommandValidation`, 409 for version conflicts, 408 `RequestTimeout` when the wait stage does not arrive.
- `sendAndWaitStream<C>(commandRequest, attributes?)` resolves to a `CommandResultEventStream`, a `ReadableStream<CommandResult>` of the results themselves (not SSE envelopes), one per stage reached; it sets `Accept: text/event-stream` itself. A server failure midway errors the stream with a `WowError` (the `for await` throws); a result whose own `errorCode` is not `Ok` is still delivered as a result. A generated or hand-written client gets the same with the preset `COMMAND_STREAM_ENDPOINT`, which sets `Accept: text/event-stream` and the command-result extractor together (`@api('…', COMMAND_STREAM_ENDPOINT)` for every endpoint of a class, `@post(path, COMMAND_STREAM_ENDPOINT)` for one).

```typescript
const result: CommandResult = await commandClient.send<AddCartItem>({
  path: 'add_cart_item',
  method: HttpMethod.POST,
  urlParams: { path: { ownerId: 'owner-123' } },
  headers: {
    ...commandHeaders({ requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
  },
  body: { productId: 'product-123', quantity: 2 },
});
```

**Stages** (`CommandStage`): `SENT` (published to the bus), `PROCESSED` (aggregate handled it), `SNAPSHOT`, `PROJECTED`, `EVENT_HANDLED`, `SAGA_HANDLED`.

**Headers.** `CommandHeaders` and `WowHeaders` are frozen `as const` objects of header names: `TENANT_ID` (`Command-Tenant-Id`), `OWNER_ID` (`Command-Owner-Id`), `SPACE_ID` (`Wow-Space-Id`), `AGGREGATE_ID` (`Command-Aggregate-Id`), `AGGREGATE_VERSION` (`Command-Aggregate-Version`, the expected version), `WAIT_STAGE` (`Command-Wait-Stage`; `PROCESSED` when absent), `WAIT_TIME_OUT` (`Command-Wait-Timeout`, ms), `WAIT_CONTEXT` / `WAIT_PROCESSOR` / `WAIT_FUNCTION` (`Command-Wait-Context`, `-Processor`, `-Function`: what the wait stage refers to), `WAIT_TAIL_STAGE` / `WAIT_TAIL_CONTEXT` / `WAIT_TAIL_PROCESSOR` / `WAIT_TAIL_FUNCTION` (`Command-Wait-Tail-Stage`, `-Context`, `-Processor`, `-Function`; only with `SAGA_HANDLED`), `REQUEST_ID` (`Command-Request-Id`, idempotency), `LOCAL_FIRST` (`Command-Local-First`), `COMMAND_AGGREGATE_CONTEXT` / `COMMAND_AGGREGATE_NAME` / `COMMAND_TYPE` (for the generic `/wow/command/send` route, which has no dedicated method: send to it with `CommandClient` and these headers), `COMMAND_HEADER_X_PREFIX` (`Command-Header-`, copied into the command header); `WowHeaders.ERROR_CODE` is the `Wow-Error-Code` response header.

`CommandRequestHeaders` makes every header optional and types it by what the server parses: wait stages are `CommandStageName`, version and timeout are `` `${number}` ``, `Command-Local-First` is `'true' | 'false'`; other headers (`Authorization`, `Command-Header-*`) stay strings. Build them with the helpers, which omit what you leave out and throw `TypeError` on invalid input:

```typescript
// aggregateVersion: a non-negative integer, else TypeError.
commandHeaders({ tenantId, ownerId, spaceId, aggregateId, aggregateVersion: 3, requestId, localFirst: true });
waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 5_000 }); // timeoutMs: positive integer
// Wait chain: a saga handles the events, then the command it sends reaches tail.stage.
// A tail with a stage other than SAGA_HANDLED is a type error and throws.
waitStrategy({ stage: CommandStage.SAGA_HANDLED, processor: 'TransferSaga', tail: { stage: CommandStage.PROCESSED, context: 'account' } });
```

Option types: `CommandHeaderOptions`, `WaitFunction` (`context`, `processor`, `function`), `WaitStageOptions`, `WaitChainOptions`, `WaitStrategyOptions`. `CommandRequest<C>` extends the fetcher's `ParameterRequest` with `urlParams?`, `headers?: CommandRequestHeaders` and `body?: CommandBody<C>` (`RemoveReadonlyFields<C>`). `CommandResult` key fields: `id`, `waitCommandId`, `stage`, `contextName`, `aggregateName`, `aggregateId`, `aggregateVersion?`, `commandId`, `requestId`, `errorCode`, `errorMsg`, `bindingErrors?`, `signalTime`, `result`.

## Errors

A call fails in one of three ways:

- **The server refuses the request** (HTTP 4xx/5xx), including a command whose handler failed: the Promise rejects with the fetcher's `ExchangeError`, never resolves with a failure. `await toWowError(error)` returns a `WowError` read from the `ErrorInfo` body (through a clone) or the `Wow-Error-Code` header.
- **A server-sent event stream fails midway**: Wow answers 200 and sends a last event named after the error code. Every built-in stream (`listStream`, `listStateStream`, `aggregateStream`, event `loadStream`, `sendAndWaitStream`) then errors with a `WowError`, so `for await` throws.
- **Wow never answered** (network, timeout, abort, a proxy's error page): `toWowError` returns `undefined`; handle the original error. Only this case may resend a command, with the same `requestId`; never resend after `RequestTimeout` or `DuplicateRequestId` (see the retry rule below).

```typescript
try {
  return await snapshotClient.getStateById(id);
} catch (error) {
  const wowError = await toWowError(error);
  if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
  throw wowError ?? error;
}
```

- `WowError extends Error`: `name = 'WowError'`, `errorCode: ErrorCode`, `errorMsg` (empty when absent), `bindingErrors` (`{ name, msg }[]`, empty when absent; map them to form fields on `COMMAND_VALIDATION`), optional `status` and `cause`; construct one with `new WowError(errorInfo, { status?, cause? })`. `isErrorInfo(value)` is the `ErrorInfo` type guard.
- `ErrorCodes` is a frozen object: `SUCCEEDED` (`'Ok'`), `NOT_FOUND`, `BAD_REQUEST`, `ILLEGAL_ARGUMENT`, `ILLEGAL_STATE`, `REQUEST_TIMEOUT`, `TOO_MANY_REQUESTS`, `DUPLICATE_REQUEST_ID`, `COMMAND_VALIDATION`, `REWRITE_NO_COMMAND`, `EVENT_VERSION_CONFLICT`, `DUPLICATE_AGGREGATE_ID`, `COMMAND_EXPECT_VERSION_CONFLICT`, `SOURCING_VERSION_CONFLICT`, `ILLEGAL_ACCESS_DELETED_AGGREGATE`, `ILLEGAL_ACCESS_OWNER_AGGREGATE`, `ILLEGAL_ACCESS_SPACE_AGGREGATE`, `INTERNAL_SERVER_ERROR`, `QUERY_SCHEMA_VALIDATION`, `QUERY_SCHEMA_CONFLICT`, `QUERY_SCHEMA_UNAVAILABLE`, `BATCH_TASK_ERROR`. There is no `isSucceeded`/`isError`: compare `errorCode === ErrorCodes.SUCCEEDED`. `WowErrorCode` is the union; `ErrorCode = WowErrorCode | (string & {})` admits application codes.
- `RecoverableType`: `RECOVERABLE`, `UNRECOVERABLE`, `UNKNOWN`.
- **Retrying a command**: resend only when Wow never answered, and with the same `requestId` (the server applies a request id once and answers a repeat with `DuplicateRequestId`). Do not resend after `RequestTimeout` (the command may still complete) or `DuplicateRequestId`; read the state instead. Guide: https://wow.ahoo.me/guide/typescript/error-handling.html.

## Query clients

`SnapshotQueryClient<S, FIELDS>` (materialized snapshots) methods, each ending with `attributes?` then `abort?`:

| Method | Returns |
|---|---|
| `count(filter)` | `number` |
| `list(query)` / `listState(query)` | `MaterializedSnapshot<S>[]` / `S[]` |
| `listStream(query)` / `listStateStream(query)` | stream yielding each snapshot / state itself |
| `paged(query)` / `pagedState(query)` | `PagedList<MaterializedSnapshot<S>>` / `PagedList<S>` |
| `cursor(query)` / `cursorState(query)` | `CursorPage<…>` (`{ list, nextCursor: string \| null }`) |
| `single(query)` / `singleState(query)` | one snapshot / state |
| `getById(id)`, `getStateById(id)`, `getByIds(ids)`, `getStateByIds(ids)` | by aggregate id |
| `aggregate<Row>(query)` / `aggregateStream<Row>(query)` | `Row[]` / `ReadableStream<Row>` (`Row` defaults to `DynamicDocument`) |

- `sort` entries are `{ field, direction: SortDirection.DESC }` (`'ASC' | 'DESC'`) or `desc('eventTime')`/`asc(...)`.
- `QueryApi` requires `aggregate`, `aggregateStream` and `cursor`; `SnapshotQueryApi` also `cursorState`. Snapshot and event clients submit aggregation to `snapshot/aggregation` and `event/aggregation`; `aggregateStream` requests SSE and yields each row.
- **Cancellation**: the last parameter of every query and load method (including `WowMetadataClient.metadata`) is `abort?: AbortController | AbortSignal`; the second stays request attributes. It is cooperative: also ignore responses from superseded requests.

```typescript
const page = await snapshotClient.paged(query, undefined, AbortSignal.timeout(10_000));
useQuery({ queryKey: ['carts', query], queryFn: ({ signal }) => snapshotClient.pagedState(query, undefined, signal) });
```

`EventStreamQueryClient<DomainEventBody, FIELDS>`: `count`, `list`, `listStream`, `paged`, `cursor`, `aggregate`, `aggregateStream`, plus `load(id, headVersion, tailVersion, attributes?, abort?)` — replays one aggregate's event streams from `headVersion` (from 1) to `tailVersion`, both inclusive (`GET {id}/event/{headVersion}/{tailVersion}`); `loadStream(…)` is the same route as SSE. The server treats the range as a list query and refuses more than its maximum list size (1000 by default). The route carries a tenant segment by default but no owner segment. Event aggregation expands the event array at `body`: event fields are then relative to that element and payload fields sit below its `body` (`aggregation.element('body')`, `aggregation.terms('name', 'eventType')`).

`GET {id}/snapshot` and `GET {id}/state/tracing` have no client method; call them through a Fetcher.

`LoadStateAggregateClient<S>`: `load(id)`, `loadVersioned(id, version)`, `loadTimeBased(id, time)`. `LoadOwnerStateAggregateClient<S>` (owner from the path, no id): `load()`, `loadVersioned(version)`, `loadTimeBased(time)`.

`WowMetadataClient`: `new WowMetadataClient({ fetcher }).metadata()` reads `GET /wow/metadata` (bounded contexts, aggregates, commands, events) relative to the fetcher's base URL.

## QueryClientFactory

```typescript
const factory = new QueryClientFactory({ fetcher, contextAlias: 'example', aggregateName: 'cart', resourceAttribution: ResourceAttributionPathSpec.OWNER });
const snapshotClient = factory.createSnapshotQueryClient();
const eventClient = factory.createEventStreamQueryClient(); // <EVENT_FIELDS> typed separately from FIELDS
const stateClient = factory.createLoadStateAggregateClient();
const ownerStateClient = factory.createLoadOwnerStateAggregateClient();
```

- `ResourceAttributionPathSpec`: `NONE`, `TENANT` (`/tenant/{tenantId}`), `OWNER` (`/owner/{ownerId}`), `TENANT_OWNER` (`/tenant/{tenantId}/owner/{ownerId}`).
- An explicit `basePath` (factory default or per client) wins over the path assembled from context, attribution and aggregate name. Per-client `basePath` overrides only when not nullish (`undefined` keeps the factory path); `''` is an explicit override. Route composition is internal (no `createQueryApiMetadata`, no `*EndpointPaths`); read `apiMetadata.basePath` if you need it.

## Cursor queries

Forward-only, no total. Pass the opaque `nextCursor` unchanged; `nextCursor === null` ends the traversal.

```typescript
let query = cursorQuery({ filter: filter.eq('state.status', 'PAID'), sort: [asc('state.createdAt')], size: 100 });
const first: CursorPage<MaterializedSnapshot<CartState>> = await snapshotClient.cursor(query);
if (first.nextCursor) query = cursorQuery({ ...query, cursor: first.nextCursor });
```

`DEFAULT_CURSOR_SIZE` is 10; `size` is 1..`MAX_CURSOR_SIZE` (`2147483646`), but over HTTP the server's page limit (100 by default) answers a larger size with 400. At most `MAX_CURSOR_SORT_FIELDS` (32) sort fields, each once: `cursorQuery` throws `TypeError('Cursor sort fields must be unique.')` for a repeat. Routes: `snapshot/cursor`, `snapshot/cursor/state`, `event/cursor`. Reference: https://wow.ahoo.me/reference/typescript/wow-client/cursor-queries.html.

## Filter expressions

Wow 8.11+ uses a discriminated `FilterExpression` (`op` on the wire), built under `filter`:

```typescript
const expression = filter.and([
  filter.deletion(DeletionState.ACTIVE),
  filter.eq('state.status', 'PAID'),
  filter.elementMatch('state.items', filter.gt('quantity', 0)),
  filter.search('event sourcing', { mode: SearchMode.PHRASE, fields: ['state.title'] }),
  filter.yesterday('state.createdAt', { zoneId: 'Asia/Shanghai', timeUnit: TimeUnit.MILLISECONDS }),
]);
await snapshotClient.list({ filter: expression, limit: 10 });
const query = listQuery({ filter: expression }); // factories infer FilterListQuery
```

- Builders. Logical: `matchAll`, `matchNone`, `and`, `or`, `nor`. Metadata: `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId`, `spaceId`. Comparison: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`. String: `contains`, `startsWith`, `endsWith` (third argument `StringComparison`). Collection: `isIn`, `notIn`, `containsAll`. Presence: `isEmpty`, `isEmptyString`, `isNotEmptyString`, `isNull`, `isNotNull`, `exists`, `notExists`. Scope/search: `deletion`, `elementMatch`, `search(query, options?)`. Relative time: `today`, `beforeToday(field, time, options?)`, `tomorrow`, `thisWeek`, `nextWeek`, `lastWeek`, `thisMonth`, `lastMonth`, `yesterday`, `nextMonth`, `lastYear`, `thisYear`, `nextYear`, `recentDays(field, days, options?)`, `earlierDays(field, days, options?)`; against the server's now (Wow 9.2.0+): `beforeNow(field, offset?, options?)`, `afterNow(field, offset?, options?)` with an ISO-8601 `offset` (default `PT0S`).
- `and`, `or`, `nor`, `ids`, `aggregateIds`, `isIn`, `notIn` and `containsAll` take one non-empty `readonly` array and throw `TypeError` for an empty one. `eq`/`ne` take a JSON scalar or `null`; use `isIn`/`notIn` for several values.
- Every field path — in `filter.*`, `asc`, `desc` and `projection` — must match Wow's `QueryField` pattern `^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$` (segments may start with `@`; numeric segments index arrays), else `TypeError('Query field is invalid: [<path>].')`. `projection()` always returns both `include` and `exclude`.
- `isEmptyString` matches exactly `""`; `isNotEmptyString` requires the field to exist, be non-null and differ from `""`. Whitespace-only is not empty.
- `RelativeTimeFilterOptions`: `zoneId`, `datePattern` (JVM `ZoneId`/`DateTimeFormatter`), `timeUnit` (default `TimeUnit.MILLISECONDS`). `days` is a positive JVM `Int`. `SearchFilterOptions`: `fields?`, `mode?` (default `SearchMode.TERMS`).
- `listQuery({ filter })` sends no `limit` unless given: the server applies its default list size (100) and refuses more than its maximum (1000). The legacy `listQuery({ condition })` keeps its old default page size.
- `elementMatch` takes an `ElementFilterExpression`: relative fields independent of the outer query, no metadata filters, `deletion` or `search`, even nested in `and`/`or`/`nor`.
- `SingleQueryRequest`, `ListQueryRequest`, `PagedQueryRequest` and `count` also accept the condition-based forms during the compatibility window.

Reference: https://wow.ahoo.me/reference/typescript/wow-client/filters.html.

## Aggregation

`AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS = ROOT_FIELDS>`: `filter?`, `elements?: AggregationElement[]` (`path`, optional `filter: ElementFilterExpression`), `groupBy?`, `metrics` (non-empty tuple), `sort?`, `limit?`, `having?`. Elements form an ordered expansion chain: the first path is root-relative; later element paths, group fields and metric fields are relative to the innermost element.

```typescript
aggregation.element(path, predicate?); aggregation.field(field); aggregation.constant(value);
aggregation.add(l, r); aggregation.subtract(l, r); aggregation.multiply(l, r); aggregation.divide(l, r);
// Every group and metric: target first, alias second, options last. No option type carries the alias.
aggregation.terms(field, alias, { missingKey? }?);
aggregation.histogram(field, alias, { interval });
aggregation.dateHistogram(field, alias, { unit, timeZone?, dense? }); // timeZone defaults to UTC
aggregation.any(field, alias, { filter? }?);
aggregation.count(alias, { filter? }?);
aggregation.sum | avg | min | max | stddev | variance | distinctCount(expression, alias, { filter? }?);
aggregation.percentile(expression, alias, { percentile, filter? });
aggregation.derived(expression, alias);
aggregation.query(query); // admits the assembled query, returns it
```

- Groups: `TERMS` (`missingKey` merges missing/null into a nonblank string key), `HISTOGRAM` (`interval`), `DATE_HISTOGRAM` (`unit`: `AggregationDateUnit` `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, `SECOND`; `dense: true` fills interior gaps and must be the only group; no rows means no generated range). Metrics: `COUNT`, `NUMERIC` (`AggregationFunction` `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV`, `VARIANCE` — population statistics), `ANY`, `DISTINCT_COUNT`, `PERCENTILE` (finite, strictly between 0 and 100; 50 is the median), `DERIVED`. Expressions: `FIELD`, `CONSTANT`, `BINARY` with `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`.
- A non-derived metric's `{ filter }` is a whole-value predicate on one record in the current scope and affects only that metric; `SEARCH` and `ELEMENT_MATCH` are rejected there, and the backend rejects array-valued fields.
- `ANY` is a metric, not a group: one non-null scalar from the group (or `null`), field relative to the innermost element, needing `AGGREGATE_TERMS` and no collection cardinality; which value is unspecified (MongoDB `$max`, Elasticsearch one-bucket `terms`). Sorting by an `ANY` alias is an expensive metric sort.
- `derived` takes a `DerivedExpression` tree (`METRIC_REF`, `CONSTANT`, `BINARY`) referencing metrics declared strictly before it, never `ANY`; it has no record filter. Null operands or division by zero give null.
- `having` (`HavingExpressionType` `CONDITION`, `BETWEEN`, `IN`, `IS_NULL`, `AND`, `OR`; `ComparisonOperator` `EQ`…`LTE`) uses metric aliases, requires a `groupBy`, cannot reference `ANY`, and runs before sort and limit.
- `aggregation.query(query)` throws `TypeError` for: the `elements`/`groupBy`/`metrics`/`sort` ceilings; a `limit` outside 1..10000; colliding aliases; a `sort` field that is not an alias, is repeated, or has no `groupBy`; an effective sort over the ceiling; `dense` beside another dimension; a bad `having`; expression depth over 8 or more than 256 nodes across all metrics; a bad derived reference; a non-finite constant; `SEARCH`/`ELEMENT_MATCH` in a metric filter. Raw expression objects are not validated until passed through it.
- `AGGREGATION_LIMITS`: `DEFAULT_LIMIT` 100, `MAX_LIMIT` 10000, `MAX_ELEMENTS` 5, `MAX_GROUPS` 32, `MAX_METRICS` 64, `MAX_SORT_FIELDS` 32, `MAX_EXPRESSION_DEPTH` 8, `MAX_EXPRESSION_NODES` 256. Wow applies the requested sort, then each remaining group ascending.
- The server keeps what needs a schema (field capabilities, cardinality, array-valued metric filters). Percentiles are approximate on both backends; Elasticsearch distinct counts may be approximate, MongoDB's are exact. MongoDB needs 5.1+ for dense groups and 7.0+ for percentiles; the client probes nothing. `Row` types the result rows only; there is no runtime decoding.

```typescript
const query: AggregationQuery = {
  groupBy: [aggregation.terms('state.status', 'status', { missingKey: 'Unknown' })],
  metrics: [
    aggregation.count('orders'),
    aggregation.sum(aggregation.field('state.amount'), 'revenue'),
    aggregation.derived({
      type: DerivedExpressionType.BINARY, operator: AggregationExpressionOperator.DIVIDE,
      left: { type: DerivedExpressionType.METRIC_REF, metric: 'revenue' },
      right: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
    }, 'averageOrderValue'),
  ],
  having: { type: HavingExpressionType.CONDITION, metric: 'averageOrderValue', operator: ComparisonOperator.GTE, value: 100 },
};
```

Reference: https://wow.ahoo.me/reference/typescript/wow-client/aggregations.html.

## Legacy Condition API

The whole `Condition` API (`Condition`, `ConditionCapable`, `Operator`, builders, operator locales) is deprecated, exported only by `@ahoo-wang/wow-client/legacy`, and removed in v10. Use it only against a Wow 8.10 server, which understands only the Condition model; servers from 8.11 on refuse `raw()`. The collection builders are variadic (`isIn('status', 'active', 'pending')`); `active()` is `deleted(DeletionState.ACTIVE)`, and `all()` is `{ operator: Operator.ALL }`, not tied to deletion state.

```typescript
import { and, eq, listQuery, ownerId } from '@ahoo-wang/wow-client/legacy';

const carts = await snapshotClient.listState(listQuery({ condition: and(ownerId('u-42'), eq('state.status', 'ACTIVE')) }));
```

## Key types

- `MaterializedSnapshot<S>`: `state`, `contextName`, `aggregateName`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, `eventId`, `firstEventTime`, `eventTime`, `snapshotTime`, `firstOperator`, `operator`, `tags`, `deleted`. `MediumMaterializedSnapshot` and `SmallMaterializedSnapshot` have no `contextName`/`aggregateName`; Small keeps only `state`, `version` and `firstEventTime`.
- `PagedList<T>`: `{ total, list }`. `Pagination`: `{ index, size }` (index from 1). `CommandBody<C> = RemoveReadonlyFields<C>`.

## Generated clients

```typescript
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import { CartCommandClient, CartStreamCommandClient, cartQueryClientFactory } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080/' });
const cartCommandClient = new CartCommandClient({ fetcher }); // keeps the `example` bounded-context prefix
const directCommandClient = new CartCommandClient({ fetcher, basePath: '' }); // no gateway
await cartCommandClient.addCartItem({ method: HttpMethod.POST, body: { productId: 'prod-1', quantity: 1 } });
const stream = await new CartStreamCommandClient({ fetcher }).addCartItem({ method: HttpMethod.POST, body: { productId: 'prod-1', quantity: 1 } });
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({ fetcher });
const directSnapshotClient = cartQueryClientFactory.createSnapshotQueryClient({ fetcher, contextAlias: '' });
```

Owner- and tenant-scoped paths need `{ownerId}`/`{tenantId}` from CoSec or `urlParams: { path: { ownerId } }`. Generated `…StreamCommandClient` classes take `COMMAND_STREAM_ENDPOINT`, so they behave like `sendAndWaitStream`: a server error that ends the stream (a wait timeout, a failed validation) errors it with a `WowError` and the `for await` throws, while a result whose own `errorCode` is not `Ok` is still a result. How the generator names and shapes them: `generator.md`.

## React query hooks

`@ahoo-wang/wow-react` (peers `react` 19.0+, `@ahoo-wang/fetcher`, `@ahoo-wang/wow-client`) runs its own request state machine and does not depend on `@ahoo-wang/fetcher-react`. Reference: https://wow.ahoo.me/reference/typescript/wow-react/.

- Hooks wrapping an `execute` you supply: `useSingleQuery`, `useListQuery`, `usePagedQuery`, `useCountQuery`, `useListStreamQuery`. Fetcher-bound variants that issue the request: `useFetcherSingleQuery`, `useFetcherListQuery`, `useFetcherPagedQuery`, `useFetcherCountQuery`, `useFetcherListStreamQuery`.
- Import them from `@ahoo-wang/wow-react`, not the `@ahoo-wang/fetcher-react` root: mixing both leaves two sets of same-named hooks and two copies of the Wow types. An application that keeps `@ahoo-wang/fetcher-react` for other hooks upgrades it to 5.1.5 or later.
- Every `Use…Options` extends `QueryHookOptions` (`query`, `initialQuery`, `autoExecute`, `attributes`, `execute`, `onSuccess`, `onError`; `useFetcher*` take `url` and `fetcher` instead of `execute`); every `Use…Return` extends `QueryHookReturn` (`status`, `loading`, `result`, `error`, `execute`, `abort`, `reset`, `getQuery`, `setQuery`; list streams return `items` and `done` instead of `result`). `status` is `QueryStatus` (`'idle' | 'loading' | 'success' | 'error'`): compare with literals, do not import `PromiseStatus`. `E` defaults to `Error`: pass `FetcherError` as `E` or narrow with `instanceof` before reading `error.exchange`. There is no `initialStatus`, `propagateError`, `onAbort` or `resultExtractor`.
- An error and `abort()` keep the last `result`; `reset()` aborts the request in flight and clears `result` and `error`; the first frame of a hook that runs on mount is `loading`, on the server too. A `useFetcher*` hook runs again when `url` or the Fetcher's name (or `baseURL` when unnamed) changes.
- With a generated client pass its fields type as the second type argument (`usePagedQuery<CartState, CartFields>(…)`): TypeScript does not infer `FIELDS` from `execute` once `R` is written.

```typescript
import { filter, pagedQuery } from '@ahoo-wang/wow-client';
import { usePagedQuery } from '@ahoo-wang/wow-react';

const { result, loading, error, setQuery } = usePagedQuery<CartState>({
  initialQuery: pagedQuery({ filter: filter.eq('state.status', 'PAID'), pagination: { index: 1, size: 20 } }),
  execute: (query, attributes, controller) => snapshotClient.pagedState(query, attributes, controller),
});
```

## Complete flow

```typescript
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import { CommandClient, CommandStage, SnapshotQueryClient, filter, toWowError, waitStrategy } from '@ahoo-wang/wow-client';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080/' });
const commandClient = new CommandClient({ fetcher, basePath: 'owner/{ownerId}/cart' });
const snapshotClient = new SnapshotQueryClient({ fetcher, basePath: 'owner/{ownerId}/cart' });

const result = await commandClient
  .send<{ productId: string; quantity: number }>({
    path: 'add_cart_item',
    method: HttpMethod.POST,
    urlParams: { path: { ownerId: 'owner-123' } },
    headers: waitStrategy({ stage: CommandStage.SNAPSHOT }),
    body: { productId: 'prod-123', quantity: 2 },
  })
  .catch(async (error) => {
    throw (await toWowError(error)) ?? error; // refused or failed: errorCode, status, bindingErrors
  });

const cart = await snapshotClient.getStateById(result.aggregateId, undefined, AbortSignal.timeout(5_000));
const stream = await snapshotClient.listStateStream({ filter: filter.aggregateId(result.aggregateId) });
for await (const state of stream) console.log('Cart:', state); // a failure midway throws a WowError here
```

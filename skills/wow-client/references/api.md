# Wow Client API Reference

## Contents

- [Installation](#installation)
- [Core Concepts](#core-concepts)
- [Package Imports](#package-imports)
  - [Entry Points](#entry-points)
- [CommonJS](#commonjs)
- [Constructors (All Use ApiMetadata)](#constructors-all-use-apimetadata)
- [CommandClient](#commandclient)
  - [Setup](#setup)
  - [send<C>(commandRequest, attributes?)](#sendccommandrequest-attributes)
  - [sendAndWaitStream<C>(commandRequest, attributes?)](#sendandwaitstreamccommandrequest-attributes)
  - [CommandStage Values](#commandstage-values)
  - [Command Headers](#command-headers)
  - [CommandRequest<C>](#commandrequestc)
  - [CommandResult Fields](#commandresult-fields)
- [Errors](#errors)
- [SnapshotQueryClient<S, FIELDS>](#snapshotqueryclients-fields)
  - [Setup](#setup-1)
  - [Query Methods](#query-methods)
  - [Aggregation Methods](#aggregation-methods)
  - [Query cancellation](#query-cancellation)
  - [ID-Based Lookup Methods](#id-based-lookup-methods)
- [EventStreamQueryClient<DomainEventBody, FIELDS>](#eventstreamqueryclientdomaineventbody-fields)
  - [Setup](#setup-2)
  - [Methods](#methods)
- [LoadStateAggregateClient<S>](#loadstateaggregateclients)
- [LoadOwnerStateAggregateClient<S>](#loadownerstateaggregateclients)
- [QueryClientFactory<S, FIELDS, DomainEventBody>](#queryclientfactorys-fields-domaineventbody)
  - [Setup](#setup-3)
  - [ResourceAttributionPathSpec](#resourceattributionpathspec)
  - [Factory Methods](#factory-methods)
- [WowMetadataClient](#wowmetadataclient)
- [Cursor Queries](#cursor-queries)
- [Filter Expressions](#filter-expressions)
- [AggregationQuery](#aggregationquery)
- [Query DSL Conditions (Deprecated)](#query-dsl-conditions-deprecated)
  - [Comparison Operators](#comparison-operators)
  - [String Operators](#string-operators)
  - [Collection Operators](#collection-operators)
  - [Null/Boolean Operators](#nullboolean-operators)
  - [Date Operators](#date-operators)
  - [ID Operators](#id-operators)
  - [State Operators](#state-operators)
  - [Logical Operators](#logical-operators)
- [Key Types](#key-types)
  - [MaterializedSnapshot<S>](#materializedsnapshots)
  - [PagedList<T>](#pagedlistt)
  - [CommandBody<C>](#commandbodyc)
  - [Pagination](#pagination)
- [Generated Clients](#generated-clients)
- [React Query Hooks (`@ahoo-wang/wow-react`)](#react-query-hooks-ahoo-wangwow-react)
- [Example: Complete Cart Flow](#example-complete-cart-flow)
- [Key Dependencies](#key-dependencies)

The `@ahoo-wang/wow-client` package (formerly `@ahoo-wang/fetcher-wow`) provides Fetcher-based clients and query helpers for Wow CQRS and DDD services. Its source lives in the Wow repository under `typescript/wow-client/src`.

## Installation

```bash
pnpm add @ahoo-wang/wow-client @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream
```

`@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator`, and `@ahoo-wang/fetcher-eventstream` stay in the fetcher project under their own names and are peer dependencies of `@ahoo-wang/wow-client`. Migrating from `@ahoo-wang/fetcher-wow` means replacing the package name in dependencies and imports, importing the deprecated `Condition` API from `@ahoo-wang/wow-client/legacy`, and adapting to the first release's API changes: `ErrorCodes` is a frozen object (no `isSucceeded`/`isError`; compare with `ErrorCodes.SUCCEEDED`), failed calls are read with `toWowError`, `CommandClient` is not generic (`send<C>(…)`), command headers are typed and built with `commandHeaders()`/`waitStrategy()`, the last query parameter is `abort?: AbortController | AbortSignal`, aggregation builders take `(target, alias, options?)`, `createOwnerLoadStateAggregateClient` is `createLoadOwnerStateAggregateClient`, and `getPropertyValue`, `createQueryApiMetadata`, `DEFAULT_OWNER_ID`, `effectiveSort`, `requireElementScopedFilter` and the `*EndpointPaths` constants are gone. See the migration guide in the Wow documentation (`guide/typescript/migration`).

## Core Concepts

The Wow framework implements CQRS + Event Sourcing + DDD:

- **Commands** - Write operations that modify aggregate state
- **Queries** - Read operations that retrieve snapshot or event data
- **Aggregates** - Domain entities maintaining state and enforcing invariants
- **Events** - Immutable records of state changes

## Package Imports

```typescript
import { HttpMethod } from '@ahoo-wang/fetcher';
import {
  // Command
  CommandClient,
  CommandHeaders,
  CommandStage,
  commandHeaders,
  waitStrategy,
  WowHeaders,
  // Errors
  ErrorCodes,
  WowError,
  toWowError,
  isErrorInfo,
  // Query clients
  SnapshotQueryClient,
  EventStreamQueryClient,
  QueryClientFactory,
  LoadStateAggregateClient,
  LoadOwnerStateAggregateClient,
  WowMetadataClient,
  ResourceAttributionPathSpec,
  // Query DSL (also exported by '@ahoo-wang/wow-client/dsl')
  SortDirection,
  aggregation,
  AGGREGATION_LIMITS,
  AggregationGroupType,
  AggregationMetricType,
  AggregationExpressionType,
  AggregationExpressionOperator,
  AggregationDateUnit,
  AggregationFunction,
  DeletionState,
  FilterOperator,
  SearchMode,
  StringComparison,
  TimeUnit,
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  DEFAULT_CURSOR_SIZE,
  MAX_CURSOR_SIZE,
  MAX_CURSOR_SORT_FIELDS,
  cursorQuery,
  asc,
  desc,
  // Types
  type CommandRequest,
  type CommandRequestHeaders,
  type CommandResult,
  type CommandBody,
  type ErrorCode,
  type ErrorInfo,
  type MaterializedSnapshot,
  type PagedList,
  type FilterExpression,
  type QueryField,
  type ElementFilterExpression,
  type MetadataFilter,
  type EqualityFilterValue,
  type SearchFilterOptions,
  type RelativeTimeFilterOptions,
  type FilterListQuery,
  type FilterPagedQuery,
  type FilterSingleQuery,
  type CursorQuery,
  type CursorPage,
  type AggregationQuery,
  type AggregationElement,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationMetricOptions,
  type TermsAggregationOptions,
  type HistogramAggregationOptions,
  type DateHistogramAggregationOptions,
  type PercentileAggregationOptions,
  type DynamicDocument,
} from '@ahoo-wang/wow-client';

// Deprecated Condition API, only for Wow 8.10 servers; removed in v10.
import {
  and,
  or,
  nor,
  raw,
  eq,
  ne,
  gt,
  lt,
  gte,
  lte,
  between,
  contains,
  startsWith,
  endsWith,
  match,
  isIn,
  notIn,
  allIn,
  elemMatch,
  isNull,
  notNull,
  isTrue,
  isFalse,
  exists,
  today,
  beforeToday,
  tomorrow,
  thisWeek,
  nextWeek,
  lastWeek,
  thisMonth,
  lastMonth,
  recentDays,
  earlierDays,
  active,
  all,
  deleted,
  spaceId,
  id,
  ids,
  aggregateId,
  aggregateIds,
  tenantId,
  ownerId,
  Operator,
  en_US,
  zh_CN,
  type Condition,
  type ListQuery,
  type PagedQuery,
  type SingleQuery,
  type ListQueryRequest,
  type PagedQueryRequest,
  type SingleQueryRequest,
} from '@ahoo-wang/wow-client/legacy';
```

The `@ahoo-wang/wow-client/legacy` entry also has `listQuery`, `pagedQuery` and `singleQuery`, which build `Condition` queries; import them under another name if a file needs both.

### Entry Points

| Import | Contents |
|---|---|
| `@ahoo-wang/wow-client` | Everything except the `/legacy` API: clients, `QueryClientFactory`, `WowMetadataClient`, errors, headers and their builders, the stream endpoint presets `QUERY_STREAM_ENDPOINT` / `COMMAND_STREAM_ENDPOINT`, and the query DSL. |
| `@ahoo-wang/wow-client/dsl` | The query DSL alone — `filter`, `aggregation`, sort, projection, pagination, `cursorQuery`, the query factories and `Filter*` types, `DeletionState`, `DynamicDocument`, `SnapshotMetadataFields`, `DomainEventStreamMetadataFields` — with no HTTP code: no Fetcher, decorators, `reflect-metadata` or event-stream patches. Use it in code that only builds queries. |
| `@ahoo-wang/wow-client/legacy` | The deprecated `Condition` API for Wow 8.10 servers (builders, `Operator`, `Condition`-based query types and factories, operator locales). Removed in v10. |

```typescript
import { aggregation, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';

export const paidPage = pagedQuery({ filter: filter.eq('state.status', 'PAID') });
export const byStatus = aggregation.terms('state.status', 'status');
```

---

## CommonJS

The package supports ESM and CommonJS. `require('@ahoo-wang/wow-client')`
uses `dist/index.cjs`; ESM imports use `dist/index.es.js`. Save this runtime
query-builder example as a `.cjs` file and run it with Node:

```javascript
const { filter, pagedQuery } = require('@ahoo-wang/wow-client');

const query = pagedQuery({
  filter: filter.eq('state.status', 'PAID'),
  pagination: { index: 1, size: 10 },
});
console.log(query.filter.field); // state.status
console.log(query.pagination.size); // 10
```

`filter` and `pagedQuery` are runtime exports. Types such as `FilterExpression`
and `PagedList` remain TypeScript-only and use `import type`.

## Constructors (All Use ApiMetadata)

All Wow clients accept `ApiMetadata` (from `@ahoo-wang/fetcher-decorator`) in their constructor. Plain objects matching the ApiMetadata shape (`{ fetcher, basePath }`) work at runtime.

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import type { ApiMetadata } from '@ahoo-wang/fetcher-decorator';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080/' });

// Constructor signature for ALL clients: constructor(apiMetadata?: ApiMetadata)
// Pass a plain object satisfying ApiMetadata shape:
const apiMetadata: ApiMetadata = {
  fetcher,
  basePath: 'owner/{ownerId}/cart',
};
```

**CommandClient**, **SnapshotQueryClient**, **EventStreamQueryClient**, **LoadStateAggregateClient**, **LoadOwnerStateAggregateClient** all share this same constructor pattern.

### Path Parameter Substitution (`{ownerId}`, `{tenantId}`)

Templates in `basePath` (and `path`) are filled from `urlParams.path` on each request. Unbound placeholders are left as-is (and log a warning); bind them per call:

```typescript
// CommandRequest.urlParams / query request urlParams
await commandClient.send({
  path: 'items',
  urlParams: { path: { ownerId: 'owner-123' } },
  body: { productId: 'p-1', quantity: 2 },
});
// → POST {baseURL}/owner/owner-123/cart/items
```

Alternatively, CoSec's resource-attribution interceptor can fill `{tenantId}`/`{ownerId}` automatically from the JWT payload — see `@ahoo-wang/fetcher-cosec` in the fetcher project.

### Space Attribution

For space-scoped aggregates, `CommandHeaders.SPACE_ID` (`Wow-Space-Id`, the same value as `WowHeaders.SPACE_ID`) attributes a command to a space, and `filter.spaceId(value)` filters queries by space (`commandHeaders({ spaceId })` builds the command header). Snapshots expose `spaceId` via `MaterializedSnapshot`.

---

## CommandClient

Sends commands to modify aggregate state. The client is not generic: the body type is chosen per call, so one client sends every command of an aggregate.

### Setup

```typescript
const commandClient = new CommandClient({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});
```

### send<C>(commandRequest, attributes?)

Sends a command and resolves to the `CommandResult` of the stage it waited for (`PROCESSED` unless the headers say otherwise). A command the server refuses or fails to process rejects: Wow answers the failed `CommandResult`, an `ErrorInfo`, with the HTTP status its `errorCode` maps to (400 `IllegalArgument` for a handler that throws, 400 `CommandValidation`, 409 for version conflicts, 408 `RequestTimeout` when the wait stage does not arrive). Read it with `toWowError` (see [Errors](#errors)); a resolved result always has `errorCode` `'Ok'`.

```typescript
interface AddCartItem {
  productId: string;
  quantity: number;
}

const result: CommandResult = await commandClient.send<AddCartItem>({
  path: 'add_cart_item',
  method: HttpMethod.POST,
  urlParams: { path: { ownerId: 'owner-123' } },
  headers: {
    ...commandHeaders({ requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
  },
  body: {
    productId: 'product-123',
    quantity: 2,
  },
});
```

### sendAndWaitStream<C>(commandRequest, attributes?)

Sends a command and receives one `CommandResult` per stage it reaches, as a `Promise<CommandResultEventStream>` (a `ReadableStream<CommandResult>`: the results themselves, not server-sent event envelopes). The method sets `Accept: text/event-stream` itself. When the server fails midway (for example the wait times out) the stream errors with a `WowError`, so the `for await` throws; a result whose own `errorCode` is not `Ok` is still delivered as a result.

```typescript
const stream = await commandClient.sendAndWaitStream<AddCartItem>({
  path: 'add_cart_item',
  method: HttpMethod.POST,
  headers: waitStrategy({ stage: CommandStage.PROJECTED }),
  body: { productId: 'product-123', quantity: 2 },
});

try {
  for await (const result of stream) {
    console.log('Reached:', result.stage); // CommandResult
  }
} catch (error) {
  if (error instanceof WowError) console.warn(error.errorCode, error.errorMsg);
  throw error;
}
```

A generated or hand-written command client gets the same behaviour by taking the endpoint preset `COMMAND_STREAM_ENDPOINT` (`@api('…', COMMAND_STREAM_ENDPOINT)` or `@post(path, COMMAND_STREAM_ENDPOINT)`), which sets `Accept: text/event-stream` and the result extractor together.

### CommandStage Values

- `SENT` - Command published to command bus
- `PROCESSED` - Command processed by aggregate root
- `SNAPSHOT` - Snapshot generated
- `PROJECTED` - Events projected
- `EVENT_HANDLED` - Events processed by event handlers
- `SAGA_HANDLED` - Events processed by Saga

### Command Headers

`CommandHeaders` and `WowHeaders` are frozen `as const` objects of header names (each value a string literal type):

- `CommandHeaders.TENANT_ID` - Tenant context (`Command-Tenant-Id`)
- `CommandHeaders.OWNER_ID` - Owner context (`Command-Owner-Id`)
- `CommandHeaders.SPACE_ID` - Space (`Wow-Space-Id`; shared with queries, so it has the `Wow-` prefix like `WowHeaders.SPACE_ID`)
- `CommandHeaders.AGGREGATE_ID` - Aggregate root ID (`Command-Aggregate-Id`)
- `CommandHeaders.AGGREGATE_VERSION` - Expected version (`Command-Aggregate-Version`)
- `CommandHeaders.WAIT_STAGE` - Wait stage (`Command-Wait-Stage`)
- `CommandHeaders.WAIT_TIME_OUT` - Wait timeout in milliseconds (`Command-Wait-Timeout`)
- `CommandHeaders.WAIT_CONTEXT` / `WAIT_PROCESSOR` / `WAIT_FUNCTION` - What the wait stage refers to (`Command-Wait-Context`, `-Processor`, `-Function`)
- `CommandHeaders.WAIT_TAIL_STAGE` / `WAIT_TAIL_CONTEXT` / `WAIT_TAIL_PROCESSOR` / `WAIT_TAIL_FUNCTION` - Wait-chain tail (`Command-Wait-Tail-*`), only with `SAGA_HANDLED`
- `CommandHeaders.REQUEST_ID` - Request ID for idempotency (`Command-Request-Id`)
- `CommandHeaders.LOCAL_FIRST` - Prefer local processing (`Command-Local-First`)
- `CommandHeaders.COMMAND_AGGREGATE_CONTEXT` / `COMMAND_AGGREGATE_NAME` / `COMMAND_TYPE` - For the generic `/wow/command/send` route, which has no dedicated method: send to it with `CommandClient` and these headers
- `CommandHeaders.COMMAND_HEADER_X_PREFIX` - `Command-Header-`, custom headers copied into the command header
- `WowHeaders.ERROR_CODE` - `Wow-Error-Code` response header

`CommandRequestHeaders` makes every command header optional and types it by what the server parses: wait stages are `CommandStageName` (`CommandStage` or its name), `Command-Aggregate-Version` and `Command-Wait-Timeout` are `` `${number}` ``, `Command-Local-First` is `'true' | 'false'`. Other headers (`Authorization`, `Command-Header-*`) stay allowed as strings. Build them with the two helpers; both omit what you leave out and throw `TypeError` on invalid input:

```typescript
// Who the command is for. aggregateVersion: non-negative integer.
commandHeaders({ tenantId, ownerId, spaceId, aggregateId, aggregateVersion: 3, requestId, localFirst: true });

// What the server waits for before answering. timeoutMs: positive integer.
waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 5_000 });

// Wait chain: a saga handles the events, then the command it sends reaches tail.stage.
// A tail with any stage other than SAGA_HANDLED is a type error and throws.
waitStrategy({
  stage: CommandStage.SAGA_HANDLED,
  processor: 'TransferSaga',
  tail: { stage: CommandStage.PROCESSED, context: 'account' },
});
```

Option types: `CommandHeaderOptions`, `WaitFunction` (`context`, `processor`, `function`), `WaitStageOptions`, `WaitChainOptions`, `WaitStrategyOptions`.

### CommandRequest<C>

```typescript
interface CommandRequest<C extends object = object> extends ParameterRequest<
  CommandBody<C>
> {
  urlParams?: CommandUrlParams;
  headers?: CommandRequestHeaders;
  body?: CommandBody<C>; // CommandBody<C> = RemoveReadonlyFields<C>
}
```

### CommandResult Fields

`CommandResult` extends: Identifier, WaitCommandIdCapable, CommandStageCapable, NamedBoundedContext, AggregateNameCapable, AggregateId, CommandId, RequestId, ErrorInfo, FunctionInfoCapable, CommandResultCapable, SignalTimeCapable, NullableAggregateVersionCapable.

Key fields: `id`, `waitCommandId`, `stage`, `contextName`, `aggregateName`, `aggregateId`, `aggregateVersion?`, `commandId`, `requestId`, `errorCode`, `errorMsg`, `bindingErrors?`, `signalTime`, `result`.

---

## Errors

A Wow call fails in one of three ways:

- **The server refuses the request** (HTTP 4xx/5xx), including a command whose handler failed: the Promise rejects with the fetcher's error (`ExchangeError`), never resolves with a failure. `await toWowError(error)` returns a `WowError` read from the response's `ErrorInfo` body (through a clone), or from its `Wow-Error-Code` header.
- **A server-sent event stream fails midway**: Wow answers HTTP 200 and sends a last event named after the error code with an `ErrorInfo` body. Every built-in stream (`listStream`, `listStateStream`, `aggregateStream`, event `loadStream`, `sendAndWaitStream`) then errors with a `WowError`, so `for await` throws.
- **Wow never answered** (network failure, timeout, abort, a proxy's error page): `toWowError` returns `undefined`; handle the original error.

```typescript
try {
  return await snapshotClient.getStateById(id);
} catch (error) {
  const wowError = await toWowError(error);
  if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
  throw wowError ?? error;
}
```

- `WowError extends Error`: `name = 'WowError'`, `errorCode: ErrorCode`, `errorMsg` (empty when absent), `bindingErrors` (empty array when absent), optional `status` (HTTP status) and `cause`. `new WowError(errorInfo, { status?, cause? })`.
- `isErrorInfo(value)` is the `ErrorInfo` type guard.
- `ErrorCodes` is a frozen `as const` object: `SUCCEEDED` (`'Ok'`), `NOT_FOUND`, `BAD_REQUEST`, `ILLEGAL_ARGUMENT`, `ILLEGAL_STATE`, `REQUEST_TIMEOUT`, `TOO_MANY_REQUESTS`, `DUPLICATE_REQUEST_ID`, `COMMAND_VALIDATION`, `REWRITE_NO_COMMAND`, `EVENT_VERSION_CONFLICT`, `DUPLICATE_AGGREGATE_ID`, `COMMAND_EXPECT_VERSION_CONFLICT`, `SOURCING_VERSION_CONFLICT`, `ILLEGAL_ACCESS_DELETED_AGGREGATE`, `ILLEGAL_ACCESS_OWNER_AGGREGATE`, `ILLEGAL_ACCESS_SPACE_AGGREGATE`, `INTERNAL_SERVER_ERROR`, `QUERY_SCHEMA_VALIDATION`, `QUERY_SCHEMA_CONFLICT`, `QUERY_SCHEMA_UNAVAILABLE`, `BATCH_TASK_ERROR`. There is no `isSucceeded`/`isError`: compare `errorCode === ErrorCodes.SUCCEEDED`.
- `WowErrorCode` is the union of those values; `ErrorCode = WowErrorCode | (string & {})` also admits application codes. `ErrorInfo.errorCode` is `ErrorCode`.
- `RecoverableType`: `RECOVERABLE` (retrying may succeed), `UNRECOVERABLE` (retrying will not help), `UNKNOWN` (cannot be determined).

---

## SnapshotQueryClient<S, FIELDS>

Queries materialized snapshots (current aggregate state).

### Setup

```typescript
const snapshotClient = new SnapshotQueryClient<CartState>({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});
```

### Query Methods

```typescript
// Count matching snapshots
const count: number = await snapshotClient.count(filter.matchAll());

// List snapshots (returns MaterializedSnapshot<S>[])
const list = await snapshotClient.list({
  filter: filter.matchAll(),
  sort: [{ field: 'eventTime', direction: SortDirection.DESC }], // 'ASC' | 'DESC'; or use desc('eventTime')
  limit: 10,
});

// List snapshots as a stream: it yields each MaterializedSnapshot<S> itself
const stream = await snapshotClient.listStream({ filter: filter.matchAll() });
for await (const snapshot of stream) {
  console.log(snapshot.state);
}

// List only state objects (returns S[])
const states = await snapshotClient.listState({ filter: filter.matchAll() });

// List states as a stream: it yields each state S itself
const stateStream = await snapshotClient.listStateStream({
  filter: filter.matchAll(),
});

// Paged snapshots (returns PagedList<MaterializedSnapshot<S>>)
const paged = await snapshotClient.paged({
  filter: filter.matchAll(),
  pagination: { index: 1, size: 20 },
});
// PagedList: { total: number, list: T[] }

// Paged states (returns PagedList<S>)
const pagedState = await snapshotClient.pagedState({
  filter: filter.matchAll(),
  pagination: { index: 1, size: 20 },
});

// Forward-only cursor pages (no total count)
const cursorPage = await snapshotClient.cursor(
  cursorQuery({ filter: filter.matchAll(), size: 20 }),
);
const stateCursorPage = await snapshotClient.cursorState(
  cursorQuery({ filter: filter.matchAll(), size: 20 }),
);
// CursorPage<T>: { list: T[], nextCursor: string | null }

// Single snapshot
const snapshot = await snapshotClient.single({
  filter: filter.aggregateId('cart-123'),
});

// Single state
const state = await snapshotClient.singleState({
  filter: filter.aggregateId('cart-123'),
});
```

### Aggregation Methods

```typescript
type CartFields = 'state.status' | 'state.items';
type ItemFields = 'productId' | 'price' | 'quantity';

type ProductSummary = {
  product: string;
  itemCount: number;
  revenue: number;
};

const revenue = aggregation.multiply(
  aggregation.field<ItemFields>('price'),
  aggregation.field<ItemFields>('quantity'),
);

const aggregationQuery: AggregationQuery<CartFields, ItemFields> = {
  filter: filter.eq('state.status', 'COMPLETED'),
  elements: [aggregation.element('state.items', filter.gt('quantity', 0))],
  groupBy: [aggregation.terms('productId', 'product')],
  metrics: [
    aggregation.count('itemCount'),
    aggregation.sum(revenue, 'revenue'),
  ],
};

const summaries =
  await snapshotClient.aggregate<ProductSummary>(aggregationQuery);
const summaryStream =
  await snapshotClient.aggregateStream<ProductSummary>(aggregationQuery);
```

```typescript
aggregate<
  Row extends object = DynamicDocument,
  AGGREGATION_FIELDS extends string = string,
>(
  query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
  attributes?: Record<string, unknown>,
  abort?: AbortController | AbortSignal,
): Promise<Row[]>;

aggregateStream<
  Row extends object = DynamicDocument,
  AGGREGATION_FIELDS extends string = string,
>(
  query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
  attributes?: Record<string, unknown>,
  abort?: AbortController | AbortSignal,
): Promise<ReadableStream<Row>>;
```

`QueryApi` requires both aggregation methods and `cursor`, so custom
implementations must provide all three. `SnapshotQueryApi` additionally
requires `cursorState`.
`SnapshotQueryClient` and `EventStreamQueryClient` submit to
`snapshot/aggregation` and `event/aggregation`, respectively;
`aggregateStream` requests the rows as server-sent events and yields each row
itself.

### Query cancellation

The last parameter of every query and load method (`single`, `list`, `listStream`, `paged`, `count`, `cursor`, `aggregate`, the `*State` variants, `getById`…, the event client's `load`/`loadStream`, the state loaders, `WowMetadataClient.metadata`) is `abort?: AbortController | AbortSignal`. Pass a controller, `AbortSignal.timeout(ms)`, or the `signal` a data library such as TanStack Query hands to its query function. The second argument remains request attributes (`Record<string, unknown>`). Cancellation is cooperative: consumers must also ignore responses from superseded requests.

```typescript
const page = await snapshotClient.paged(query, undefined, AbortSignal.timeout(10_000));

// TanStack Query
useQuery({
  queryKey: ['carts', query],
  queryFn: ({ signal }) => snapshotClient.pagedState(query, undefined, signal),
});
```

### ID-Based Lookup Methods

```typescript
// Get full snapshot by aggregate ID
const snapshot = await snapshotClient.getById('cart-123');

// Get state only by aggregate ID
const state = await snapshotClient.getStateById('cart-123');

// Get multiple snapshots by IDs
const snapshots = await snapshotClient.getByIds(['cart-123', 'cart-456']);

// Get multiple states by IDs
const states = await snapshotClient.getStateByIds(['cart-123', 'cart-456']);
```

---

## EventStreamQueryClient<DomainEventBody, FIELDS>

Queries domain event stream history.

### Setup

```typescript
const eventClient = new EventStreamQueryClient({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});
```

### Methods

```typescript
const count = await eventClient.count(filter.matchAll());
const list = await eventClient.list({ filter: filter.matchAll() });
const stream = await eventClient.listStream({ filter: filter.matchAll() });
const paged = await eventClient.paged({
  filter: filter.matchAll(),
  pagination: { index: 1, size: 20 },
});
const cursorPage = await eventClient.cursor(
  cursorQuery({ filter: filter.matchAll(), size: 20 }),
);

type EventStreamFields = 'body';
type EventFields = 'name';
const eventAggregationQuery: AggregationQuery<EventStreamFields, EventFields> =
  {
    elements: [aggregation.element('body')],
    groupBy: [aggregation.terms('name', 'eventType')],
    metrics: [aggregation.count('count')],
  };
const eventCounts = await eventClient.aggregate(eventAggregationQuery);
const eventCountStream = await eventClient.aggregateStream(
  eventAggregationQuery,
);
```

Event aggregation expands the event array at `body`. Event fields are then
relative to that element; payload fields are nested below its `body` field.
Both aggregation methods use `event/aggregation`.

`load(id, headVersion, tailVersion, attributes?, abort?)` replays one aggregate's
event streams from `headVersion` (from 1) to `tailVersion`, both inclusive, in
version order — `GET {id}/event/{headVersion}/{tailVersion}`. `loadStream(…)` is
the same route as server-sent events. The server treats the range as a list
query and refuses more than its maximum list size (1000 by default). The route
carries a tenant segment by default but no owner segment, like the load-state
routes.

```typescript
const firstTen = await eventClient.load('cart-123', 1, 10);
```

`GET {id}/snapshot` and `GET {id}/state/tracing` have no client method; call
them through a Fetcher directly.

---

## LoadStateAggregateClient<S>

Loads aggregate state by ID, version, or time.

```typescript
const stateClient = new LoadStateAggregateClient<CartState>({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});

const state = await stateClient.load('cart-123');
const versioned = await stateClient.loadVersioned('cart-123', 5);
const timeBased = await stateClient.loadTimeBased('cart-123', Date.now());
```

## LoadOwnerStateAggregateClient<S>

Owner-specific aggregate state client (no ID required, uses owner context from path).

```typescript
const ownerClient = new LoadOwnerStateAggregateClient<CartState>({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});

const state = await ownerClient.load();
const versioned = await ownerClient.loadVersioned(5);
const timeBased = await ownerClient.loadTimeBased(Date.now());
```

---

## QueryClientFactory<S, FIELDS, DomainEventBody>

An explicit `basePath` in factory defaults or per-client options takes precedence
over the path assembled from context, resource attribution, and aggregate name.
Per-client `basePath` overrides the factory default only when it is not nullish;
`basePath: undefined` retains the factory path. An empty string is an explicit
override too.

Factory for creating pre-configured typed query clients.

### Setup

```typescript
const factory = new QueryClientFactory({
  fetcher,
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
});
```

### ResourceAttributionPathSpec

- `NONE` - No prefix
- `TENANT` - Path prefix: `/tenant/{tenantId}`
- `OWNER` - Path prefix: `/owner/{ownerId}`
- `TENANT_OWNER` - Path prefix: `/tenant/{tenantId}/owner/{ownerId}`

### Factory Methods

```typescript
const snapshotClient = factory.createSnapshotQueryClient();
const eventClient = factory.createEventStreamQueryClient();
const stateClient = factory.createLoadStateAggregateClient();
const ownerStateClient = factory.createLoadOwnerStateAggregateClient();
```

`createEventStreamQueryClient<EVENT_FIELDS>()` types event-stream fields
separately from the factory's snapshot `FIELDS`. The route composition itself
is internal (there is no `createQueryApiMetadata` and no `*EndpointPaths`
constant); read a client's `apiMetadata.basePath` if you need the result.

---

## WowMetadataClient

Reads the server's `WowMetadata` (bounded contexts, their aggregates, and each
aggregate's commands and events) from `GET /wow/metadata`, relative to the
fetcher's base URL.

```typescript
const metadata = await new WowMetadataClient({ fetcher }).metadata();
Object.keys(metadata.contexts); // ['example-service', …]
```

---

## Cursor Queries

Wow V9 cursor queries are forward-only and return no total count. Pass the
opaque `nextCursor` unchanged to the next request.

```typescript
let query = cursorQuery({
  filter: filter.eq('state.status', 'PAID'),
  sort: [asc('state.createdAt')],
  size: 100,
});

const first: CursorPage<MaterializedSnapshot<CartState>> =
  await snapshotClient.cursor(query);

if (first.nextCursor) {
  query = cursorQuery({ ...query, cursor: first.nextCursor });
  const second = await snapshotClient.cursor(query);
}
```

`DEFAULT_CURSOR_SIZE` is `10`; `size` must be between `1` and
`MAX_CURSOR_SIZE` (`2147483646`), the cursor model's bound. Over HTTP the
server enforces its own page limit, 100 by default, and answers a larger size
with a 400. A request accepts at most
`MAX_CURSOR_SORT_FIELDS` (`32`) explicit sort fields, and each field at most
once: `cursorQuery` throws `TypeError('Cursor sort fields must be unique.')`
for a repeat. A cursor is a position in one total order, and two directions for
the same field leave that position ambiguous, so a page could repeat or skip
rows — Wow's gateway refuses it for the same reason. Snapshot and event-stream
cursors use `snapshot/cursor`, `snapshot/cursor/state`, and `event/cursor`.

## Filter Expressions

Wow 8.11+ queries use a discriminated `FilterExpression` with `op` as the
wire discriminator. Builders are grouped under `filter` so they do not collide
with the legacy `Condition` helpers:

```typescript
const expression = filter.and([
  filter.deletion(DeletionState.ACTIVE),
  filter.eq('state.status', 'PAID'),
  filter.elementMatch('state.items', filter.gt('quantity', 0)),
  filter.search('event sourcing', {
    mode: SearchMode.PHRASE,
    fields: ['state.title', 'state.description'],
  }),
  filter.yesterday('state.createdAt', {
    zoneId: 'Asia/Shanghai',
    timeUnit: TimeUnit.MILLISECONDS,
  }),
]);

await snapshotClient.count(expression);
await snapshotClient.list({ filter: expression, limit: 10 });
// The factories infer FilterListQuery when filter is supplied.
const query = listQuery({ filter: expression });
```

`listQuery({ filter })` sends no `limit` unless given, so the server applies its
default list size (100 by default) and refuses a limit above its maximum list
size (1000 by default). The legacy `listQuery({ condition })` from
`@ahoo-wang/wow-client/legacy` keeps its previous default page size.

Available builders:

- Logical: `matchAll`, `matchNone`, `and`, `or`, `nor`
- Metadata: `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId`, `spaceId`
- Comparison: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`
- String: `contains`, `startsWith`, `endsWith`; pass `StringComparison` as the third argument
- Collection: `isIn`, `notIn`, `containsAll`
- Presence: `isEmpty`, `isEmptyString`, `isNotEmptyString`, `isNull`, `isNotNull`, `exists`, `notExists`
- Scope/search: `deletion`, `elementMatch`, `search(query, options?: SearchFilterOptions)`
- Relative time: `today`, `beforeToday`, `tomorrow`, `thisWeek`, `nextWeek`, `lastWeek`, `thisMonth`, `lastMonth`, `yesterday`, `nextMonth`, `lastYear`, `thisYear`, `nextYear`, `recentDays`, `earlierDays`; relative to the server's now: `beforeNow`, `afterNow` (ISO-8601 `offset`, default `PT0S`, Wow 9.2.0+)

`and`, `or`, `nor`, `ids`, `aggregateIds`, `isIn`, `notIn`, and `containsAll`
accept one non-empty `readonly` array and throw `TypeError` for an empty array:

```typescript
const statuses = ['PAID', 'SHIPPED'] as const;
filter.and([filter.eq('state.status', 'PAID')]);
filter.ids(['snapshot-1', 'snapshot-2']);
filter.aggregateIds(['cart-1', 'cart-2']);
filter.isIn('state.status', statuses);
filter.notIn('state.status', ['CANCELLED']);
filter.containsAll('state.tags', ['wow', 'cqrs']);
```

`eq` and `ne` accept a JSON scalar or `null`; use `isIn`/`notIn` for multiple
values. Query field segments may start with `@`.

Every field path is checked against Wow's `QueryField` pattern
(`^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$`) —
not only in `filter.*` but in `asc`, `desc` and `projection`, since Wow holds a
sort field and a projection entry as `QueryField` too. A path it rejects throws
`TypeError('Query field is invalid: [<path>].')` where it is built:

```typescript
asc('state.createdAt'); // ok
asc('9 not a path'); // throws TypeError
projection({ include: ['state.items.0.sku'] }); // ok: numeric segments index arrays
```

`projection()` always returns both keys, `include` and `exclude`, whether or
not they were given.

`isEmptyString` matches exactly `""`. `isNotEmptyString` requires the field to
exist, be non-null, and differ from `""`. Whitespace-only strings are not empty.

Relative-time builders accept JVM `ZoneId` and `DateTimeFormatter` options:

```typescript
interface RelativeTimeFilterOptions {
  zoneId?: string;
  datePattern?: string;
  timeUnit?: TimeUnit;
}

filter.search(query, options?: SearchFilterOptions);
filter.today(field, options?);
filter.beforeToday(field, time, options?);
filter.tomorrow(field, options?);
filter.thisWeek(field, options?);
filter.nextWeek(field, options?);
filter.lastWeek(field, options?);
filter.thisMonth(field, options?);
filter.lastMonth(field, options?);
filter.yesterday(field, options?);
filter.nextMonth(field, options?);
filter.lastYear(field, options?);
filter.thisYear(field, options?);
filter.nextYear(field, options?);
filter.recentDays(field, days, options?);
filter.earlierDays(field, days, options?);
filter.beforeNow(field, offset?, options?); // field < server now + offset (Wow 9.2.0+)
filter.afterNow(field, offset?, options?); // field > server now + offset (Wow 9.2.0+)
```

`SearchFilterOptions` has optional `fields` and `mode`; `mode` defaults to
`SearchMode.TERMS`. Relative-time `timeUnit` defaults to `TimeUnit.MILLISECONDS`.
`days` must be a positive JVM `Int`.

`SingleQueryRequest`, `ListQueryRequest`, and `PagedQueryRequest` accept either
the filter-based request types or the existing condition-based request types.
`count` likewise accepts `FilterExpression | Condition`. The legacy Condition
API remains available from `@ahoo-wang/wow-client/legacy` for servers in the
compatibility window.

`elementMatch` accepts `ElementFilterExpression`, whose relative field type is
independent from the outer query fields and excludes metadata filters,
`deletion`, and `search`, including inside nested `and`/`or`/`nor` expressions.

## AggregationQuery

`AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS = ROOT_FIELDS>` accepts
these fields:

- `filter?: FilterExpression<ROOT_FIELDS>`
- `elements?: AggregationElement[]`
- `groupBy?: AggregationGroup<AGGREGATION_FIELDS>[]`
- `metrics: [AggregationMetric<AGGREGATION_FIELDS>, ...AggregationMetric<AGGREGATION_FIELDS>[]]` (non-empty)
- `sort?: FieldSort[]`
- `limit?: number`
- `having?: HavingExpression`

Each `AggregationElement` has a `path` and optional `filter`.
`elements[].filter` is an `ElementFilterExpression`.
Elements form an ordered expansion chain. The first path is root-relative;
later element paths, group fields, and metric expression fields are relative
to the current innermost element.

`AggregationGroup` is one of:

- `TERMS`: `field`, `alias`, optional `missingKey`
- `HISTOGRAM`: `field`, `alias`, `interval`
- `DATE_HISTOGRAM`: `field`, `alias`, `unit`, optional `timeZone` and `dense`

`AggregationMetric` is one of:

- `COUNT`: `alias`
- `NUMERIC`: `function`, `expression`, `alias`
- `ANY`: `field`, `alias`
- `DISTINCT_COUNT`: `expression`, `alias`
- `PERCENTILE`: `expression`, `percentile`, `alias`
- `DERIVED`: `expression: DerivedExpression`, `alias`

All non-derived metrics accept optional `filter: FilterExpression<AGGREGATION_FIELDS>`.

`AggregationFunction` values are `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV`, and `VARIANCE`.
`AggregationDateUnit` values are `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`,
`HOUR`, `MINUTE`, and `SECOND`. Aggregation expressions use these
discriminators:

- `AggregationExpressionType.FIELD`: `field`
- `AggregationExpressionType.CONSTANT`: `value`
- `AggregationExpressionType.BINARY`: `operator`, `left`, `right`

`AggregationExpressionOperator` values are `ADD`, `SUBTRACT`, `MULTIPLY`, and
`DIVIDE`. Use the `aggregation` builders rather than hand-writing these shapes:

```typescript
aggregation.element(path, predicate?);
aggregation.field(field);
aggregation.constant(value);
aggregation.add(left, right);
aggregation.subtract(left, right);
aggregation.multiply(left, right);
aggregation.divide(left, right);
// Every group and metric: target first, alias second, options last.
aggregation.terms(field, alias, { missingKey? }?);
aggregation.histogram(field, alias, { interval });
aggregation.dateHistogram(field, alias, { unit, timeZone?, dense? }); // timeZone defaults to UTC
aggregation.any(field, alias, { filter? }?);
aggregation.count(alias, { filter? }?);
aggregation.sum(expression, alias, { filter? }?);
aggregation.avg(expression, alias, { filter? }?);
aggregation.min(expression, alias, { filter? }?);
aggregation.max(expression, alias, { filter? }?);
aggregation.stddev(expression, alias, { filter? }?);
aggregation.variance(expression, alias, { filter? }?);
aggregation.distinctCount(expression, alias, { filter? }?);
aggregation.percentile(expression, alias, { percentile, filter? });
aggregation.derived(expression, alias);
aggregation.query(query); // admits the assembled query, returns it
```

Option types: `TermsAggregationOptions` (`missingKey`), `HistogramAggregationOptions`
(`interval`), `DateHistogramAggregationOptions` (`unit`, `timeZone`, `dense`),
`AggregationMetricOptions` (`filter`), `PercentileAggregationOptions`
(`percentile`, `filter`). None of them carries the alias.

```typescript
aggregation.count('paid', { filter: filter.eq('state.status', 'PAID') });
aggregation.percentile(aggregation.field('latency'), 'p95', { percentile: 95 });
aggregation.dateHistogram('createTime', 'day', {
  unit: AggregationDateUnit.DAY,
  timeZone: 'Asia/Shanghai',
});
```

`ANY` is a metric, not a group. It returns one non-null scalar from the current
group, or `null` when no value exists. Its field is relative to the innermost
element. Wow requires the field to support `AGGREGATE_TERMS` and rejects
collection cardinality. MongoDB uses a `$max` accumulator while Elasticsearch
uses a one-bucket `terms` aggregation, so the selected value is intentionally
unspecified. Sorting by an `ANY` alias is an expensive metric sort.

`aggregation.query(query)` admits an assembled `AggregationQuery` against the
rules Wow enforces in its own constructor, then returns it. It throws
`TypeError` for: the `elements`, `groupBy`, `metrics` and `sort` ceilings; a
`limit` outside 1..10000; colliding aliases; a `sort` field that is not an
alias, is repeated, or has no `groupBy`; an effective sort over the ceiling;
`dense` beside another dimension; `having` without a `groupBy`, referencing an
undeclared or `ANY` metric, or carrying non-finite or inverted bounds;
expression depth over 8 or more than 256 expression nodes across all metrics
together; a derived reference to a metric not declared strictly before it or to
an `ANY` metric; a non-finite constant; and `SEARCH` or `ELEMENT_MATCH` in a
metric filter.

`AGGREGATION_LIMITS` publishes those sizes (`DEFAULT_LIMIT` 100, `MAX_LIMIT`
10000, `MAX_ELEMENTS` 5, `MAX_GROUPS` 32, `MAX_METRICS` 64, `MAX_SORT_FIELDS`
32, `MAX_EXPRESSION_DEPTH` 8, `MAX_EXPRESSION_NODES` 256). Wow applies the
requested sort, then each remaining group ascending.

What stays on the server is what needs a schema: field capabilities,
cardinality, and whether a metric filter names an array-valued field. The `Row`
generic describes aggregation result rows only; wow-client does not perform runtime
decoding.

### Derived metrics, HAVING and group options

Aligned with Wow `main` at `fd1b3cd46`. Optional metric filters, `missingKey`, and `dense` are omitted from the JSON unless supplied.

- `aggregation.distinctCount(expression, alias, { filter? })` counts distinct non-null contributions; `aggregation.percentile(expression, alias, { percentile, filter? })` accepts finite values strictly between 0 and 100 (use 50 for the median). `stddev` and `variance` compute population statistics. Percentiles are approximate on both backends; Elasticsearch distinct counts may be approximate, while MongoDB counts distinct values exactly.
- Non-derived metrics accept an optional `{ filter }` option, a `FilterExpression` in the current aggregation scope. It affects only that metric. `aggregation.query()` rejects `SEARCH` and `ELEMENT_MATCH` there — a metric filter is a whole-value predicate on one record, so element matching and full text have no reading — while the backend rejects array-valued fields, which needs a schema.
- `aggregation.derived(expression, alias)` uses a `DerivedExpression` tree (`METRIC_REF`, `CONSTANT`, `BINARY`). References must name metrics declared strictly before the derived metric (so a self-reference cannot be written) and cannot reference `ANY`; `aggregation.query()` enforces both. Derived metrics have no record filter; filter the referenced metrics instead. Null operands or division by zero produce null.
- `having?: HavingExpression` supports `CONDITION`, `BETWEEN`, `IN`, `IS_NULL`, `AND`, and `OR`, using metric aliases, not field paths. `ComparisonOperator` contains `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`. HAVING requires grouping and cannot reference `ANY`; it runs before sorting and limit. Non-empty sets/operands are enforced by tuple types, and `aggregation.query()` checks finite values, ordered bounds, references and depth before the request is sent.
- `terms(field, alias, { missingKey })` optionally merges missing/null values into a nonblank string bucket key; the backend validates string field support. `dateHistogram(field, alias, { unit, timeZone?, dense: true })` fills interior date gaps; it requires the only group dimension. No rows means no generated date range.

Backend requirements still apply (MongoDB 5.1+ for dense groups, 7.0+ for percentiles); the client performs no backend capability probing. Raw typed expression objects are not runtime validators on their own — pass the assembled query through `aggregation.query()` to have them checked.

```ts
import {
  aggregation,
  AggregationExpressionOperator,
  ComparisonOperator,
  DerivedExpressionType,
  HavingExpressionType,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

const query: AggregationQuery = {
  groupBy: [
    aggregation.terms('state.status', 'status', { missingKey: 'Unknown' }),
  ],
  metrics: [
    aggregation.count('orders'),
    aggregation.sum(aggregation.field('state.amount'), 'revenue'),
    aggregation.derived(
      {
        type: DerivedExpressionType.BINARY,
        operator: AggregationExpressionOperator.DIVIDE,
        left: { type: DerivedExpressionType.METRIC_REF, metric: 'revenue' },
        right: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
      },
      'averageOrderValue',
    ),
  ],
  having: {
    type: HavingExpressionType.CONDITION,
    metric: 'averageOrderValue',
    operator: ComparisonOperator.GTE,
    value: 100,
  },
};
```

## Query DSL Conditions (Deprecated)

The complete Condition API (`Condition`, `ConditionCapable`, `Operator`,
builders, helpers, and operator locales) is deprecated and exported only by
`@ahoo-wang/wow-client/legacy`, which is removed in v10. Use `FilterExpression`
and `filter.*`; keep Condition only while talking to a Wow 8.10 server.

```typescript
import { and, eq, listQuery, ownerId } from '@ahoo-wang/wow-client/legacy';

const carts = await snapshotClient.listState(
  listQuery({ condition: and(ownerId('u-42'), eq('state.status', 'ACTIVE')) }),
);
```

### Comparison Operators

```typescript
eq('status', 'active'); // Equal
ne('status', 'inactive'); // Not equal
gt('age', 18); // Greater than
lt('score', 100); // Less than
gte('rating', 4.0); // Greater than or equal
lte('price', 100); // Less than or equal
between('salary', 50000, 100000); // Between two values
```

### String Operators

```typescript
contains('email', '@company.com');
startsWith('username', 'j');
endsWith('domain', '.com');
match('description', 'keywords'); // Full-text search
```

### Collection Operators

```typescript
isIn('status', 'active', 'pending', 'review');
notIn('role', 'guest', 'banned');
allIn('tags', 'react', 'typescript');
elemMatch('items', eq('quantity', 0));
```

### Null/Boolean Operators

```typescript
isNull('deletedAt');
notNull('email');
isTrue('isActive');
isFalse('isDeleted');
exists('phoneNumber');
```

### Date Operators

```typescript
today('createdAt');
beforeToday('lastLogin', someTime); // Field is before today (time value is compared server-side)
tomorrow('scheduledDate');
thisWeek('updatedAt');
nextWeek('startDate');
lastWeek('endDate');
thisMonth('createdDate');
lastMonth('expirationDate');
recentDays('createdAt', 5); // Last N days including today
earlierDays('createdAt', 3); // More than N days ago
```

### ID Operators

```typescript
id('abc-123');
ids(['abc-123', 'def-456']);
aggregateId('agg-789');
aggregateIds(['agg-1', 'agg-2']);
tenantId('tenant-abc');
ownerId('owner-123');
```

### State Operators

```typescript
import { DeletionState } from '@ahoo-wang/wow-client';
import { active, all, deleted } from '@ahoo-wang/wow-client/legacy';

active(); // Not deleted (shorthand for deleted(DeletionState.ACTIVE))
deleted(DeletionState.DELETED); // Is deleted
all(); // No filter ({ operator: Operator.ALL } — not tied to deletion state)
```

### Logical Operators

```typescript
and(
  eq('tenantId', 'tenant-123'),
  or(
    contains('email', '@company.com'),
    isIn('department', 'engineering', 'marketing'),
  ),
  between('salary', 50000, 100000),
);

nor(eq('status', 'banned')); // Nor (not or)

raw({ $text: { $search: 'keywords' } }); // Raw condition
```

---

## Key Types

### MaterializedSnapshot<S>

Full snapshot with metadata. Fields: `state`, `contextName`, `aggregateName`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `version`, `eventId`, `firstEventTime`, `eventTime`, `snapshotTime`, `firstOperator`, `operator`, `tags`, `deleted`. `MediumMaterializedSnapshot` and `SmallMaterializedSnapshot` mirror Wow's Kotlin models and have no `contextName`/`aggregateName` (the server never sends them); Small keeps only `state`, `version` and `firstEventTime`.

### PagedList<T>

```typescript
interface PagedList<T> {
  total: number;
  list: T[];
}
```

### CommandBody<C>

```typescript
type CommandBody<C> = RemoveReadonlyFields<C>;
```

### Pagination

```typescript
interface Pagination {
  index: number;
  size: number;
}
```

---

## Generated Clients

When using `@ahoo-wang/wow-generator`, clients are auto-generated:

```typescript
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import {
  CartCommandClient,
  CartStreamCommandClient,
} from './generated/example/cart/commandClient';
import { cartQueryClientFactory } from './generated/example/cart/queryClient';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080/' });
// The constructor merges { fetcher } over the generated defaults, so the
// clients keep the bounded-context base path (the `example` prefix).
const cartCommandClient = new CartCommandClient({ fetcher });
const cartStreamCommandClient = new CartStreamCommandClient({ fetcher });
// Reaching the service directly, without a gateway that routes by context alias:
const directCommandClient = new CartCommandClient({ fetcher, basePath: '' });

// Send command using generated client
await cartCommandClient.addCartItem({
  method: HttpMethod.POST,
  body: { productId: 'prod-1', quantity: 1 },
});

// Streaming version (stream client, same generated method)
const stream = await cartStreamCommandClient.addCartItem({
  method: HttpMethod.POST,
  body: { productId: 'prod-1', quantity: 1 },
});

// Query clients from factory
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient();
// The query factory's equivalent of basePath: '' is contextAlias: ''
const directSnapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  contextAlias: '',
});
const eventClient = cartQueryClientFactory.createEventStreamQueryClient();
const stateClient = cartQueryClientFactory.createLoadStateAggregateClient();
```

---

## React Query Hooks (`@ahoo-wang/wow-react`)

The React Wow query hooks moved out of `@ahoo-wang/fetcher-react` into `@ahoo-wang/wow-react` (source under `typescript/wow-react/src` in the Wow repository).

```bash
pnpm add @ahoo-wang/wow-react @ahoo-wang/wow-client
```

- `@ahoo-wang/wow-react` runs its own request state machine and does not depend on `@ahoo-wang/fetcher-react`; its peers are `react` 19.3+, `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventstream` and `@ahoo-wang/wow-client`.
- State: an error and `abort()` keep the last `result`; `reset()` aborts the request in flight and clears `result` and `error`; the first frame of a hook that runs on mount is `loading`, on the server too. A `useFetcher*` hook runs again when `url` changes or the Fetcher's name (or `baseURL` when unnamed) changes.
- With a generated client, pass its fields type as the second type argument (`usePagedQuery<CartState, CartFields>(…)`): TypeScript does not infer `FIELDS` from `execute` once `R` is written.
- Query hooks wrap an `execute` function you supply: `useSingleQuery`, `useListQuery`, `usePagedQuery`, `useCountQuery`, `useListStreamQuery`.
- Fetcher-bound variants issue the request themselves: `useFetcherSingleQuery`, `useFetcherListQuery`, `useFetcherPagedQuery`, `useFetcherCountQuery`, `useFetcherListStreamQuery`.
- Import these hooks from `@ahoo-wang/wow-react`, not from the root of `@ahoo-wang/fetcher-react`; mixing both leaves two sets of same-named Wow hooks and two copies of the Wow types in one project.
- The option and return types are the package's own: every `Use…Options` extends `QueryHookOptions` (`query`, `initialQuery`, `autoExecute`, `attributes`, `execute`, `onSuccess`, `onError`; the `useFetcher*` hooks take `url` and `fetcher` instead of `execute`) and every `Use…Return` extends `QueryHookReturn` (`status`, `loading`, `result`, `error`, `execute`, `abort`, `reset`, `getQuery`, `setQuery`; the list streams return `items` and `done` instead of `result`). `status` is `QueryStatus`, the string union `'idle' | 'loading' | 'success' | 'error'`, so compare it with literals; do not import `PromiseStatus` from fetcher-react. `E` defaults to `Error`: pass `FetcherError` as `E`, or narrow with `instanceof`, before reading `error.exchange`. There is no `initialStatus`, `propagateError`, `onAbort` or `resultExtractor` option.

```typescript
import { filter, pagedQuery } from '@ahoo-wang/wow-client';
import { usePagedQuery } from '@ahoo-wang/wow-react';

const { result, loading, error, setQuery } = usePagedQuery<CartState>({
  initialQuery: pagedQuery({
    filter: filter.eq('state.status', 'PAID'),
    pagination: { index: 1, size: 20 },
  }),
  execute: (query, attributes, controller) =>
    snapshotClient.pagedState(query, attributes, controller), // abort: controller or its signal
});
```

---

## Example: Complete Cart Flow

```typescript
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandStage,
  ErrorCodes,
  SnapshotQueryClient,
  filter,
  toWowError,
  waitStrategy,
} from '@ahoo-wang/wow-client';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080/' });

const commandClient = new CommandClient({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});

const snapshotClient = new SnapshotQueryClient({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
});

// Send command
const result = await commandClient
  .send<{ productId: string; quantity: number }>({
    path: 'add_cart_item',
    method: HttpMethod.POST,
    urlParams: { path: { ownerId: 'owner-123' } },
    headers: waitStrategy({ stage: CommandStage.SNAPSHOT }),
    body: { productId: 'prod-123', quantity: 2 },
  })
  .catch(async error => {
    // refused or failed: WowError with errorCode, status and bindingErrors
    throw (await toWowError(error)) ?? error;
  });

// Query updated state
const cart = await snapshotClient.getStateById(
  result.aggregateId,
  undefined,
  AbortSignal.timeout(5_000),
);

// Stream the current state; a failure midway throws a WowError here
const stream = await snapshotClient.listStateStream({
  filter: filter.aggregateId(result.aggregateId),
});
for await (const state of stream) {
  console.log('Cart:', state);
}
```

---

## Key Dependencies

- `@ahoo-wang/fetcher` - Core HTTP client
- `@ahoo-wang/fetcher-eventstream` - SSE streaming support (peer dependency, loaded by wow-client)
- `@ahoo-wang/fetcher-decorator` - ApiMetadata type, decorators for auto-implemented methods
- `@ahoo-wang/wow-client` - Wow CQRS/DDD types and clients
- `@ahoo-wang/wow-react` - React Wow query hooks (optional; React 19.3 or later)

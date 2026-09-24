---
title: 'Snapshot queries'
description: 'Snapshot queries — @ahoo-wang/wow-client'
---

# Snapshot queries

`SnapshotQueryClient<S,FIELDS>(apiMetadata?)` queries materialized snapshots. Every query method is POST. The final optional `abort` cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data library passes; attributes are execution metadata, not JSON body fields. Request projection can make a result partial: the generic T must honestly describe selected fields rather than asserting missing fields exist.

| Method                       | Relative endpoint                      | Promise result                                         |
| ---------------------------- | -------------------------------------- | ------------------------------------------------------ |
| single / singleState         | snapshot/single, snapshot/single/state | MaterializedSnapshot&lt;S&gt; / S                      |
| list / listState             | snapshot/list, snapshot/list/state     | Snapshot[] / S[]                                       |
| listStream / listStateStream | Same list endpoints, SSE Accept        | ReadableStream of JSON SSE snapshots / states          |
| paged / pagedState           | snapshot/paged, snapshot/paged/state   | PagedList with total/list                              |
| count                        | snapshot/count                         | number; body is FilterExpression or Condition directly |
| cursor / cursorState         | snapshot/cursor, snapshot/cursor/state | CursorPage with list/nextCursor                        |
| aggregate / aggregateStream  | snapshot/aggregation                   | DynamicDocument[] or JSON SSE rows                     |

Each concrete method takes `(query, attributes?, abort?)`; the query is a Filter* query, or a deprecated Condition query from `@ahoo-wang/wow-client/legacy` (the `*QueryRequest` unions); getById/getStateById take an id instead of a query, send `filter.aggregateId(id)` through single/singleState, and so need Wow 8.11 or later; against Wow 8.10 call single/singleState with a `/legacy` query built from `aggregateId(id)`. getByIds/getStateByIds take string[], use the new aggregateIds filter and limit equal to input length. Empty IDs return Promise.resolve([]) without a request. Do not assume the server preserves input ID order.

MaterializedSnapshot combines state S with context/aggregate/tenant/owner/space identity, version, event/time/operator data, tags and deleted. MediumMaterializedSnapshot and SmallMaterializedSnapshot mirror Wow's Kotlin models and carry no context/aggregate names, which the server never sends: Medium keeps state, tenant/owner/space, version, event/time/operator data and tags; Small keeps state, version and firstEventTime. They are structural models, not converters. No client method covers `GET {id}/snapshot`; call it through a Fetcher directly. SnapshotMetadataFields provides exact logical metadata names; state paths typically live below `state`.

No result cache or consistency wait is added by these clients. Read-after-command visibility depends on the server projection and requested wait stage. A missing single result is still typed Promise&lt;T&gt;; this package does not normalize absence into T | null. A missing aggregate is a rejected call: `await toWowError(error)` gives `errorCode` `ErrorCodes.NOT_FOUND` (see [errors](./errors-and-utilities)). Stream consumption/reader cleanup belongs to the caller; `listStream`, `listStateStream` and `aggregateStream` error with a `WowError` when the server fails midway, so a `for await` throws. See [query options](./query-options), [cursor](./cursor-queries), [aggregation](./aggregations).

## Complete example

```ts
import {
  SnapshotQueryClient,
  pagedQuery,
  filter,
  asc,
} from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export async function firstPage(signal = AbortSignal.timeout(10_000)) {
  return client.pagedState(
    pagedQuery({
      filter: filter.eq('state.active', true),
      pagination: { index: 1, size: 20 },
      sort: [asc('state.name')],
    }),
    undefined,
    signal,
  );
}
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### QueryApi {#api-QueryApi}

::: details Expand all fields and members

```ts
export interface QueryApi<R, FIELDS extends string = string> {
  cursor<T extends Partial<R> = R>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<CursorPage<T>>;
  aggregate<
    Row extends DynamicDocument = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<Row[]>;
  aggregateStream<
    Row extends DynamicDocument = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
  single<T extends Partial<R> = R>(
    singleQuery: SingleQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T>;
  list<T extends Partial<R> = R>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;
  listStream<T extends Partial<R> = R>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;
  paged<T extends Partial<R> = R>(
    pagedQuery: PagedQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<PagedList<T>>;
  count(
    filter: FilterExpression<FIELDS> | Condition<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<number>;
}
```

:::

[typescript/wow-client/src/query/queryApi.ts:37](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryApi.ts#L37)

### MaterializedSnapshot {#api-MaterializedSnapshot}

```ts
export interface MaterializedSnapshot<S>
  extends
    StateCapable<S>,
    AggregateId,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    SnapshotTimeCapable,
    AbacTaggable,
    DeletedCapable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:34](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L34)

### MediumMaterializedSnapshot {#api-MediumMaterializedSnapshot}

```ts
export interface MediumMaterializedSnapshot<S>
  extends
    StateCapable<S>,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    AbacTaggable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:62](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L62)

### SmallMaterializedSnapshot {#api-SmallMaterializedSnapshot}

```ts
export interface SmallMaterializedSnapshot<S>
  extends StateCapable<S>, Version, FirstEventTimeCapable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:84](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L84)

### SnapshotMetadataFields {#api-SnapshotMetadataFields}

```ts
export class SnapshotMetadataFields {
  static readonly VERSION = 'version';
  static readonly TENANT_ID = 'tenantId';
  static readonly OWNER_ID = 'ownerId';
  static readonly SPACE_ID = 'spaceId';
  static readonly EVENT_ID = 'eventId';
  static readonly FIRST_EVENT_TIME = 'firstEventTime';
  static readonly EVENT_TIME = 'eventTime';
  static readonly FIRST_OPERATOR = 'firstOperator';
  static readonly OPERATOR = 'operator';
  static readonly SNAPSHOT_TIME = 'snapshotTime';
  static readonly TAGS = 'tags';
  static readonly DELETED = 'deleted';
  static readonly STATE = 'state';
}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:93](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L93)

### SnapshotQueryApi {#api-SnapshotQueryApi}

::: details Expand all fields and members

```ts
export interface SnapshotQueryApi<
  S,
  FIELDS extends string = string,
> extends QueryApi<MaterializedSnapshot<S>, FIELDS> {
  cursorState<T extends Partial<S> = S>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<CursorPage<T>>;
  singleState<T extends Partial<S> = S>(
    singleQuery: SingleQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T>;
  listState<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;
  listStateStream<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;
  pagedState<T extends Partial<S> = S>(
    pagedQuery: PagedQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<PagedList<T>>;
}
```

:::

[typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts#L32)

### SnapshotQueryClient {#api-SnapshotQueryClient}

::: details Expand all fields and members

```ts
export class SnapshotQueryClient<S, FIELDS extends string = string> implements SnapshotQueryApi<S, FIELDS>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    aggregate<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<Row[]>;
    aggregateStream<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
    cursor<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<CursorPage<T>>;
    cursorState<T extends Partial<S> = S>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<CursorPage<T>>;
    count(filter: FilterExpression<FIELDS> | Condition<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<number>;
    list<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    listStream<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    listState<T extends Partial<S> = S>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    listStateStream<T extends Partial<S> = S>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    paged<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<PagedList<T>>;
    pagedState<T extends Partial<S> = S>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<PagedList<T>>;
    single<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(singleQuery: SingleQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T>;
    singleState<T extends Partial<S> = S>(singleQuery: SingleQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T>;
    getById(id: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<MaterializedSnapshot<S>>;
    getStateById(id: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    getByIds(ids: string[], attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<MaterializedSnapshot<S>[]>;
    getStateByIds(ids: string[], attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S[]>;
}
```

:::

[typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts:117](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts#L117)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)

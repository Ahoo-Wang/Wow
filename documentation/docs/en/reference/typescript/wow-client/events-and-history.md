---
title: 'Events and historical state'
description: 'Events and historical state — @ahoo-wang/wow-client'
---

# Events and historical state

Event queries return event-stream records stored by Wow, while historical state loaders reconstruct/select aggregate state through dedicated endpoints. Neither is a subscription to all future domain changes merely because its type contains the word stream.

| Client / method                             | Endpoint / result                                                                                             |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| EventStreamQueryClient.list / paged / count | POST event/list, event/paged, event/count → records[], PagedList, number                                      |
| listStream                                  | POST event/list with text/event-stream Accept → JSON SSE event-stream records                                 |
| cursor                                      | POST event/cursor → CursorPage; new filter query only                                                         |
| aggregate / aggregateStream                 | POST event/aggregation → flat aggregation rows or JSON SSE rows                                               |
| load(id, headVersion, tailVersion)          | GET `{id}/event/{headVersion}/{tailVersion}` → the aggregate's event streams of those versions, both inclusive |
| loadStream(id, headVersion, tailVersion)    | Same route with text/event-stream Accept → JSON SSE event-stream records                                      |
| LoadStateAggregateClient.load(id)           | GET `{id}/state` → S                                                                                          |
| loadVersioned(id, version)                  | GET `{id}/state/{version}` → S                                                                                |
| loadTimeBased(id, createTime)               | GET `{id}/state/time/{createTime}` → S                                                                        |
| LoadOwnerStateAggregateClient               | Same load variants without id; endpoints start `state`; owner/tenant attribution belongs in configured route. |

All concrete client methods also accept optional attributes and `abort` after their required arguments; `abort` is an `AbortController` or an `AbortSignal` (`AbortSignal.timeout(ms)`, the `signal` a data library passes). EventStreamQueryApi deliberately omits single. `load`/`loadStream` replay one aggregate in version order, as an audit trail or an event-sourcing view does: `headVersion` starts at 1, and the server treats the range as a list query, so more versions than its maximum list size (1000 by default) is refused. That route carries a tenant segment by default but no owner segment, like the load-state routes. The streams (`listStream`, `aggregateStream`, `loadStream`) error with a `WowError` when the server fails midway, so a `for await` throws; see [errors](./errors-and-utilities). No client method covers `GET {id}/state/tracing`; call it through a Fetcher directly. Network, status and parsing errors reject; loaders do not install a local event store or validate the requested version/time range. createTime is a numeric timestamp passed into the path without unit conversion; use the server's epoch-millisecond contract.

A DomainEvent contains id/name/body/bodyType/revision. DomainEventStream has stream identity, aggregate attribution, owner/space, commandId/requestId, createTime/version, header and an array of DomainEvent bodies. Header supports known command/trace fields plus string-valued extensions. StateEvent adds state, first operator/time and deleted. MetadataFields supplies exact logical field paths (including body.body). ReadableDomainEventStream is a ReadableStream of JSON SSE envelopes, not a Promise and not automatically iterated. Cancel/release an acquired reader on early exit; aborting an HTTP controller does not by itself constitute acknowledgement of domain events.

## Complete example

```ts
import {
  LoadStateAggregateClient,
  EventStreamQueryClient,
} from '@ahoo-wang/wow-client';
interface Account {
  balance: number;
}
const history = new LoadStateAggregateClient<Account>({
  basePath: 'account',
});
const events = new EventStreamQueryClient({ basePath: 'account' });
export async function audit(id: string, signal = AbortSignal.timeout(10_000)) {
  const state = await history.loadVersioned(id, 3, undefined, signal);
  const records = await events.load(id, 1, 3, undefined, signal);
  return { state, records };
}
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### DomainEvent {#api-DomainEvent}

```ts
export interface DomainEvent<BODY>
  extends Identifier, Named, BodyCapable<BODY> {
  bodyType: string;
  revision: string;
}
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### DomainEventStreamHeader {#api-DomainEventStreamHeader}

```ts
export interface DomainEventStreamHeader {
  command_operator?: string;
  command_wait_endpoint?: string;
  command_wait_stage?: CommandStage;
  local_first?: string;
  remote_ip?: string;
  user_agent?: string;
  trace_id?: string;
  [key: string]: string | undefined;
}
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### DomainEventStream {#api-DomainEventStream}

```ts
export interface DomainEventStream<DomainEventBody = any>
  extends
    Identifier,
    AggregateId,
    OwnerId,
    SpaceIdCapable,
    CommandId,
    CreateTimeCapable,
    RequestId,
    Version,
    BodyCapable<DomainEvent<DomainEventBody>[]> {
  header: DomainEventStreamHeader;
}
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### StateEvent {#api-StateEvent}

```ts
export interface StateEvent<DomainEventBody = any, S = any>
  extends
    DomainEventStream<DomainEventBody>,
    StateCapable<S>,
    FirstOperatorCapable,
    FirstEventTimeCapable,
    DeletedCapable {}
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### DomainEventStreamMetadataFields {#api-DomainEventStreamMetadataFields}

```ts
export class DomainEventStreamMetadataFields {
  static readonly HEADER = 'header';
  static readonly COMMAND_OPERATOR = `${DomainEventStreamMetadataFields.HEADER}.command_operator`;
  static readonly AGGREGATE_ID = 'aggregateId';
  static readonly TENANT_ID = 'tenantId';
  static readonly OWNER_ID = 'ownerId';
  static readonly SPACE_ID = 'spaceId';
  static readonly COMMAND_ID = 'commandId';
  static readonly REQUEST_ID = 'requestId';
  static readonly VERSION = 'version';
  static readonly BODY = 'body';
  static readonly BODY_ID = `${DomainEventStreamMetadataFields.BODY}.id`;
  static readonly BODY_NAME = `${DomainEventStreamMetadataFields.BODY}.name`;
  static readonly BODY_TYPE = `${DomainEventStreamMetadataFields.BODY}.bodyType`;
  static readonly BODY_REVISION = `${DomainEventStreamMetadataFields.BODY}.revision`;
  static readonly BODY_BODY = `${DomainEventStreamMetadataFields.BODY}.body`;
  static readonly CREATE_TIME = 'createTime';
}
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### ReadableDomainEventStream {#api-ReadableDomainEventStream}

```ts
export type ReadableDomainEventStream = ReadableStream<
  JsonServerSentEvent<DomainEventStream>
>;
```

[typescript/wow-client/src/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts)

### EventStreamQueryApi {#api-EventStreamQueryApi}

```ts
export interface EventStreamQueryApi<
  DomainEventBody = any,
  FIELDS extends string = string,
> extends Omit<
  QueryApi<DomainEventStream<DomainEventBody>, FIELDS>,
  'single'
> {}
```

[typescript/wow-client/src/query/event/eventStreamQueryApi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/eventStreamQueryApi.ts)

### EventStreamQueryClient {#api-EventStreamQueryClient}

```ts
export class EventStreamQueryClient<DomainEventBody = any, FIELDS extends string = string> implements EventStreamQueryApi<DomainEventBody, FIELDS>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    aggregate<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<Row[]>;
    aggregateStream<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
    cursor<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<CursorPage<T>>;
    count(filter: FilterExpression<FIELDS> | Condition<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<number>;
    list<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    listStream<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    paged<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<PagedList<T>>;
    load<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(id: string, headVersion: number, tailVersion: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    loadStream<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(id: string, headVersion: number, tailVersion: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<JsonServerSentEvent<T>>>;
}
```

[typescript/wow-client/src/query/event/eventStreamQueryClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/eventStreamQueryClient.ts)

### LoadStateAggregateClient {#api-LoadStateAggregateClient}

```ts
export class LoadStateAggregateClient<S> implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(id: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadVersioned(id: string, version: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadTimeBased(id: string, createTime: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
}
```

[typescript/wow-client/src/query/state/loadStateAggregateClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadStateAggregateClient.ts)

### LoadOwnerStateAggregateClient {#api-LoadOwnerStateAggregateClient}

```ts
export class LoadOwnerStateAggregateClient<S> implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadVersioned(version: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadTimeBased(createTime: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
}
```

[typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Identity and resource attribution](./identity-and-attribution)

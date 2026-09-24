---
title: 'Client configuration and metadata'
description: 'Client configuration and metadata — @ahoo-wang/wow-client'
---

# Client configuration and metadata

Use `QueryClientFactory<S,FIELDS,DomainEventBody>` when snapshot, event and historical-state clients share an aggregate route. Pass `QueryClientOptions` once and override options per create method. Construction only composes metadata; it does not discover a server, request schema, or validate a deployment.

| Input / method                                   | Contract                                                                        |
| ------------------------------------------------ | ------------------------------------------------------------------------------- |
| contextAlias, resourceAttribution, aggregateName | Joined in this order to make basePath; absent parts are empty.                  |
| basePath                                         | Explicit value overrides the composed path, including an explicit empty string. |
| Other ApiMetadata options                        | Forwarded to decorator execution, including fetcher and request options.        |
| createSnapshotQueryClient                        | SnapshotQueryClient&lt;S,FIELDS&gt;                                             |
| createLoadStateAggregateClient                   | LoadStateAggregateClient&lt;S&gt;                                               |
| createOwnerLoadStateAggregateClient              | LoadOwnerStateAggregateClient&lt;S&gt; (note method spelling)                   |
| createEventStreamQueryClient                     | EventStreamQueryClient&lt;DomainEventBody,FIELDS&gt;                            |

Per-call options shallowly override constructor defaults. `createQueryApiMetadata(options)` exposes the same route construction directly. Supply actual URL path values through the request configuration when a ResourceAttributionPathSpec contains `{tenantId}` or `{ownerId}`. Missing Fetcher registration and request failures belong to decorator/Fetcher execution; no I/O or cleanup occurs when creating these wrappers.

`WowMetadata` is a data model with description and contexts keyed by name. Each BoundedContext has alias, scopes and aggregates; an Aggregate has type, tenantId, id, scopes, command names and event names. Nullable metadata values are explicit nulls. This module exports no `wow()` decorator or automatic metadata fetcher. Read [commands](./commands) and [snapshot queries](./snapshot-queries) for execution.

## Complete example

```ts
import {
  QueryClientFactory,
  createQueryApiMetadata,
} from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const options = { contextAlias: 'accounts', aggregateName: 'user' };
const factory = new QueryClientFactory<User>(options);
const snapshots = factory.createSnapshotQueryClient();
const history = factory.createLoadStateAggregateClient();
console.log(createQueryApiMetadata(options).basePath); // accounts/user
export { snapshots, history };
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### ScopesCapable {#api-ScopesCapable}

```ts
export interface ScopesCapable {
  scopes: string[];
}
```

[typescript/wow-client/src/configuration/wowMetadata.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/configuration/wowMetadata.ts#L16)

### Aggregate {#api-Aggregate}

```ts
export interface Aggregate extends ScopesCapable {
  type: string | null;
  tenantId: string | null;
  id: string | null;
  commands: string[];
  events: string[];
}
```

[typescript/wow-client/src/configuration/wowMetadata.ts:20](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/configuration/wowMetadata.ts#L20)

### BoundedContext {#api-BoundedContext}

```ts
export interface BoundedContext extends ScopesCapable, DescriptionCapable {
  alias: string | null;
  aggregates: Record<string, Aggregate>;
}
```

[typescript/wow-client/src/configuration/wowMetadata.ts:43](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/configuration/wowMetadata.ts#L43)

### WowMetadata {#api-WowMetadata}

```ts
export interface WowMetadata extends DescriptionCapable {
  contexts: Record<string, BoundedContext>;
}
```

[typescript/wow-client/src/configuration/wowMetadata.ts:48](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/configuration/wowMetadata.ts#L48)

### createQueryApiMetadata {#api-createQueryApiMetadata}

```ts
export function createQueryApiMetadata(
  options: QueryClientOptions,
): ApiMetadata;
```

[typescript/wow-client/src/query/queryClients.ts:52](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryClients.ts#L52)

### QueryClientOptions {#api-QueryClientOptions}

```ts
export interface QueryClientOptions
  extends
    PartialBy<ApiMetadata, 'basePath'>,
    Partial<AliasBoundedContext>,
    Partial<AggregateNameCapable> {
  contextAlias?: string;
  resourceAttribution?: ResourceAttributionPathSpec;
}
```

[typescript/wow-client/src/query/queryClients.ts:34](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryClients.ts#L34)

### QueryClientFactory {#api-QueryClientFactory}

```ts
export class QueryClientFactory<S, FIELDS extends string = string, DomainEventBody = any> {
    constructor(private readonly defaultOptions: QueryClientOptions);
    createSnapshotQueryClient(options?: QueryClientOptions): SnapshotQueryClient<S, FIELDS>;
    createLoadStateAggregateClient(options?: QueryClientOptions): LoadStateAggregateClient<S>;
    createOwnerLoadStateAggregateClient(options?: QueryClientOptions): LoadOwnerStateAggregateClient<S>;
    createEventStreamQueryClient<FIELDS extends string = string>(options?: QueryClientOptions): EventStreamQueryClient<DomainEventBody, FIELDS>;
}
```

[typescript/wow-client/src/query/queryClients.ts:65](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryClients.ts#L65)

## Related topics

[Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)

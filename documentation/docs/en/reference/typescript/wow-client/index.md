---
title: 'wow-client reference'
description: '@ahoo-wang/wow-client entry selection, installation and behavior contracts'
---

# wow-client

Use Wow clients with a service that implements the Wow command and query protocols. The builders create serializable data locally; creating a query or client does not send HTTP. Generics describe the expected response, not server authorization, schema validation or projection freshness.

## Choose an entry

| Need                                     | Entry                                                                         | Check first                                                                         |
| ---------------------------------------- | ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| Send a write and inspect its stage       | [CommandClient](./commands#api-CommandClient)                                 | URL identity, command body and wait strategy; HTTP success is not business success. |
| Read current state or snapshots          | [QueryClientFactory](./configuration#api-QueryClientFactory)                  | Choose state-only versus full snapshot result and correct path.                     |
| Construct a query without I/O            | [filter](./filters#api-filter) + [pagedQuery](./query-options#api-pagedQuery) | New filter/legacy condition forms differ; list limits default to 0/10 respectively. |
| Traverse a changing result set           | [Cursor queries](./cursor-queries)                                            | Stable sort and cursor rules accepted by your backend.                              |
| Compute grouped results                  | [Aggregations](./aggregations)                                                | Metric/group expression and server capability; builders do not compute results.     |
| Read an event stream or historical state | [Events and history](./events-and-history)                                    | Event envelopes versus state payloads and stream cleanup.                           |

## Installation prerequisites

```sh
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

The package version follows Wow: `@ahoo-wang/wow-client` x.y.z is released together with Wow x.y.z. The Fetcher peers accept `^5.1 || ^6`. The package requires Node >=22.12.0, as does repository development, which also pins pnpm 10.34.5. The command installs every peer the package declares; direct runtime dependencies are installed automatically.

Coming from `@ahoo-wang/fetcher-wow`? The API is unchanged; follow the [migration guide](../../../guide/typescript/migration.md) to switch imports.

## Core request

Pass a Fetcher configured with your baseURL/authentication. Calling `loadUsers` issues the request and returns `{list,total}`; the service must implement the accounts/user Wow snapshot-state paged endpoint. This example does not contact a public service.

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import { QueryClientFactory, filter, pagedQuery } from '@ahoo-wang/wow-client';

interface User {
  id: string;
  name: string;
}

export async function loadUsers(fetcher: Fetcher) {
  const client = new QueryClientFactory<User>({
    fetcher,
    contextAlias: 'accounts',
    aggregateName: 'user',
  }).createSnapshotQueryClient();
  return client.pagedState(
    pagedQuery({
      filter: filter.matchAll(),
      pagination: { index: 1, size: 20 },
    }),
  );
}
```

## Topics

- [Client configuration and metadata](./configuration)
- [Commands and wait results](./commands)
- [Snapshot queries](./snapshot-queries)
- [Filter expressions and legacy conditions](./filters)
- [Projection, sorting and pagination](./query-options)
- [Cursor queries](./cursor-queries)
- [Aggregation builders](./aggregations)
- [Events and historical state](./events-and-history)
- [Identity and resource attribution](./identity-and-attribution)
- [Message payloads and state metadata](./messages-and-state)
- [Business errors and document utilities](./errors-and-utilities)
- [Legacy operator locales](./operator-locales)
- [Complete symbol index](./symbols)

[State and resource ownership](https://fetcher.ahoo.me/architecture/state-and-resources) · [Failure and cancellation boundaries](https://fetcher.ahoo.me/architecture/failure-model)

# `@ahoo-wang/wow-client`

Typed Fetcher clients and contracts for Wow commands, snapshots, domain events,
filters, pagination, and aggregation. Use it only against Wow HTTP endpoints.

Supported servers: Wow 8.11 and later through the `filter` API; Wow 8.10
through [`/legacy`](#wow-810-servers-ahoo-wangwow-clientlegacy). CI tests the
client against a server of the same version and smoke-tests it against Wow
8.11.5, 9.1.3 and 9.1.5; for 8.10.8 it type-checks generated code only. Node `>=22.12.0` or a
current browser. See the
[compatibility matrix](https://wow.ahoo.me/guide/typescript/compatibility).

Released with Wow 9.2.0, from the same tag and with the same version.

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains.

Peer dependencies: `fetcher`, `fetcher-decorator`, and `fetcher-eventstream`.

## Query

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/wow-client';

interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
}

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const snapshots = new SnapshotQueryClient<CartState>({
  fetcher,
  basePath: 'cart',
});

const carts = await snapshots.listState(
  listQuery({
    filter: filter.and([
      filter.ownerId('u-42'),
      filter.eq('state.status', 'ACTIVE'),
    ]),
    limit: 50,
  }),
);
```

`listQuery()`, `pagedQuery()` and `singleQuery()` match everything when no
filter is given. `listQuery()` sends no `limit` unless you give one, and what
the server does without it depends on its version:

- Wow 9.1.5 and later apply the server's default list size (100 unless
  configured otherwise).
- Wow 8.11 to 9.1.3 reject the query with `IllegalArgument`
  (`limit[0] must be between 1 and 1000`): HTTP 400 for a list, the error
  event that ends a list stream. Pass `limit` explicitly against
  those servers; the `WowError` of that rejection says so.

## Send a command

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import {
  CommandClient,
  CommandStage,
  commandHeaders,
  waitStrategy,
} from '@ahoo-wang/wow-client';

const commands = new CommandClient({ fetcher, basePath: 'cart' });
const result = await commands.send({
  path: 'add_cart_item',
  method: 'POST',
  headers: {
    ...commandHeaders({ ownerId: 'u-42', requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
  },
  body: { productId: 'p-1', quantity: 1 },
});
```

`CommandRequestHeaders` types every command header by what the server parses:
a stage must be a `CommandStage`, a timeout whole milliseconds. `waitStrategy()`
also builds Wow's wait chain (`SAGA_HANDLED` plus a `tail`).
`sendAndWaitStream()` yields one `CommandResult` per stage the command reaches.

## Errors

A server error is a `WowError`, carrying the server's `ErrorInfo`
(`errorCode`, `errorMsg`, `bindingErrors`) and the HTTP status. Match
`errorCode` against `ErrorCodes`. Wow answers a refused request, including a
command whose handler failed, with an HTTP error status, so the call rejects.

```ts
import {
  ErrorCodes,
  toWowError,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

async function findCart(snapshots: SnapshotQueryClient<unknown>, id: string) {
  try {
    return await snapshots.getStateById(id);
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
    throw wowError ?? error;
  }
}
```

- A failed request rejects with the fetcher's error. `toWowError()` reads the
  response's `ErrorInfo` body, or failing that its `Wow-Error-Code` header, and
  returns `undefined` when the Wow server did not answer (network failure,
  timeout, abort, a proxy's own error page).
- A stream (`listStream`, `aggregateStream`, `sendAndWaitStream`, …) that
  fails midway errors with a `WowError`, so `for await` throws it. The server
  answered HTTP 200 and sends the error as the last event. Without this the
  error would look like a row.

## Cancel

Every query method takes an `AbortController` or an `AbortSignal` as its last
argument, after the interceptor attributes:

<!-- typecheck-context
import type { FilterPagedQuery, SnapshotQueryClient } from '@ahoo-wang/wow-client';
declare const snapshots: SnapshotQueryClient<unknown>;
declare const query: FilterPagedQuery;
-->

```ts
const page = await snapshots.pagedState(
  query,
  undefined,
  AbortSignal.timeout(5_000),
);
```

## Build queries without HTTP: `@ahoo-wang/wow-client/dsl`

`filter`, `aggregation`, sort, projection, pagination, cursor queries and the
query factories, without the clients: no Fetcher, no decorators, no
`reflect-metadata`, and none of the stream patches `fetcher-eventstream`
installs. Use it where a bundle only builds queries.

## Wow 8.10 servers: `@ahoo-wang/wow-client/legacy`

The root entry speaks only `FilterExpression`, which Wow 8.11 and later
accept. Wow 8.10 understands only the deprecated Condition model, which this
package keeps on its own subpath until v10:

<!-- typecheck-context
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
declare const snapshots: SnapshotQueryClient<unknown>;
-->

```ts
import { and, eq, listQuery, ownerId } from '@ahoo-wang/wow-client/legacy';

const carts = await snapshots.listState(
  listQuery({ condition: and(ownerId('u-42'), eq('state.status', 'ACTIVE')) }),
);
```

The query clients accept both kinds of query. Everything else comes from the
root entry. The subpath is removed in v10.

## Core capabilities

- Command results and streaming wait stages.
- Snapshot, domain-event, load-state, and owner-state clients; event streams by
  version range (`EventStreamQueryClient.load`); server metadata
  (`WowMetadataClient`).
- Array-first `FilterExpression` builders with early validation.
- Single, list, paged, cursor, count, and stream query contracts.
- Projection, sorting, nested aggregation, modeling, ABAC, and metadata types.
- `aggregation.query()` admits a whole aggregation against the same rules
  Wow enforces on arrival, so a bad query fails where it was built.

Not covered by a client yet: `GET {id}/snapshot`, `GET {id}/state/tracing`
and the `/wow/command/send` route (send it with `CommandClient` and the
`COMMAND_AGGREGATE_CONTEXT`, `COMMAND_AGGREGATE_NAME` and `COMMAND_TYPE`
headers).

## Documentation

- [Quick start: call a Wow service](https://wow.ahoo.me/guide/typescript/quick-start)
- [Error handling](https://wow.ahoo.me/guide/typescript/error-handling) and
  [authentication](https://wow.ahoo.me/guide/typescript/authentication)
- [TypeScript guide](https://wow.ahoo.me/guide/typescript/)
- [wow-client reference](https://wow.ahoo.me/reference/typescript/wow-client/)
- [Interactive query stories](https://wow.ahoo.me/storybook/)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

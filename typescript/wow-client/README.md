# `@ahoo-wang/wow-client`

Typed Fetcher clients and contracts for Wow commands, snapshots, domain events,
filters, pagination, and aggregation. Use it only against Wow HTTP endpoints.

Supported servers: Wow 8.11 and later through the `filter` API; Wow 8.10
through [`/legacy`](#wow-810-servers-ahoo-wangwow-clientlegacy). CI tests the
client against a server of the same version and smoke-tests it against Wow
8.11.5, 9.1.3 and 9.1.5; for 8.10.8 it type-checks generated code only. Node `>=22.12.0` or a
current browser. TypeScript 6 or later: CI tests 6.0 through the latest 7.x. See the
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

Relative time is resolved by the server, never by the browser's clock.
`filter.beforeNow` and `filter.afterNow` compare a time field strictly with
the server's now plus an ISO-8601 `offset` (default `PT0S`; a negative one
looks back), read once per query, so a saved view means the same thing on
every client:

```ts
import { filter } from '@ahoo-wang/wow-client';

const overdue = filter.beforeNow('state.timeoutAt');
const lastHalfHour = filter.afterNow('state.createTime', '-PT30M');
```

`BEFORE_NOW` and `AFTER_NOW` need Wow 9.2.0 or later; an earlier server
rejects the query.

## What the server can query: the capability descriptor

`QueryDescriptorClient` reads `GET {aggregate}/snapshot/schema` and
`GET {aggregate}/event/schema` (Wow 9.2.0 and later): a
`QueryModelDescriptor` listing every queryable field with the filter
operators, sorts and aggregation it admits, which metrics are estimates and
which date-histogram units, date parts and date-difference units apply, what `FIRST` and `LAST`
order by when they name no field, the paging modes, full-text
search (on the record, and inside an element where the storage grants it), and
the entry's limits. Every capability it lists is admitted when
used alone; anything it does not list is rejected. Use it to offer only what
the server accepts, instead of hard-coding operator tables and limits.

<!-- typecheck-context
import type { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import { FilterOperator, QueryDescriptorClient } from '@ahoo-wang/wow-client';

// The schema routes have no tenant or owner segment.
const descriptors = new QueryDescriptorClient({ fetcher, basePath: 'cart' });

const first = await descriptors.describeSnapshot();
if (!first.notModified) {
  const productId = first.descriptor.fields.find(
    field => field.path === 'state.items.productId',
  );
  const canSearchByPrefix = productId?.filter.operators.includes(
    FilterOperator.STARTS_WITH,
  );
}

// Revalidate the copy you hold: 304 answers { notModified: true, version }.
const again = await descriptors.describeSnapshot(first.version);
```

The descriptor does not depend on the caller and its `version` is also the
ETag, so hold it and pass its version (or the ETag) back: the method sends
`If-None-Match` and resolves to `{ notModified: true, version }` on a 304.
`QueryClientFactory.createQueryDescriptorClient()` builds the base path
without the factory's resource attribution. The server reloads a schema
every few minutes, so a long-lived page revalidates rather than caching
forever.

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
- A rejected query (`IllegalArgument` while decoding, `QuerySchemaValidation`
  at admission) says which rule it broke and where: `wowError.violation` is
  `{ code, path, message }`, `code` one of `QueryErrorCodes` and `path` the
  JSON path (`filter.state`) or the logical field (`state.items.sku`). The
  list of codes only grows, so fall back to `errorMsg` for one you do not
  handle. From Wow 9.2 budget and gate rejections carry a code too
  (`SIZE_OUT_OF_RANGE`, `EXPENSIVE_OPERATOR_DISABLED`, …); older servers
  answer them with text alone (`HTTP list query limit[...]`). A failure of
  the server itself answers HTTP 500 `InternalServerError`, without a code.

<!-- typecheck-context
import type { WowError } from '@ahoo-wang/wow-client';
declare const wowError: WowError;
declare function markField(path: string, message: string): void;
-->

```ts
import { QueryErrorCodes } from '@ahoo-wang/wow-client';

const violation = wowError.violation;
if (violation?.code === QueryErrorCodes.UNKNOWN_FIELD) {
  markField(violation.path, violation.message);
}
```

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

`filter`, `aggregation`, sort, projection, pagination, cursor queries, the
query factories and the capability descriptor types, without the clients: no Fetcher, no decorators, no
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
  (`WowMetadataClient`); query capability descriptors with conditional GET
  (`QueryDescriptorClient`).
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

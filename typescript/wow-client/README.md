# `@ahoo-wang/wow-client`

The TypeScript client of a Wow service's routes, built on Fetcher: commands
with their waits, state reads, snapshot, event and dataset queries, the
Manifest, and the RFC 9457 problems the service answers with. The query DSL
(`filter`, `aggregation`, sort, projection, pagination) is on its own entry,
`@ahoo-wang/wow-client/dsl`.

Node `>=22.12.0` or a current browser. TypeScript 6 or later: CI tests 6.0
through the latest 7.x. Released with Wow, from the same tag and with the
same version.

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains.

Peer dependencies: `@ahoo-wang/fetcher` and `@ahoo-wang/fetcher-eventstream`.

Most applications use the clients `wow-generator` writes from a service's
Manifest, which build on the classes below; every class also serves any
route by hand.

## Query

An aggregate's snapshots are queried at `/{context}/{aggregate}/snapshot/query`.
The body names the operation in its `kind`; the client sets it.

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';

interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
}

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const carts = new SnapshotQueryClient<CartState>(
  { context: 'shop', name: 'cart' },
  { fetcher },
);

const active = await carts.list(
  listQuery({ filter: filter.eq('state.status', 'ACTIVE'), limit: 50 }),
);
```

`single`, `list`, `paged`, `cursor`, `count` and `aggregate` each answer one
operation; `listStream` reads a list as server-sent events and resumes a
stream the service cut off where it stopped. `describe(version?)` reads the
query model's capability descriptor (`GET` on the same route): the fields
with the operators, sorts and aggregation each admits, and the limits;
pass the version you hold and a 304 answers `{ notModified: true, version }`.
`EventQueryClient` queries an aggregate's event streams the same way,
`QueryModelClient` any query route, and `DatasetClient` lists the service's
datasets (`GET /wow/dataset`).

Every method takes `RequestOptions` last: the `scope` and `space` to act in,
more `headers`, and the `signal` that cancels the request.

Relative time is resolved by the server, never by the browser's clock:

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const overdue = filter.beforeNow('state.timeoutAt');
const lastHalfHour = filter.afterNow('state.createTime', '-PT30M');
```

## Send a command

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import { CommandGateway, wait } from '@ahoo-wang/wow-client';

const commands = new CommandGateway({ fetcher });
const result = await commands.send(
  '/shop/cart/{id}/add-cart-item',
  { productId: 'p-1', quantity: 1 },
  { id: 'cart-42', wait: { plan: [wait.snapshot()], timeout: 10_000 } },
);
```

The route is `POST /{context}/{aggregate}/{id}/{command}`; a command that may
create its aggregate leaves `{id}` out. `wait` says how far the command
goes before the call answers (`accepted`, `committed`, `snapshot`, or a
subscription's `handled`, also across a saga's derived commands); `stream`
answers one `CommandResult` per hop. Every command carries a request id
(`Wow-Request-Id`), given or generated: a retry with the same one is answered
as the first commit's duplicate, and `requestIdOf(error)` hands back the one
a failure was sent with. `lifecycle` sends the lifecycle commands an
aggregate lists (delete, restore, transfer, tags).

`StateClient` reads an aggregate's state, at a version or a time, and its
trace; `fetchManifest` reads the service's Manifest (`GET /wow/manifest`).

## Errors

The service answers a refusal with an RFC 9457 problem, and the call rejects
with a `WowProblemError`: the `problem` as written, its `status`, its Wow
`code` and its `violations`. A command that did not succeed carries its
`CommandResult` too, and `resolveRejected: true` resolves that result
instead. A stream that fails midway errors with the same error, so a
`for await` throws it.

```ts
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';

declare const error: unknown;
if (
  error instanceof WowProblemError &&
  error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND
) {
  // …
}
```

A rejected query names the rule it broke in each violation: `code` one of
`QueryErrorCodes` (from `/dsl`), `path` the JSON path or the field, and a
`message`. The list of codes only grows, so fall back to the problem's
`detail` for one you do not handle. An error's `retry` says whether and
when the request may be retried, and whether with the same request id.

## Build queries without HTTP: `@ahoo-wang/wow-client/dsl`

`filter`, `aggregation`, sort, projection, pagination, cursor queries, the
query factories, the capability descriptor's types and `QueryErrorCodes`,
without the clients: no Fetcher, and none of the stream patches
`fetcher-eventstream` installs. Use it where a bundle only builds queries.
`aggregation.query()` admits a whole aggregation against the rules Wow
enforces on arrival, so a bad query fails where it was built.

## Documentation

- [TypeScript guide](https://wow.ahoo.me/guide/typescript/)
- [Interactive query stories](https://wow.ahoo.me/storybook/)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

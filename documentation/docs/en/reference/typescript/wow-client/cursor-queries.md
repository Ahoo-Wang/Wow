---
title: 'Cursor queries'
description: 'Cursor queries — @ahoo-wang/wow-client'
---

# Cursor queries

Cursor pagination uses an opaque server token and a filter; it does not use page indexes. `cursorQuery(options)` validates size and sort-count locally and returns a plain CursorQuery. Use SnapshotQueryClient.cursor/cursorState or EventStreamQueryClient.cursor to send it.

| Field / constant    | Contract                                                                          |
| ------------------- | --------------------------------------------------------------------------------- |
| filter              | Required FilterExpression; legacy Condition is not a CursorQuery filter.          |
| projection, sort    | Default {} and []; preserve the same logical query across token continuation.     |
| size                | Default DEFAULT_CURSOR_SIZE = 10; integer 1 through MAX_CURSOR_SIZE = 2147483646, the cursor model's bound. Over HTTP the server enforces its own page limit, 100 by default, and answers a larger size with a 400. |
| sort length         | At most MAX_CURSOR_SORT_FIELDS = 32.                                              |
| cursor              | Default null on first page; later use response.nextCursor unchanged.              |
| CursorPage&lt;T&gt; | `{ list: T[], nextCursor: string \| null }`; null marks no continuation.          |

Invalid size or too many sort fields throws TypeError before network execution. The builder does not decode tokens, validate server capabilities, verify unique sort keys, freeze a database snapshot, or automatically add tie-breakers. Cursor validity, expiry and consistency are server contracts; do not edit or derive the token. Stop based on nextCursor, not list length. A non-null token can require another request even if a page is smaller than requested.

There is no built-in async iterator or cursor-close method in this package. The loop below takes an AbortSignal (from a controller, `AbortSignal.timeout(ms)` or a data library) and stops once it is aborted. Breaking a loop stops future HTTP calls; aborting the signal cancels the current request. It is not a guarantee to close a server-side PIT/session, because no release endpoint is exposed here. Transport errors and invalid/expired cursor responses reject through Fetcher (`toWowError` reads the server's error code); decide explicitly whether to restart from null.

## Complete example

```ts
import {
  SnapshotQueryClient,
  cursorQuery,
  filter,
  asc,
} from '@ahoo-wang/wow-client';
import type { CursorPage } from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export async function readUsers(signal: AbortSignal) {
  let cursor: string | null = null;
  const users: User[] = [];
  do {
    signal.throwIfAborted();
    const page: CursorPage<User> = await client.cursorState(
      cursorQuery({
        filter: filter.matchAll(),
        sort: [asc('state.name')],
        size: 100,
        cursor,
      }),
      undefined,
      signal,
    );
    users.push(...page.list);
    cursor = page.nextCursor;
  } while (cursor !== null);
  return users;
}
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### cursorQuery {#api-cursorQuery}

```ts
export function cursorQuery<FIELDS extends string = string>(
  options: CursorQuery<FIELDS>,
): CursorQuery<FIELDS>;
```

Implementation defaults: `projection = {}`; `sort = []`; `size = DEFAULT_CURSOR_SIZE`; `cursor = null`.

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

### DEFAULT_CURSOR_SIZE {#api-DEFAULT_CURSOR_SIZE}

```ts
declare const DEFAULT_CURSOR_SIZE: 10;
```

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

### MAX_CURSOR_SIZE {#api-MAX_CURSOR_SIZE}

```ts
declare const MAX_CURSOR_SIZE: 2147483646;
```

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

### MAX_CURSOR_SORT_FIELDS {#api-MAX_CURSOR_SORT_FIELDS}

```ts
declare const MAX_CURSOR_SORT_FIELDS: 32;
```

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

### CursorQuery {#api-CursorQuery}

```ts
export interface CursorQuery<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
  projection?: Projection<FIELDS>;
  sort?: FieldSort<FIELDS>[];
  size?: number;
  cursor?: string | null;
}
```

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

### CursorPage {#api-CursorPage}

```ts
export interface CursorPage<T> {
  list: T[];
  nextCursor: string | null;
}
```

[typescript/wow-client/src/dsl/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/cursorQuery.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)

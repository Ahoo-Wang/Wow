---
title: Send a Wow Command and Read State
description: Configure application aggregate routes, inspect command results, and query or stream snapshot state.
---

# Send a Wow Command and Read State

Use this guide with a server that implements Wow commands and snapshot queries. Command completion and a query result are separate contracts; keep both visible to your application.

Installation must also resolve every declared peer dependency; see the [package prerequisites](../../reference/typescript/wow-client/) for the complete graph. Application routes and identities below are supplied by your application, not created by installation.

## 1. Confirm aggregate endpoints

Install `@ahoo-wang/fetcher` and `@ahoo-wang/wow-client`. This example assumes the application exposes an owner-scoped cart at `owner/{ownerId}/cart`, an `add_cart_item` POST command, and Wow snapshot endpoints below that path. Confirm these paths in the server's OpenAPI document, and configure authentication as in [Authentication and Interceptors](./authentication.md). Clients generated from the document already know these paths; see the [Quick Start](./quick-start.md). The route names and state model are application examples.

## 2. Create the command and query clients

<!-- typecheck: file=cart.ts -->

```ts
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import {
  CommandClient,
  CommandStage,
  SnapshotQueryClient,
  commandHeaders,
  filter,
  pagedQuery,
  listQuery,
  waitStrategy,
} from '@ahoo-wang/wow-client';

interface AddCartItem {
  productId: string;
  quantity: number;
}
interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
  items: Array<{ productId: string; quantity: number }>;
}

export function createCartClients(baseURL: string, ownerId: string) {
  const metadata = {
    fetcher: new Fetcher({ baseURL }),
    basePath: 'owner/{ownerId}/cart',
    urlParams: { path: { ownerId } },
  };
  const commands = new CommandClient(metadata);
  const snapshots = new SnapshotQueryClient<CartState>(metadata);
  const active = filter.and([
    filter.ownerId(ownerId),
    filter.eq('state.status', 'ACTIVE'),
  ]);
  return {
    addItem: (body: AddCartItem, requestId: string) =>
      commands.send<AddCartItem>({
        path: 'add_cart_item',
        method: HttpMethod.POST,
        body,
        headers: {
          ...commandHeaders({ requestId }),
          ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
        },
      }),
    loadPage: (signal: AbortSignal) =>
      snapshots.pagedState(
        pagedQuery({ filter: active, pagination: { index: 1, size: 20 } }),
        undefined,
        signal,
      ),
    streamStates: (signal: AbortSignal) =>
      snapshots.listStateStream(
        listQuery({ filter: active, limit: 50 }),
        undefined,
        signal,
      ),
  };
}
```

`createCartClients(apiOrigin, ownerId)` returns functions for adding an item, reading a first page, and streaming up to 50 states. `CommandClient` is not generic: the body type is chosen per `send<C>` call. `commandHeaders()` and `waitStrategy()` build typed command headers and throw `TypeError` on invalid input. `pagedState` returns `PagedList<CartState>` with list and total; state methods unwrap snapshot envelopes but filter field names still address the stored snapshot, hence `state.status`.

## 3. Send once and inspect the result

Wow answers a command it cannot carry out — invalid input, a command handler that throws, a version conflict, a repeated request id — with an HTTP error status, so `send` rejects; it resolves only when the command reached the stage it waited for. `await toWowError(error)` turns the fetcher's error into a `WowError` with the server's `errorCode`, `errorMsg`, `bindingErrors` and HTTP `status`, and returns `undefined` when Wow did not answer at all (network failure, abort). [Error Handling](./error-handling.md) lists every case.

```ts
import { toWowError } from '@ahoo-wang/wow-client';
import type { createCartClients } from './cart';

export async function addBook(clients: ReturnType<typeof createCartClients>) {
  try {
    const result = await clients.addItem(
      { productId: 'book-1', quantity: 2 },
      crypto.randomUUID(),
    );
    return { stage: result.stage, version: result.aggregateVersion };
  } catch (error) {
    const wowError = await toWowError(error);
    if (!wowError) throw error; // network failure, abort, proxy error page
    return { failed: wowError.errorCode, message: wowError.errorMsg };
  }
}
```

`waitStrategy({ stage: CommandStage.SNAPSHOT })` requests that stage; it does not guarantee that every projection is already queryable. Reuse the same request id when retrying a command whose outcome is uncertain, so the server can refuse the duplicate (`ErrorCodes.DUPLICATE_REQUEST_ID`).

Only after deciding how to handle the command result, load the page with `clients.loadPage(signal)`. Each query method takes `abort` as its last argument: an `AbortController`, or an `AbortSignal` — `AbortSignal.timeout(ms)`, or the `signal` a data library such as TanStack Query passes to its query function. Give each independently owned query its own signal and abort it when its UI/task ends.

## 4. Consume a bounded query stream

This standalone variant assumes a non-owner-scoped `cart` query path. Change it to your actual aggregate path:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/wow-client';

export async function printActiveStates(baseURL: string, signal: AbortSignal) {
  const snapshots = new SnapshotQueryClient<{ status: string }>({
    fetcher: new Fetcher({ baseURL }),
    basePath: 'cart',
  });
  const stream = await snapshots.listStateStream(
    listQuery({ filter: filter.eq('state.status', 'ACTIVE'), limit: 50 }),
    undefined,
    signal,
  );
  const reader = stream.getReader();
  let finished = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        finished = true;
        break;
      }
      console.log(value.data.status);
    }
  } finally {
    try {
      if (!finished) await reader.cancel();
    } finally {
      reader.releaseLock();
    }
  }
}
```

The third method argument is `abort` (an AbortController or AbortSignal); the second is optional attributes. Stop a pending request or active stream by aborting it. Both request creation and subsequent reads may reject: when the server fails midway through a stream it still answered HTTP 200, so the error arrives as an error event, and `reader.read()` (or a `for await` loop) throws a `WowError`. Catch the function's promise at the owner. Reader cleanup also runs when rendering/processing a row throws.

## 5. Verify the boundary

Mock fetch and assert command path/body/`Command-Wait-Stage`, query JSON, one-based pagination and Accept for the SSE call. Return a documented command result fixture and a `{list: [], total: 0}` page fixture separately. Do not infer consistency from a mock: confirm command stages and snapshot visibility against your real service in an integration test.

Array-first filter builders require one nonempty array; empty input throws before execution. Use `filter.matchAll()` intentionally for an unfiltered query. For larger result sets choose [cursor queries](../../reference/typescript/wow-client/cursor-queries); for server calculations use [aggregations](../../reference/typescript/wow-client/aggregations). Neither is required to fetch this first page.

See [commands](../../reference/typescript/wow-client/commands), [snapshot queries](../../reference/typescript/wow-client/snapshot-queries), [filters](../../reference/typescript/wow-client/filters), and [pagination/projection/sort](../../reference/typescript/wow-client/query-options).

[snapshotQueryClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/snapshot/snapshotQueryClient.ts) defines the stream arguments.

[Review integration boundaries](https://fetcher.ahoo.me/architecture/integration-decisions); [return to this task group](./index.md).

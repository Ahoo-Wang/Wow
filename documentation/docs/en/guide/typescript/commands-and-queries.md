---
title: Send a Wow Command and Read State
description: Configure application aggregate routes, inspect command results, and query or stream snapshot state.
---

# Send a Wow Command and Read State

Use this guide with a server that implements Wow commands and snapshot queries. Command completion and a query result are separate contracts; keep both visible to your application.

Installation must also resolve every declared peer dependency; see the [package prerequisites](../../reference/typescript/wow-client/) for the complete graph. Application routes and identities below are supplied by your application, not created by installation.

## 1. Confirm aggregate endpoints

Install `@ahoo-wang/fetcher` and `@ahoo-wang/wow-client`. This example assumes the application exposes an owner-scoped cart at `owner/{ownerId}/cart`, an `add_cart_item` POST command, and Wow snapshot endpoints below that path. Confirm these paths in the server's OpenAPI document and configure authentication separately. The route names and state model are application examples.

## 2. Create the command and query clients

```ts
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import {
  CommandClient,
  CommandHeaders,
  CommandStage,
  SnapshotQueryClient,
  filter,
  pagedQuery,
  listQuery,
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
  const commands = new CommandClient<AddCartItem>(metadata);
  const snapshots = new SnapshotQueryClient<CartState>(metadata);
  const active = filter.and([
    filter.ownerId(ownerId),
    filter.eq('state.status', 'ACTIVE'),
  ]);
  return {
    addItem: (body: AddCartItem) =>
      commands.send({
        path: 'add_cart_item',
        method: HttpMethod.POST,
        body,
        headers: { [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT },
      }),
    loadPage: (controller: AbortController) =>
      snapshots.pagedState(
        pagedQuery({ filter: active, pagination: { index: 1, size: 20 } }),
        undefined,
        controller,
      ),
    streamStates: (controller: AbortController) =>
      snapshots.listStateStream(
        listQuery({ filter: active, limit: 50 }),
        undefined,
        controller,
      ),
  };
}
```

`createCartClients(apiOrigin, ownerId)` returns functions for adding an item, reading a first page, and streaming up to 50 states. `pagedState` returns `PagedList<CartState>` with list and total; state methods unwrap snapshot envelopes but filter field names still address the stored snapshot, hence `state.status`.

## 3. Send once and inspect the result

Await `clients.addItem({ productId: 'book-1', quantity: 2 })` and inspect the returned stage, errorCode and errorMsg according to your server's command contract. An HTTP success does not itself establish business success. `WAIT_STAGE: SNAPSHOT` requests that stage; it does not guarantee every projection is already queryable or that failures disappear. Use the server's request-ID policy when retrying a command whose outcome is uncertain.

Only after deciding how to handle the command result, load the page with `clients.loadPage(controller)`. Catch transport failures separately from returned command failures. Pass a newly created AbortController to each independently owned query; call abort when its UI/task ends.

## 4. Consume a bounded query stream

This standalone variant assumes a non-owner-scoped `cart` query path. Change it to your actual aggregate path:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/wow-client';

export async function printActiveStates(
  baseURL: string,
  controller: AbortController,
) {
  const snapshots = new SnapshotQueryClient<{ status: string }>({
    fetcher: new Fetcher({ baseURL }),
    basePath: 'cart',
  });
  const stream = await snapshots.listStateStream(
    listQuery({ filter: filter.eq('state.status', 'ACTIVE'), limit: 50 }),
    undefined,
    controller,
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

The third method argument is the AbortController; the second is optional attributes. Stop a pending request or active stream by aborting the supplied controller. Both request creation and subsequent reads may reject, so catch the function's promise at the owner. Reader cleanup also runs when rendering/processing a row throws.

## 5. Verify the boundary

Mock fetch and assert command path/body/WAIT_STAGE, query JSON, one-based pagination and Accept for the SSE call. Return a documented command result fixture and a `{list: [], total: 0}` page fixture separately. Do not infer consistency from a mock: confirm command stages and snapshot visibility against your real service in an integration test.

Array-first filter builders require one nonempty array; empty input throws before execution. Use `filter.matchAll()` intentionally for an unfiltered query. For larger result sets choose [cursor queries](../../reference/typescript/wow-client/cursor-queries); for server calculations use [aggregations](../../reference/typescript/wow-client/aggregations). Neither is required to fetch this first page.

See [commands](../../reference/typescript/wow-client/commands), [snapshot queries](../../reference/typescript/wow-client/snapshot-queries), [filters](../../reference/typescript/wow-client/filters), and [pagination/projection/sort](../../reference/typescript/wow-client/query-options).

[snapshotQueryClient.ts:326](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts#L326) defines the stream arguments.

[Review integration boundaries](https://fetcher.ahoo.me/architecture/integration-decisions); [return to this task group](./index.md).

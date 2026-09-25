---
title: Error Handling
description: Where a Wow call fails, what the TypeScript client sees in each case, and how to read WowError, binding errors, stream errors, timeouts and retries.
---

# Error Handling

This page answers: **when a call to a Wow service fails, what does my code receive, and what should it do with it?**

A Wow service reports a refused request with an HTTP error status and an `ErrorInfo` body. Fetcher rejects every non-2xx response, so **a business failure is an exception, not a return value**. `@ahoo-wang/wow-client` turns that exception into a `WowError` that carries Wow's error code. Streams are the exception to the exception: they have already answered HTTP 200 when something fails, so the failure travels inside the stream.

## Where a call fails

| Where it fails | What your code receives | `await toWowError(error)` |
|---|---|---|
| The request never got a Wow answer: network failure, DNS, CORS, a fetcher `timeout`, an abort, a proxy's own error page | The promise rejects with the fetcher's `ExchangeError` (or `FetchTimeoutError`, or the abort's `AbortError` as its cause) | `undefined` |
| Wow refused the request: validation, a failed command handler, a version conflict, a missing aggregate, a query it cannot run | The promise rejects with the fetcher's `ExchangeError`; its response has the mapped HTTP status, a `Wow-Error-Code` header and an `ErrorInfo` body | A `WowError` with `errorCode`, `errorMsg`, `bindingErrors` and `status` |
| A query stream or `sendAndWaitStream` fails after it started | Reading the stream throws a `WowError`: `for await` throws, `reader.read()` rejects | Not needed; the error already is a `WowError` |
| A stage of a streamed command fails | A `CommandResult` event whose `errorCode` is not `'Ok'`; the stream goes on to its end | Not applicable |

A plain command (`send`, or a method of a generated `…CommandClient`) never resolves with a failure: the server answers a failed `CommandResult` with the status its error code maps to, so the promise rejects. Its body is still the `CommandResult`, which is an `ErrorInfo`, so `toWowError` reads it the same way.

These are the answers of the example service to a few failures, as `toWowError` reads them:

| Call | `status` | `errorCode` | `bindingErrors` |
|---|---|---|---|
| `addCartItem({ productId: '', quantity: 0 })` | 400 | `CommandValidation` | `quantity`, `productId` |
| `changeQuantity` of a product that is not in the cart | 400 | `IllegalArgument` | none |
| `getStateById` of an unknown id | 404 | `NotFound` | none |
| A command waiting for a stage that does not arrive within `timeoutMs` | 408 | `RequestTimeout` | none |
| A request to a port with nothing listening | — | `toWowError` returns `undefined` | — |

## Read a refused request

```ts
import { ErrorCodes, toWowError, type SnapshotQueryClient } from '@ahoo-wang/wow-client';

interface CartState {
  items: Array<{ productId: string; quantity: number }>;
}

export async function findCart(
  snapshots: SnapshotQueryClient<CartState>,
  id: string,
): Promise<CartState | undefined> {
  try {
    return await snapshots.getStateById(id);
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
    throw wowError ?? error;
  }
}
```

- `toWowError` is asynchronous: it reads the error body, from a clone, so another handler can still read it.
- Rethrow `wowError ?? error`: when Wow did not answer, the original error is the one worth logging.
- Switch on `errorCode` against `ErrorCodes`, not on the HTTP status: several codes share 400. The mapping is in [`ErrorHttpStatusMapping`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMapping.kt); a code the service registers itself defaults to 400.

## Show validation errors on a form

`CommandValidation` carries one `BindingError` per invalid field, `name` being the property path and `msg` the server's message:

```ts
import { ErrorCodes, toWowError } from '@ahoo-wang/wow-client';

export async function submit(
  send: () => Promise<unknown>,
  showFieldError: (field: string, message: string) => void,
): Promise<boolean> {
  try {
    await send();
    return true;
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode !== ErrorCodes.COMMAND_VALIDATION) throw wowError ?? error;
    for (const { name, msg } of wowError.bindingErrors) showFieldError(name, msg);
    return false;
  }
}
```

## Streams

A query stream (`listStream`, `listStateStream`, `aggregateStream`, the event client's `loadStream`) and `CommandClient.sendAndWaitStream` error with a `WowError` when the server fails midway. Wow has already answered HTTP 200 by then, so it sends one last event named after the error code and closes; the client turns that event into the error:

```ts
import { WowError, listQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';

export async function readAll<S>(snapshots: SnapshotQueryClient<S>, signal: AbortSignal) {
  const states: S[] = [];
  try {
    for await (const event of await snapshots.listStateStream(listQuery(), undefined, signal)) {
      states.push(event.data);
    }
  } catch (error) {
    if (error instanceof WowError) console.warn(error.errorCode, error.errorMsg);
    throw error;
  }
  return states;
}
```

A streamed command reports each stage it reaches as a `CommandResult`. A stage whose handler failed is such a result with its own `errorCode`, not an error, so check every result:

```ts
import { CommandStage, ErrorCodes, waitStrategy, type CommandClient } from '@ahoo-wang/wow-client';

export async function addAndFollow(commands: CommandClient) {
  const results = await commands.sendAndWaitStream({
    path: 'add_cart_item',
    method: 'POST',
    headers: waitStrategy({ stage: CommandStage.PROJECTED, timeoutMs: 10_000 }),
    body: { productId: 'book-1', quantity: 1 },
  });
  for await (const { data } of results) {
    if (data.errorCode !== ErrorCodes.SUCCEEDED) {
      throw new Error(`${data.stage} failed: ${data.errorCode} ${data.errorMsg}`);
    }
  }
}
```

The generated `…StreamCommandClient` classes behave the same way: they take wow-client's `COMMAND_STREAM_ENDPOINT`, so a server error that ends the stream, such as a wait timeout (`RequestTimeout`) or a command that fails validation, errors the stream with a `WowError` and a `for await` throws. A command result whose own `errorCode` is not `Ok` is still a result, as above.

## Timeouts and cancellation

Every query method takes an `AbortController` or an `AbortSignal` as its last argument; commands take `signal` or `timeout` in their request. An aborted or timed-out call rejects with the fetcher's error and `toWowError` answers `undefined`: the server may or may not have acted on it.

```ts
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';

export function loadWithin<S>(snapshots: SnapshotQueryClient<S>, id: string, ms: number) {
  return snapshots.getStateById(id, undefined, AbortSignal.timeout(ms));
}
```

The command's own `waitStrategy({ timeoutMs })` is different: it tells the server how long to wait for the stage, and the server answers `RequestTimeout` (408) when the stage does not arrive. The command itself may still complete later.

## Retry

| Situation | Retry? |
|---|---|
| No Wow answer (network, timeout, abort) on a **query** | Yes |
| No Wow answer on a **command** | Only with the **same** `requestId`: the server applies a request id once and answers a repeat with `DuplicateRequestId` |
| `RequestTimeout` (408) on a command | No: the server received the command, which may still complete. Read the state to learn its outcome |
| `DuplicateRequestId` | Do not resend; the first attempt reached the server. Read the state to learn its outcome |
| `TooManyRequests` (429) | Yes, after a delay |
| `EventVersionConflict`, `CommandExpectVersionConflict` (409) | Reload the state and decide again; resending the same command conflicts again |
| `CommandValidation`, `IllegalArgument`, `NotFound`, other 4xx | No: the request itself is wrong |
| `InternalServerError` and other 5xx | Only for idempotent calls, and with a limit |

```ts
import {
  CommandStage,
  commandHeaders,
  toWowError,
  waitStrategy,
  type CommandRequestHeaders,
} from '@ahoo-wang/wow-client';

export async function sendOnce<R>(
  send: (headers: CommandRequestHeaders) => Promise<R>,
  attempts = 3,
): Promise<R> {
  const headers: CommandRequestHeaders = {
    ...commandHeaders({ requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.PROCESSED }),
  };
  for (let attempt = 1; ; attempt++) {
    try {
      return await send(headers); // the same requestId on every attempt
    } catch (error) {
      if ((await toWowError(error)) || attempt >= attempts) throw error; // Wow answered: do not resend
    }
  }
}
```

## Where to read more

- [Errors and document utilities](../../reference/typescript/wow-client/errors-and-utilities.md): `WowError`, `toWowError`, `ErrorCodes` and the stream extractors, signature by signature.
- [Commands and wait results](../../reference/typescript/wow-client/commands.md): `CommandResult` and the wait stages.
- [Completion semantics](../command/completion.md) and [Troubleshooting](../troubleshooting.md) on the server side.
- [Troubleshooting the TypeScript client](./troubleshooting.md): symptoms by message.

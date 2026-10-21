# Commands

A command is sent to `POST /{context}/{aggregate}/{id}/{command}` (a command that may create its aggregate also has the route without `{id}`). The call answers when the command reaches the stage its wait plan names, with a `CommandResult`, or rejects with a `WowProblemError`.

## The generated client

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { wait } from '@ahoo-wang/wow-client';
import { BasketCommandClient } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 30_000 });
const basket = new BasketCommandClient({ fetcher });

const result = await basket.addBasketItem(
  { productId: 'p-1', quantity: 2, unitPrice: '10.00' },
  { id: 'buyer-1', wait: { plan: [wait.snapshot()], timeout: 10_000 } },
);
// result.version: the aggregate's version after the commit.
```

- Methods take the body, then options: `id` (the aggregate; for a command with an id route it is the first parameter), `wait`, `requestId`, `expectedVersion` (`If-Match`), `scope`, `space`, `headers`, `signal`.
- `CommandGateway` (`@ahoo-wang/wow-client`) is what the generated clients build on: `send(path, body, options)` and `stream(...)` for a route by hand. A hand-written call passes `bodyNumbers` (the aggregate's `textNumbers.commands[<Command>]`) for exact numbers.

## How far to wait

`wait.plan` lists the hops, in order (`Wow-Wait`):

| Hop | Answers when | Use for |
| --- | --- | --- |
| `wait.accepted()` | The command passed admission (202) | Fire and forget, with a later read |
| `wait.committed()` | Its events are in the log | The write is durable; reads may lag |
| `wait.snapshot()` (the default) | The snapshot holds the commit | Read your own write right after |
| `wait.handled('Processor.function', context?)` | That subscription handled the commit | A saga's next step, a projection the screen reads |

A plan may follow a saga across contexts: `[wait.committed(), wait.handled('BasketCheckoutSaga.onBasketCheckedOut'), wait.committed('eshop-ordering')]`; the generated `<context>Wait` builders name the subscriptions. `timeout` bounds it (`Wow-Wait-Timeout`). The `…Stream` methods answer one `CommandResult` per hop as server-sent events; `chainEnded` with `pending` says the chain stopped early (a saga derived nothing).

## Retries

Every command carries a request id (`Wow-Request-Id`), given or generated. A retry with the same id is answered as the first commit's duplicate (`duplicate: true`), never applied twice. On a failure, `requestIdOf(error)` gives the id it was sent with, and `error.retry` says whether a retry may help (`retryable`), when (`after`) and whether with the same id (`sameRequestId`). In particular:

- `wow.wait.timeout` (504): the command may have committed. Read the state (or retry with the same id); never send it again with a new id.
- `wow.command.unknown` (503), `wow.command.contended` (409), `wow.storage.unavailable` (503): retry with the same id.
- `wow.aggregate.version-mismatch` (409, 412 with `expectedVersion`): reload and decide again; a blind retry repeats the conflict.
- `wow.command.busy` (429): back off by `Retry-After`.

## Errors

```ts
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';
import { EshopBasketErrorCodes } from './generated/index.js';

declare const error: unknown;
if (error instanceof WowProblemError) {
  if (error.code === EshopBasketErrorCodes.BASKET_EMPTY) {
    // a rejection: the domain refused, say so in the audience's words
  } else if (error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND) {
    // …
  }
}
```

- `WowProblemError` carries the RFC 9457 `problem`, its `status`, the Wow `code`, `violations` (field-level reasons, `path` and `message`) and `retry`; a command that did not succeed carries its `CommandResult` too. `resolveRejected: true` resolves the result instead of rejecting.
- A domain rejection's code is `<context>.<aggregate>.<code>`, listed in the generated `<Context>ErrorCodes`; show the problem's `detail` for a code the screen does not handle.
- A stream that fails midway errors with the same error, so a `for await` throws it.

## Lifecycle commands

An aggregate lists the lifecycle commands it accepts (`LifecycleCommands`: delete, restore, transfer owner, transfer space, apply tags); the generated client has a method for each (`deleteAggregate(id)` sends `DELETE /{context}/{aggregate}/{id}`).

# Commands

A command is sent to `POST /{context}/{aggregate}/{id}/{command}` (a command that may create its aggregate also has the route without `{id}`, where the server generates the id; every command of an aggregate whose id is its owner's, such as the eShop's basket, has it too, meaning the caller's own aggregate, and nothing is generated; a command whose body names its aggregate, `@AggregateId` on the server and `idProperty` in the Manifest, has only the route without `{id}`, and acts on the aggregate its body names). The call answers when the command reaches the stage its wait plan names, with a `CommandResult`, or rejects: a rejected command with a `CommandRejectedError`, anything else with a `WowProblemError`.

## The generated client

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { WaitPlan } from '@ahoo-wang/wow-client';
import { BasketCommandClient } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 30_000 });
const basket = new BasketCommandClient({ fetcher });

// The basket's id is its buyer's: the caller's own, by no id (addBasketItem('buyer-1', body) addresses it by id).
const result = await basket.addBasketItemOwn(
  { productId: 'p-1', quantity: 2, unitPrice: '10.00' },
  { wait: WaitPlan.SNAPSHOT, timeout: 10_000 },
);
// result.version: the aggregate's version after the commit.
```

- Methods take the body, then options: `id` (the aggregate; for a command with an id route it is the first parameter), `wait` (a `WaitPlan`), `timeout` (milliseconds, over the plan's own), `requestId`, `expectedVersion` (`If-Match`), `targetTenant` and `targetScope` (`{ ownerId?, spaceId? }`, `Wow-Scope`), `space`, `headers`, `signal`. A method answers as `CommandClient.wait` does: the server's default plan unless `wait` is given; `wait: WaitPlan.ACCEPTED` answers once accepted.
- `CommandClient` (`@ahoo-wang/wow-client`) is what the generated clients build on, Kotlin's `CommandClient`: each method takes one `CommandRequest` `{ command, body?, id?, type?, wait?, timeout?, … }`, `command` the full type name `context.aggregate.Command` the route is found by, in the Manifest the client was given (`new CommandClient(options, { manifest })`; a generated client passes its context's `manifest.ts`), else in the service's, fetched once. `send(request)` answers once accepted, `wait(request)` with the result its plan ends with, `stream(request)` one result per hop. A lifecycle command (`LifecycleCommands.DELETE_AGGREGATE`, …) also needs `type: { context, name }` and `id`. For a command whose body names its aggregate, an `id` must be the body's: the client writes it into a body that names none, and throws a `TypeError` for a body that names another, sending nothing; a computed id is the service's, so an `id` given for it is ignored: leave it out. A hand-written client passes `bodyNumbers` in its options, by full command name (the aggregate's `textNumbers.commands[<Command>]`), for exact numbers.
- `<Context>Client` (generated `client.ts`) holds a `CommandClient` (`.commands`) and every aggregate's clients (`.basket.commands`, `.basket.snapshots`, …) over one set of options.

## How far to wait

`wait` is a `WaitPlan` (`Wow-Wait`): `WaitPlan.ACCEPTED`, `COMMITTED`, `SNAPSHOT`, or `WaitPlan.of(...hops)` of these hops, in order, `.within(ms)` its timeout:

| Hop | Answers when | Use for |
| --- | --- | --- |
| `wait.accepted()` | The command passed admission (202) | Fire and forget, with a later read |
| `wait.committed()` | Its events are in the log | The write is durable; reads may lag |
| `wait.snapshot()` (the default) | The snapshot holds the commit | Read your own write right after |
| `wait.handled('Processor.function', context?)` | That subscription handled the commit | A saga's next step, a projection the screen reads |

A plan may follow a saga across contexts: `WaitPlan.of(wait.committed(), wait.handled('BasketCheckoutSaga.onBasketCheckedOut'), wait.committed('eshop-ordering'))`; the generated `<context>Wait` builders name the subscriptions. `timeout` bounds it, in milliseconds (`Wow-Wait-Timeout`). The `…Stream` methods answer one `CommandResult` per hop as server-sent events; `chainEnded` with `pending` says the chain stopped early (a saga derived nothing).

## Retries

Every command carries a request id (`Wow-Request-Id`), given or generated. A retry with the same id is answered as the first commit's duplicate (`duplicate: true`), never applied twice. On a failure, `requestIdOf(error)` gives the id it was sent with, and `error.retry` says what a retry may do (`none`, `same-request` with the same id, or `new-request`), and `error.retryAfterMs` when. In particular:

- `wow.wait.timeout` (504): the command may have committed. Read the state (or retry with the same id); never send it again with a new id.
- `wow.command.unknown` (503), `wow.command.contended` (409), `wow.storage.unavailable` (503): retry with the same id.
- `wow.aggregate.version-mismatch` (409, 412 with `expectedVersion`): reload and decide again; a blind retry repeats the conflict.
- `wow.command.busy` (429): back off by `Retry-After`.

## Errors

```ts
import {
  CommandRejectedError,
  FrameworkErrorCodes,
  WowProblemError,
} from '@ahoo-wang/wow-client';
import { EshopBasketContract } from './generated/index.js';

declare const error: unknown;
if (error instanceof CommandRejectedError) {
  if (error.code === EshopBasketContract.Basket.Errors.EMPTY) {
    // the domain refused: say so in the audience's words
  }
} else if (error instanceof WowProblemError) {
  if (error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND) {
    // …
  }
}
```

- The two errors are mapped by the result's status, as Kotlin's: a rejected result (the aggregate's refusal, or an admission refusal: unknown, invalid or forbidden) is a `CommandRejectedError` with its `code`, its `result` (`ref.id` `null` when no id was decided) and the `problem` when one came; anything else (refused before admission, a technical failure, a failed result) is a `WowProblemError` with the RFC 9457 `problem`, its `status`, the Wow `code`, `violations` (field-level reasons, `path` and `message`), `retry`, and the `result` when the service sent one.
- A domain rejection's code is `<context>.<aggregate>.<code>`, listed in the generated `<Context>Contract.<Aggregate>.Errors` (the same object as `<Aggregate>Aggregate.Errors`; context-level codes in `<Context>Contract.Errors`); show the problem's `detail` for a code the screen does not handle.
- A stream that fails midway errors with the same error, so a `for await` throws it.

## Lifecycle commands

An aggregate lists the lifecycle commands it accepts (`LifecycleCommands`: delete, restore, transfer owner, transfer space, apply tags); the generated client has a method for each (`deleteAggregate(id)` sends `DELETE /{context}/{aggregate}/{id}`).

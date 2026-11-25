---
title: Send Commands
description: Send a command through a generated client, wait for what it leads to across sagas and contexts, follow the wait as a stream, and handle the result or the problem it ends with.
---

# Send Commands

A generated command client has one method per command of its aggregate. A call posts the command, waits as far as the request asks, and resolves with the `CommandResult` the wait ends with, or rejects with a `CommandRejectedError` or a `WowProblemError` ([When a command fails](#when-a-command-fails)). This page uses the eShop's generated clients ([Generate a Client](./generate)).

<!-- typecheck-generated: examples/eshop/web/src/generated -->

## Create the clients once

Every client sends through a Fetcher: the service's base URL, and the interceptors that sign each request ([Authentication](./authentication)). Create the Fetcher once per service, and the clients over it:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import type { WowClientOptions } from '@ahoo-wang/wow-client';
import {
  OrderCommandClient,
  OrderStateClient,
} from './generated/ordering/index.js';

const ordering: WowClientOptions = {
  fetcher: new Fetcher({ baseURL: 'https://ordering.example.com' }),
};

export const orders = new OrderCommandClient(ordering);
export const orderStates = new OrderStateClient(ordering);
```

Each route starts with its context, `/eshop-ordering/order/…`, so one Fetcher at a gateway that routes by that prefix serves every service; `basePath` puts a prefix of the gateway's own before every route.

The generated clients send by the routes of their context's `manifest.ts`, the `<Context>Manifest` the generator writes from the service's Manifest, so they never fetch it at run time. A `CommandClient` you build yourself, `new CommandClient(options, { manifest })`, takes such a Manifest too; without one it fetches `GET /wow/manifest` once for every command waiting on it, and again only after a fetch that failed. Each command's wait is its own: its `signal` ends it while the fetch goes on for the others, and a failed fetch rejects each command with an error of its own (the same problem, or an `Error` that names the fetch and keeps its cause), carrying that command's request id. `CommandClient` is the client's name, as in Kotlin: the server's admission is what Wow calls the command gateway.

## Send a command

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
const result = await orders.submitOrder({
  items: [{ productId: 'p-1', quantity: 2, unitPrice: '19.90' }],
});
console.log(result.ref.id, result.version, result.status);
```

`SubmitOrder` creates its aggregate, so its method takes no id: the service generates one, and `result.ref.id` names it. Pass `{ id }` in the options to choose it. A command on an existing aggregate takes its id first, `orders.payOrder(id, body)`. An aggregate whose id is its owner's, such as a shopper's basket, also has an `…Own` method without the id, `baskets.checkoutBasketOwn(body)`, which acts on the caller's own aggregate; its method by id is for a caller with no owner, such as a back office acting for a shopper. `unitPrice` is a `BigDecimal` on the server, so its type is `string`, written into the body as an exact number.

The result says where the command got to:

| Member | Holds |
| --- | --- |
| `status` | `succeeded`; a call that did not succeed rejects instead ([When a command fails](#when-a-command-fails)) |
| `ref` | The aggregate: its type, tenant and id |
| `version` | The aggregate's version after the commit |
| `hop` | The hop of the wait this result answers |
| `results` | What the command handler put in its result |
| `duplicate` | `true` when the request id was already committed, and this answers the first commit |
| `requestId`, `commandId`, `correlationId` | The ids of the request, the command and the chain it started |

### A body that names its aggregate

A command whose body names its aggregate (its class's `@AggregateId`, the Manifest's `idProperty`) has no id parameter and no `id` option. Its method sends the body to the route without `{id}`, and the service acts on the aggregate the body names. When that property is computed from the other fields, the body may leave it out and the service computes the id, as for any [computed property](#a-computed-property).

A `CommandClient` request written by hand sends such a command by the same route. The eShop has none, so this one sends the `RestockProduct` of [Name the aggregate in the body](../guide/aggregates#name-the-aggregate-in-the-body):

<!-- typecheck-context
import type { CommandClient } from '@ahoo-wang/wow-client';
declare const commands: CommandClient;
-->

```ts
await commands.wait({
  command: 'eshop-inventory.stock.RestockProduct',
  body: { productId: 'p-1', quantity: 2 },
});
```

An `id` given with such a request must be the one its body names: the client writes it into a body that names none, and a body that names another id throws a `TypeError` before anything is sent. A computed id is the service's: an `id` given for it is ignored, because the service computes the id from the fields, so leave `id` out.

### A computed property

A computed property of a command's body, a getter with no field behind it (`val searchCode: String get() = "$code|$brandName"`), is the service's: Wow's JSON writes it and reads nothing into it, so a body may leave it out, at any depth, and a value given for it is ignored. The schema still lists it as required, since every value the service writes holds it, so the generated client takes such a body as an input type of its own: `CreateOrderInput` beside `CreateOrder`, `ProductRefInput` beside `ProductRef`, for every type of the body that holds a computed property, nested, in a list, in a map or in one of its alternatives. In the input a computed property is optional and still `readonly`: `createOrder({ product: { id, code, brandName } })` compiles, and so does a value read from a state, which holds the property, handed back as it is. `CreateOrder` and `ProductRef` are what the service writes, with the property required. So annotate a body you build with the type the method takes (`CreateOrderInput`, the method's parameter type), not with the bare name. A body that holds no computed property has no input type, and the method takes its type as it is. A name the Manifest already gives one of its own types is numbered (`ProductRefInput2`).

## Wait for what the command leads to

Without a wait, the call answers once the snapshot holds the commit, or the commit where the aggregate keeps no snapshot, within the service's default timeout. `wait` says how far to go, a `WaitPlan` as Kotlin's: `WaitPlan.ACCEPTED`, `COMMITTED` and `SNAPSHOT`, or `WaitPlan.of(...hops)` for a plan of your own, and `.within(ms)` for its timeout:

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import { WaitPlan } from '@ahoo-wang/wow-client';

const accepted = await orders.cancelOrder(
  'order-1',
  { reason: 'changed my mind' },
  { wait: WaitPlan.ACCEPTED },
);
```

The hops a plan is made of:

| Hop | Answers once |
| --- | --- |
| `Hop.accepted()` | The command passed admission; the route answers 202 |
| `Hop.committed()` | Its events are in the log |
| `Hop.snapshot()` | The snapshot holds the commit, so a read sees it |
| `Hop.queryable()` | The store the aggregate's snapshot queries read holds the commit, so a list reloaded next sees it; later than `snapshot` where those queries read a copy |
| `Hop.handled('Processor.function')` | That subscription handled the commit |

A plan follows the command further: through a saga's `handled` hop to the commands the saga sent, in their own contexts. Each context's contract holds its hops, `<Context>Contract.Wait`, typed with the names of its subscriptions. Checking a basket out, waiting until the order it submits is committed in the ordering context:

<!-- typecheck-context
import { BasketCommandClient } from './generated/basket/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
import { WaitPlan } from '@ahoo-wang/wow-client';
import { EshopBasketContract } from './generated/basket/index.js';
import { EshopOrderingContract } from './generated/ordering/index.js';

const placed = await baskets.checkoutBasketOwn(
  {},
  {
    wait: WaitPlan.of(
      EshopBasketContract.Wait.committed(),
      EshopBasketContract.Wait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      EshopOrderingContract.Wait.committed(),
    ),
    timeout: 10_000,
  },
);
console.log(`order ${placed.ref.id} at version ${placed.version}`);
```

The call answers with the last hop's result: here the order's commit, so `placed.ref` is the order. `timeout` is in milliseconds, over the plan's own (10 seconds unless `.within` says otherwise), and every plan states its timeout as `Wow-Wait-Timeout`, as the Kotlin client does; `send` states its acceptance's, 10 seconds unless `timeout` says otherwise. `parseWaitPlan` reads a `Wow-Wait` value as the service reads it, refusing one longer than 4,096 characters or of more than 32 hops, and `formatWaitPlan` writes one. A wait that runs out rejects with 504 `wow.wait.timeout`; the command may still have committed. [Waits](../concepts/waits) explains how the service follows a chain.

## Follow the wait as a stream

Each command method has a `…Stream` twin that answers one result per hop as the wait advances, for a page that shows the progress of a long chain. The lifecycle methods, `deleteAggregate` and the others, have none.

<!-- typecheck-context
import { WaitPlan } from '@ahoo-wang/wow-client';
import { BasketCommandClient, EshopBasketContract } from './generated/basket/index.js';
import { EshopOrderingContract } from './generated/ordering/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
const stream = await baskets.checkoutBasketOwnStream(
  {},
  {
    wait: WaitPlan.of(
      EshopBasketContract.Wait.committed(),
      EshopBasketContract.Wait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      EshopOrderingContract.Wait.committed(),
    ),
  },
);
const reader = stream.getReader();
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  console.log(value.hop.stage, value.status);
  if (value.status !== 'succeeded' || value.chainEnded) break;
}
```

A command refused at admission rejects the call before any result streams, with a `WowProblemError` whose `result.status` is `rejected`: a stream call does not turn it into a `CommandRejectedError` as `wait` does, so check `error.result?.status`. A rejection after admission is the stream's last result, with its `status` and `error`. When a saga sent nothing further, the chain ends before the plan does: that result has `chainEnded`, and `pending` names the hops that will not come. The eShop's checkout page follows its fifteen-hop chain to the payment this way (`examples/eshop/web/src/shop/checkout.ts`). The stream closes on the service's `end` event of reason `complete`, after the last result, which is no result. An event the client does not know, which a newer service may send, is skipped; a result of a stage it does not know is still a result, its `hop.stage` as the service wrote it. A stream that ends without `end`, however cleanly, one whose `end` gives another reason, one whose connection breaks after it began, and one whose result is no JSON reject the read with a `WowProblemError` of `wow.command.unknown` (`retry` `same-request`), the network's error its `cause`: the command may have been committed, so send it again with the request id it was sent with, which `requestIdOf(error)` hands back.

## When a command fails

A call that did not succeed rejects, mapped by the result's status as Kotlin's `CommandClient` maps it:

- A rejected command, by its aggregate (the domain's code) or at admission (an unknown command, an invalid one, a caller the authorizer forbids), rejects with a `CommandRejectedError`: its `code`, its `result` (whose `ref.id` is `null` when no aggregate id was decided yet), and the `problem` when the service answered one. Sending it again with the same request id is rejected again.
- Anything else rejects with a `WowProblemError`: a request refused before admission (a body that does not parse, a caller the edge refuses), a technical failure (a busy or closing node, a store that is down, a wait that ran out), a failed result. It holds the RFC 9457 problem, its `status`, its Wow `code`, its `violations`, the `result` when the service sent one, and `retry`.

Compare the code with the generated contract's `<Context>Contract.<Aggregate>.Errors` for the domain's rejections and with `FrameworkErrorCodes` for the framework's:

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import {
  CommandRejectedError,
  FrameworkErrorCodes,
  WowProblemError,
} from '@ahoo-wang/wow-client';
import { EshopOrderingContract } from './generated/ordering/index.js';

try {
  await orders.payOrder('order-1', { amount: '39.80', paymentId: 'pay-1' });
} catch (error) {
  if (error instanceof CommandRejectedError) {
    switch (error.code) {
      case EshopOrderingContract.Order.Errors.WRONG_AMOUNT:
        console.warn(error.result.error?.message);
        break;
      case FrameworkErrorCodes.COMMAND_INVALID:
        for (const violation of error.result.error?.violations ?? [])
          console.warn(violation.path, violation.message);
        break;
      default:
        throw error;
    }
  } else if (!(error instanceof WowProblemError) || error.retry === 'none') {
    throw error;
  }
}
```

A command's whole answer is bounded: by the wait its plan states and 5 seconds more, as the Kotlin client's is. One with no plan, neither `wait` nor `timeout`, states no wait and waits the service's default (`wow.wait.default-timeout`), which the client does not know, so it is bounded by the client options' `defaultWaitMs` and the same 5 seconds: a minute when absent, the longest default a service takes unless its `wow.wait.max-timeout` is raised. A deployment whose services wait longer by default sets `defaultWaitMs` in the options it builds its clients with (`new OrderCommandClient({ fetcher, defaultWaitMs: 120_000 })`), or states each command's wait. The service answers `wow.wait.timeout` at the wait's end, so a node that has not answered by then never will: the request is aborted, its connection closed, and the call rejects with `wow.command.unknown`. So does a `wait` or `send` whose answer is lost, or cannot be read, after it began. An abort of the request's own `signal` rejects with the abort's reason, which `requestIdOf` reads too.

A `WowProblemError`'s `retry` says what a retry may do, as the error catalog declares it for its code: `none`, a retry fails alike; `same-request`, send it again with the same request id; `new-request`, send it again as it is. A `retry` value this client does not know, a newer service's, reads as absent: the catalog's for the code, as the Kotlin client reads it. `retryAfterMs` says how long to wait first (`Retry-After`, delay seconds or an HTTP date; anything else reads as absent, so the catalog's wait applies). Every command carries a request id (`Wow-Request-Id`): the one in the options, or one the client generates, a UUIDv7 (`newRequestId`, ordered by the millisecond it was made in, as the Kotlin client's are), which either error's `requestId` hands back. A retry with the same id is answered as the first commit's duplicate, never applied twice, so retry `wow.command.unknown` and `wow.wait.timeout` with it. Pass `requestId` from the start when the retry may come from elsewhere, a page reload say:

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import { newRequestId } from '@ahoo-wang/wow-client';

const requestId = newRequestId();
await orders.shipOrder('order-1', {}, { requestId, expectedVersion: 4 });
```

`expectedVersion` sends `If-Match`: a command on an aggregate at another version is refused with `wow.aggregate.version-mismatch`. [Error Codes](../reference/error-codes) lists every framework code with its status.

## Lifecycle commands, scope and space

An aggregate's client also sends the lifecycle commands its Manifest lists: `deleteAggregate`, `restoreAggregate`, `transferOwner` and `transferSpace`.

Who else may read or change an aggregate follows its access attributes, which its state declares (`@Access`), and the service's access policy ([Ownership and Access](../guide/ownership#write-an-access-policy)): a grant is a command of the aggregate's own, such as `shareDocument`, not a lifecycle command. A record carries the attributes as `access`, typed by the generated `<Aggregate>Access` ([Query snapshots](./queries#query-snapshots)).

A 10.0.0 pre-release's clients had `applyTags`, and its records `tags`. To move a client to 10.0.0:

1. Regenerate it with this `wow-generator`, from the service's Manifest.
2. Where it applied tags, send the aggregate's own command that changes the attribute.
3. Where it read `tags`, read `access.<name>`. An integer attribute is a `string`, its exact text.
4. Where a query filtered on `tags`, filter on `access.<name>`, and on event streams on `access.owner` and `access.space`.

Every method takes the request options last. `space` picks one of the caller's spaces (`Wow-Space`; a CoSec service reads `CoSec-Space-Id` instead); `targetTenant` and `targetScope` (`{ ownerId?, spaceId? }`) act in another tenant, owner or space (`Wow-Scope`), for a caller allowed to act across scopes; `headers` adds headers of your own, and refuses, before anything is sent, one the client writes from the request's own fields (`Wow-Request-Id`, `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Scope`, `Wow-Space`, `If-Match`, `If-None-Match`, `Last-Event-ID`, in any case): set the field, so the request id `requestIdOf` hands back is the one sent; `signal` cancels the request. Which caller a request is, and what it may do, is the service's to decide from its credentials ([Identity](../concepts/identity)).

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

`SubmitOrder` creates its aggregate, so its method takes no id: the service generates one, and `result.ref.id` names it. Pass `{ id }` in the options to choose it. A command on an existing aggregate takes its id first, `orders.payOrder(id, body)`. `unitPrice` is a `BigDecimal` on the server, so its type is `string`, written into the body as an exact number.

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
| `wait.accepted()` | The command passed admission; the route answers 202 |
| `wait.committed()` | Its events are in the log |
| `wait.snapshot()` | The snapshot holds the commit, so a read sees it |
| `wait.handled('Processor.function')` | That subscription handled the commit |

A plan follows the command further: through a saga's `handled` hop to the commands the saga sent, in their own contexts. Each context's `contract.ts` holds its hops, typed with the names of its subscriptions. Checking a basket out, waiting until the order it submits is committed in the ordering context:

<!-- typecheck-context
import { BasketCommandClient } from './generated/basket/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
import { WaitPlan } from '@ahoo-wang/wow-client';
import { eshopBasketWait } from './generated/basket/index.js';
import { eshopOrderingWait } from './generated/ordering/index.js';

const placed = await baskets.checkoutBasket(
  'buyer-1',
  {},
  {
    wait: WaitPlan.of(
      eshopBasketWait.committed(),
      eshopBasketWait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      eshopOrderingWait.committed(),
    ),
    timeout: 10_000,
  },
);
console.log(`order ${placed.ref.id} at version ${placed.version}`);
```

The call answers with the last hop's result: here the order's commit, so `placed.ref` is the order. `timeout` is in milliseconds, over the plan's own (10 seconds unless `.within` says otherwise). `parseWaitPlan` reads a `Wow-Wait` value, and `formatWaitPlan` writes one. A wait that runs out rejects with 504 `wow.wait.timeout`; the command may still have committed. [Waits](../concepts/waits) explains how the service follows a chain.

## Follow the wait as a stream

Each method has a `…Stream` twin that answers one result per hop as the wait advances, for a page that shows the progress of a long chain:

<!-- typecheck-context
import { WaitPlan } from '@ahoo-wang/wow-client';
import { BasketCommandClient, eshopBasketWait } from './generated/basket/index.js';
import { eshopOrderingWait } from './generated/ordering/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
const stream = await baskets.checkoutBasketStream(
  'buyer-1',
  {},
  {
    wait: WaitPlan.of(
      eshopBasketWait.committed(),
      eshopBasketWait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      eshopOrderingWait.committed(),
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

A command refused at admission rejects the call with its problem, as without a stream. A rejection after admission is the stream's last result, with its `status` and `error`. When a saga sent nothing further, the chain ends before the plan does: that result has `chainEnded`, and `pending` names the hops that will not come. The eShop's checkout page follows its fifteen-hop chain to the payment this way (`examples/eshop/web/src/shop/checkout.ts`).

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

A `WowProblemError`'s `retry` says what a retry may do, as the error catalog declares it for its code: `none`, a retry fails alike; `same-request`, send it again with the same request id; `new-request`, send it again as it is. `retryAfterMs` says how long to wait first (`Retry-After`). Every command carries a request id (`Wow-Request-Id`): the one in the options, or one the client generates, which either error's `requestId` hands back. A retry with the same id is answered as the first commit's duplicate, never applied twice, so retry `wow.command.unknown` and `wow.wait.timeout` with it. Pass `requestId` from the start when the retry may come from elsewhere, a page reload say:

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

An aggregate's client also sends the lifecycle commands its Manifest lists: `deleteAggregate`, `restoreAggregate`, `transferOwner`, `transferSpace` and `applyTags`.

Every method takes the request options last. `space` picks one of the caller's spaces (`Wow-Space`); `targetTenant` and `targetScope` (`{ ownerId?, spaceId? }`) act in another tenant, owner or space (`Wow-Scope`), for a caller allowed to act across scopes; `headers` adds headers of your own; `signal` cancels the request. Which caller a request is, and what it may do, is the service's to decide from its credentials ([Identity](../concepts/identity)).

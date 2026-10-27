---
title: 发送命令
description: 通过生成的客户端发送命令，等待它跨 Saga 和上下文带来的结果，以流的形式跟随等待，并处理它最终的结果或 problem。
---

# 发送命令

生成的命令客户端为它的聚合的每条命令提供一个方法。一次调用发送命令，按请求的要求等待到相应的位置，然后以等待结束时的 `CommandResult` resolve，或者以服务回答的 problem reject。本页使用 eShop 生成的客户端（[生成客户端](./generate)）。

<!-- typecheck-generated: example/eshop/web/src/generated -->

## 只创建一次客户端 {#create-the-clients-once}

每个客户端都通过一个 Fetcher 发送：服务的基础 URL，以及为每个请求签名的拦截器（[认证](./authentication)）。每个服务创建一次 Fetcher，再基于它创建客户端：

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

每个路由都以它的上下文开头，例如 `/eshop-ordering/order/…`，所以放在一个按这个前缀路由的网关上的 Fetcher 能服务所有服务；`basePath` 在每个路由前加上网关自己的前缀。

## 发送命令 {#send-a-command}

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

`SubmitOrder` 会创建它的聚合，所以它的方法不接收 ID：服务生成一个，`result.ref.id` 给出它。在选项中传入 `{ id }` 可以自己选定。作用于已有聚合的命令先接收它的 ID：`orders.payOrder(id, body)`。`unitPrice` 在服务端是 `BigDecimal`，所以它的类型是 `string`，以精确数字写入命令体。

结果说明命令到达了哪里：

| 成员 | 包含 |
| --- | --- |
| `status` | `SUCCEEDED`；没有成功的调用会 reject，除非设置了 `resolveRejected` |
| `ref` | 聚合：它的类型、租户和 ID |
| `version` | 提交之后聚合的版本 |
| `hop` | 这个结果所回答的等待的那一跳 |
| `results` | 命令处理函数放进结果里的内容 |
| `duplicate` | 请求 ID 已经提交过、这次回答的是第一次提交时为 `true` |
| `requestId`、`commandId`、`correlationId` | 请求、命令及其开始的链的 ID |

## 等待命令带来的结果 {#wait-for-what-the-command-leads-to}

不指定等待时，调用在快照包含这次提交后回答（聚合不保存快照时则是在提交后），时长在服务的默认超时之内。`wait` 说明要等到哪里：

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import { wait } from '@ahoo-wang/wow-client';

const accepted = await orders.cancelOrder(
  'order-1',
  { reason: 'changed my mind' },
  { wait: [wait.accepted()] },
);
```

| 跳 | 何时回答 |
| --- | --- |
| `wait.accepted()` | 命令通过了准入；路由以 202 回答 |
| `wait.committed()` | 它的事件已在日志中 |
| `wait.snapshot()` | 快照包含这次提交，所以读取能看到它 |
| `wait.handled('Processor.function')` | 那个订阅处理了这次提交 |

计划可以跟随命令走得更远：经过 Saga 的 `handled` 那一跳，到达 Saga 发出的、位于它们各自上下文中的命令。每个上下文的 `contract.ts` 包含它的各跳，以它的订阅名提供类型。为购物车结账，并一直等到它提交的订单在 ordering 上下文中提交：

<!-- typecheck-context
import { BasketCommandClient } from './generated/basket/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
import { eshopBasketWait } from './generated/basket/index.js';
import { eshopOrderingWait } from './generated/ordering/index.js';

const placed = await baskets.checkoutBasket(
  'buyer-1',
  {},
  {
    wait: [
      eshopBasketWait.committed(),
      eshopBasketWait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      eshopOrderingWait.committed(),
    ],
    timeout: 10_000,
  },
);
console.log(`order ${placed.ref.id} at version ${placed.version}`);
```

调用以最后一跳的结果回答：这里是订单的提交，所以 `placed.ref` 是订单。`timeout` 以毫秒计。用完时间的等待以 504 `wow.wait.timeout` reject；命令仍可能已经提交。服务怎样跟随一条链，见[等待结果](../concepts/waits)。

## 以流的形式跟随等待 {#follow-the-wait-as-a-stream}

每个方法都有一个 `…Stream` 孪生方法，随着等待推进，每一跳回答一个结果，用于展示一条长链进度的页面：

<!-- typecheck-context
import { BasketCommandClient, eshopBasketWait } from './generated/basket/index.js';
import { eshopOrderingWait } from './generated/ordering/index.js';
declare const baskets: BasketCommandClient;
-->

```ts
const stream = await baskets.checkoutBasketStream(
  'buyer-1',
  {},
  {
    wait: [
      eshopBasketWait.committed(),
      eshopBasketWait.handled('BasketCheckoutSaga.onBasketCheckedOut'),
      eshopOrderingWait.committed(),
    ],
  },
);
const reader = stream.getReader();
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  console.log(value.hop.stage, value.status);
  if (value.status !== 'SUCCEEDED' || value.chainEnded) break;
}
```

在准入时被拒绝的命令，会像不用流时一样以它的 problem reject 这次调用。准入之后的拒绝是流的最后一个结果，带着它的 `status` 和 `error`。当某个 Saga 不再发出任何东西时，链会在计划结束之前结束：那个结果带有 `chainEnded`，`pending` 指明不会到来的那些跳。eShop 的结账页面就是这样把它十五跳的链一直跟随到支付的（`example/eshop/web/src/shop/checkout.ts`）。

## 命令失败时 {#when-a-command-fails}

拒绝会以一个 `WowProblemError` reject：服务写出的 RFC 9457 problem，它的 `status`、它的 Wow `code` 和 `violations`，对于到达了聚合的命令，还有 `result`。领域的拒绝与生成的契约中的 `<Context>Contract.<Aggregate>.Errors` 比较，框架的拒绝与 `FrameworkErrorCodes` 比较：

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';
import { EshopOrderingContract } from './generated/ordering/index.js';

try {
  await orders.payOrder('order-1', { amount: '39.80', paymentId: 'pay-1' });
} catch (error) {
  if (!(error instanceof WowProblemError)) throw error;
  switch (error.code) {
    case EshopOrderingContract.Order.Errors.WRONG_AMOUNT:
      console.warn(error.problem.detail);
      break;
    case FrameworkErrorCodes.COMMAND_INVALID:
      for (const violation of error.violations)
        console.warn(violation.path, violation.message);
      break;
    default:
      if (!error.retry.retryable) throw error;
  }
}
```

`error.retry` 说明重试能否成功、多久之后（`afterMs`，来自 `Retry-After`），以及是否保留请求 ID（`sameRequestId`）。每条命令都带着一个请求 ID（`Wow-Request-Id`）：选项中给出的，或者客户端生成的，`error.requestId` 会把它交回来。用同一个 ID 重试，会被当作第一次提交的重复请求来回答，绝不会执行两次，所以用它重试 `wow.command.unknown` 和 `wow.wait.timeout`。重试可能来自别处时（例如页面重新加载），从一开始就传入 `requestId`：

<!-- typecheck-context
import { OrderCommandClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
-->

```ts
import { newRequestId } from '@ahoo-wang/wow-client';

const requestId = newRequestId();
await orders.shipOrder('order-1', {}, { requestId, expectedVersion: 4 });
```

`expectedVersion` 发送 `If-Match`：作用于处于其他版本的聚合的命令，会以 `wow.aggregate.version-mismatch` 被拒绝。`resolveRejected: true` 让没有成功的结果 resolve 而不是 reject，供自己读取 `status` 和 `error` 的代码使用。每个框架错误码及其状态见[错误码](../reference/error-codes)。

## 生命周期命令、作用域和空间 {#lifecycle-commands-scope-and-space}

聚合的客户端还发送它的 Manifest 列出的生命周期命令：`deleteAggregate`、`restoreAggregate`、`transferOwner`、`transferSpace` 和 `applyTags`。

每个方法的最后一个参数都是请求选项。`space` 选择调用方的一个空间（`Wow-Space`）；`targetTenant` 和 `targetScope`（`{ ownerId?, spaceId? }`）在另一个租户、所有者或空间中行事（`Wow-Scope`），供被允许跨作用域行事的调用方使用；`headers` 加上你自己的请求头；`signal` 取消请求。请求是哪个调用方、能做什么，由服务根据它的凭据决定（[身份](../concepts/identity)）。

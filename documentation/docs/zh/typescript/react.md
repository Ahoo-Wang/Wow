---
title: React Hooks
description: 用 wow-react 在 React 组件中通过生成的查询客户端运行 Wow 查询，把查询、结果、加载状态和错误作为 React 状态保存。
---

# React Hooks

`@ahoo-wang/wow-react` 在组件中保存一个 Wow 查询：查询本身、它的结果、是否正在加载，以及它失败时的错误。hook 在挂载时运行查询，查询改变时再次运行，取消被更新的查询替换掉的请求，并在卸载时取消请求。这些查询就是[读取与查询](./queries)中的那些，通过生成的查询客户端运行。

<!-- typecheck-generated: examples/eshop/web/src/generated -->

## 安装 {#install}

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

需要 React 19 或更高版本：这个包用 React Compiler 构建，会导入 `react/compiler-runtime`，而 React 18 没有它。

## 通过客户端运行查询 {#run-a-query-through-a-client}

`use*Query` hook 接收查询和一个运行它的 `execute` 函数；生成客户端的方法恰好接收查询和请求选项，所以由客户端提供路由和响应处理：

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { desc, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function PaidOrders({
  orders,
  page,
}: {
  orders: OrderSnapshotQueryClient;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<
    SnapshotRecord<OrderState>,
    OrderStateField | 'lastEventTime'
  >({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      sort: [desc('lastEventTime')],
      pagination: { index: page, size: 20 },
    }),
    execute: (query, options) => orders.paged(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  if (loading || !result) return <p>Loading…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.aggregateId}>
          {order.aggregateId}: {order.state.totalAmount}
        </li>
      ))}
    </ul>
  );
}
```

- 把记录类型和查询指明的字段作为类型参数传入：第一个参数写出来之后，TypeScript 不会再从 `execute` 推断字段。
- 像上面那样把选项交给客户端：它们带着 hook 用来取消请求的 `signal`。
- 通过箭头函数传递方法：单独传递的方法会丢失它的 `this`。

| Hook | 运行 | `result` |
| --- | --- | --- |
| `useSingleQuery` | `singleQuery(…)` | 一条记录 |
| `useListQuery` | `listQuery(…)` | 记录列表 |
| `usePagedQuery` | `pagedQuery(…)` | `{ total, exact, list }` |
| `useCountQuery` | `{ filter, limit? }` | `{ count, exact }` |
| `useStreamQuery` | `listQuery(…)`，以服务端推送事件 | 陆续到达的 `items`，然后是 `done` |

聚合查询和游标查询没有自己的 hook。游标查询通过 `useSingleQuery` 运行，以这一页作为它的结果；聚合则在一个清理时会中止的 effect 中用 `client.aggregate(query, { signal })` 运行。

## 流式读取长列表 {#stream-a-long-list}

`useStreamQuery` 以服务端推送事件读取列表，并保存陆续到达的行，无论到达多少行，大约每帧至多渲染一次。服务切断的流会从停下的地方续读，就像 `stream` 那样：

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function ShippedOrders({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, done, loading, error, abort } = useStreamQuery<
    SnapshotRecord<OrderState>,
    OrderStateField
  >({
    query: listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 1000 }),
    execute: (query, options) => orders.stream(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <p>
        {items.length} orders{done ? '' : '…'}
      </p>
      {loading && <button onClick={abort}>Stop</button>}
    </>
  );
}
```

`abort()` 停止流，保留已读到的行；`reset()` 停止流并清空它们。

## 状态与错误 {#state-and-errors}

每个 hook 都返回 `status`（`'idle' | 'loading' | 'success' | 'error'`）、`loading`、`result`（或 `items` 和 `done`）、`error`，以及 `execute`、`abort`、`reset`、`getQuery` 和 `setQuery`。

- hook 在挂载时以及查询改变时（按内容比较）运行；`autoExecute: false` 让它只在 `execute()` 时运行。设为 `undefined` 的查询会中止在途的请求，让 hook 处于空闲状态。
- 失败的请求或 `abort()` 会保留上一次的 `result`，所以失败的刷新不会让屏幕变空。
- 在服务端渲染中，hook 渲染为 `loading` 且不发送任何请求；页面水合之后才运行查询。
- 对服务拒绝的请求，`error` 是一个 `WowProblemError`，带着它的 `code`、`status` 和 `violations`；把错误类型作为第三个类型参数传入，就能这样为它提供类型。

## 不用生成的客户端 {#without-a-generated-client}

`useFetcher*Query` hooks 通过 Fetcher 而不是客户端把查询发往查询路由，用于没有客户端覆盖的路由，例如数据集的路由：`useFetcherPagedQuery({ fetcher, url: '/wow/dataset/eshop-ordering/sales/query', initialQuery })`。它们的选项见 [wow-react README](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-react)。

[Storybook](/storybook/?path=/docs/react-hooks-wow-queries--docs) 对着内存数据运行每个 hook。

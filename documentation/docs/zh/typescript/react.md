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
import type { Snapshot } from '@ahoo-wang/wow-client';
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
    Snapshot<OrderState>,
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
| `useAggregateQuery` | `{ groupBy?, metrics, … }` | 行，每组一行 |
| `useCursorQuery` | `{ filter, sort?, size? }`，逐页读取 | 已读各页的 `items`、`hasMore` 和 `fetchNext()` |
| `useStreamQuery` | `listQuery(…)`，以服务端推送事件 | 陆续到达的 `items`、`position`，然后是 `done` |

## 逐页读取 {#read-page-by-page}

`useCursorQuery` 读取只进的游标查询，保存已读各页的行；`fetchNext()` 读取下一页并追加到后面。游标归 hook 所有：它的查询里没有游标，每次运行都从第一页开始；`fetchNext()` 失败时保留已有的行，再调用一次就重试那一页：

```tsx
import type { Snapshot } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function PaidOrderFeed({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, hasMore, loading, error, fetchNext } = useCursorQuery<
    Snapshot<OrderState>,
    OrderStateField
  >({
    query: { filter: filter.eq('state.status', 'PAID'), size: 50 },
    execute: (query, options) => orders.cursor(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.aggregateId}</li>
        ))}
      </ul>
      {hasMore && (
        <button disabled={loading} onClick={() => void fetchNext()}>
          More
        </button>
      )}
    </>
  );
}
```


## 流式读取长列表 {#stream-a-long-list}

`useStreamQuery` 以服务端推送事件读取列表，并保存陆续到达的行，无论到达多少行，大约每帧至多渲染一次。传入客户端的 `streamRows`，它为每条记录带上位置：服务切断的流会从停下的地方续读，hook 把最后读到的记录的位置保存为 `position`：

```tsx
import type { Snapshot } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function ShippedOrders({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, done, loading, error, abort } = useStreamQuery<
    Snapshot<OrderState>,
    OrderStateField
  >({
    query: listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 1000 }),
    execute: (query, options) => orders.streamRows(query, options),
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

组件重新挂载后要从流停下的地方接着读，就把 `position` 存在比组件活得久的地方，再以 `requestOptions: { resume: { lastEventId: saved } }` 传回：第一个请求就会请求它之后的记录。

## 状态与错误 {#state-and-errors}

每个 hook 都返回 `status`（`'idle' | 'loading' | 'success' | 'error'`）、`loading`、`result`（或 `items`）、`error`、`problem`，以及 `execute`、`abort`、`reset`、`getQuery` 和 `setQuery`。

- hook 在挂载时以及查询或 `requestOptions`（scope、space、更多请求头）改变时（按内容比较）运行；`autoExecute: false` 让它只在 `execute()` 时运行。设为 `undefined` 的查询会中止在途的请求，让 hook 处于空闲状态。每次渲染都变的请求头值会让查询每次渲染都重新运行，所以要让这样的值保持稳定。
- 失败的请求或 `abort()` 会保留上一次的 `result`，所以失败的刷新不会让屏幕变空。
- 在服务端渲染中，hook 渲染为 `loading` 且不发送任何请求；页面水合之后才运行查询。
- `error` 是一个 `Error`。服务以 problem 应答时，`problem` 就是这个错误，一个带着 `code`、`status`、`violations` 和重试提示的 `WowProblemError`；没有服务应答的失败（例如网络错误）时它是 `undefined`。

## 数据集 {#datasets}

数据集在运行时注册，所以没有为它生成的客户端；`DatasetClient` 给出它的查询客户端。在组件之外只创建一次客户端，然后像生成的客户端那样把它的方法交出去：

```tsx
import { DatasetClient } from '@ahoo-wang/wow-client';
import { pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';

const sales = new DatasetClient().dataset('eshop-ordering', 'sales');

export function SalesPage({ page }: { page: number }) {
  const { result } = usePagedQuery<Record<string, unknown>>({
    query: pagedQuery({ pagination: { index: page, size: 20 } }),
    execute: (query, options) => sales.paged(query, options),
  });
  return <p>{result?.total ?? '…'} rows</p>;
}
```

[Storybook](/storybook/?path=/docs/react-hooks-wow-queries--docs) 对着内存数据运行每个 hook。

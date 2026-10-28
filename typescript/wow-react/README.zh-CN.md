# `@ahoo-wang/wow-react`

面向 [Wow](https://github.com/Ahoo-Wang/Wow) 查询的 React hook：单条、列表、分页、计数、
聚合、游标和列表流。它们把查询、结果、加载中与错误保存为 React 状态，建立在
`@ahoo-wang/wow-client` 的查询类型之上。

## 环境要求

- **React 19.0 及以上。** 本包用 React Compiler 编译，产物导入只有 React 19 才有的
  `react/compiler-runtime`；不支持 React 18。
- 工具链与服务端渲染需要 Node.js 22.12 及以上；浏览器以 React 19 支持的为准。
- `@ahoo-wang/wow-client` 与本包的次版本号一致。
- TypeScript 6 或更高版本（CI 测试 6.0 到最新的 7.x），使用 `"moduleResolution": "bundler"` 或
  `"module": "nodenext"`。

## 安装

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

`@ahoo-wang/fetcher-eventstream` 是 `@ahoo-wang/wow-client` 的 peer，不是本包的。

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/typescript/compatibility#版本范围)。

这些 hook 自带请求状态机：不需要 `@ahoo-wang/fetcher-react`，也不需要
`@ahoo-wang/fetcher-wow`。

本包只发布 ES 模块。Node.js 22.12+ 上的 CommonJS 代码仍可以
`require('@ahoo-wang/wow-react')`，因为 Node.js 能通过 `require` 加载 ES 模块。

## 配合查询客户端

`use*Query` 这组 hook 接收一个 `execute` 函数：参数是查询与本次运行的请求选项（含 `signal`）。
`@ahoo-wang/wow-client` 的查询客户端——生成的 `…SnapshotQueryClient` 或 `QueryModelClient`——
最后两个参数恰好就是它们，于是路由、请求头和响应处理都由客户端负责，不用手写：

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
// 查询可用的状态字段；生成的客户端会导出这个类型。
type OrderFields = 'state.status';

export function PaidOrders({
  client,
  page,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: page, size: 20 },
    }),
    execute: (query, options) => client.paged(query, options),
  });

  if (error) return <p role="alert">订单加载失败</p>;
  if (loading || !result) return <p>加载中…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.aggregateId}>{order.state.status}</li>
      ))}
    </ul>
  );
}
```

像上面这样把客户端的字段类型作为第二个类型参数传入：生成的客户端字段比 `string` 窄，
而一旦写出第一个类型参数，TypeScript 就不会再从 `execute` 推断它。
记得把选项传下去：这样新的查询、`abort()`、`reset()` 和组件卸载都会取消请求。方法要像上面那样
包一层箭头函数再交出去：单独交出的方法会丢掉 `this`。
快照查询过滤的是快照记录，聚合状态在它的 `state` 字段下，所以状态字段写作
`state.status`，而不是 `status`。`requestOptions` 为每次运行加上作用域、空间或更多请求头。
客户端在组件之外创建一次（或用 memo 保存），不要在 `execute` 里创建：一个客户端服务所有请求。

## 逐页读取

`useCursorQuery` 读取只进的游标查询，保存已读各页的行；`fetchNext()` 读取下一页并追加到后面。
游标归 hook 所有，所以它的查询里没有游标（在那里写 `cursor` 无法通过编译）：每次运行都从第一页
开始，`execute` 收到的查询带着它要读的那一页的游标。

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
type OrderFields = 'state.status';

export function PaidOrderPages({
  client,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
}) {
  const { items, hasMore, loading, error, fetchNext } = useCursorQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    query: { filter: filter.eq('state.status', 'PAID'), size: 50 },
    execute: (query, options) => client.cursor(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.state.status}</li>
        ))}
      </ul>
      {hasMore && (
        <button disabled={loading} onClick={() => void fetchNext()}>
          更多
        </button>
      )}
    </>
  );
}
```

页面加载中或读完最后一页之后，`fetchNext()` 什么也不做。`fetchNext()` 失败时保留已有的行和游标，
再调用一次就重试那一页；新的查询和 `execute()` 从第一页重新开始，`reset()` 清空已有的行。

`useAggregateQuery` 运行一个聚合（通常是 `client.aggregate`），把它的行保存为 `result`，每组一行。

## 流式读取列表

`useStreamQuery` 以服务端推送事件（SSE）读取列表，记录一到就保存下来。把客户端的
`streamRows` 作为 `execute` 传入：它发送 `Accept: text/event-stream`，服务端中途切断的流
会从断点续传，并为每条记录带上位置，hook 把它保存为 `position`。

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
type OrderFields = 'state.status';

export function PaidOrderFeed({
  client,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
}) {
  const { items, done, loading, error, abort } = useStreamQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    initialQuery: listQuery({
      filter: filter.eq('state.status', 'PAID'),
      limit: 100,
    }),
    execute: (query, options) => client.streamRows(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.state.status}</li>
        ))}
      </ul>
      {loading && <button onClick={abort}>停止</button>}
      {done && <p>共 {items.length} 个订单</p>}
    </>
  );
}
```

| 字段       | 含义                                                                                     |
| ---------- | ---------------------------------------------------------------------------------------- |
| `items`    | 当前查询到目前为止收到的记录，按到达顺序。新查询从空列表开始；`reset()` 清空它。         |
| `position` | 最后一条带位置的记录的位置；新查询和 `reset()` 清除它。                                  |
| `done`     | 流正常结束，`items` 已包含全部记录。等同于 `status === 'success'`。                      |
| `loading`  | 从发出请求直到流结束、出错或被中止。                                                     |
| `problem`  | 请求失败、或服务端在流中途发来错误事件时的 `WowProblemError`。                           |
| `abort()`  | 停止读取流并保留已收到的行。`reset()` 停止并清空 `items`。`execute()` 重新执行当前查询。 |

流由 hook 自己持有：它负责读取，无论到达多少记录，最多约每帧（16 毫秒）渲染一次，并在新查询开始、
调用 `abort()` 或 `reset()`、组件卸载时取消流。组件不接触 reader，所以在 StrictMode 下
也是安全的。

组件重新挂载后（切换页面、刷新）要从流停下的地方接着读，就把 `position` 存在比组件活得久的
地方，再以 `requestOptions: { resume: { lastEventId: saved } }` 传回；第一个请求就会请求它之后的记录。

## 状态与错误

每个 hook 在挂载时执行查询，之后 `query` 或 `requestOptions`（都按内容比较）或 `setQuery()`
改变时再次执行；设置 `autoExecute: false` 则只在调用 `execute()` 时执行。`requestOptions`
也算在内，是因为作用域、空间和请求头都可能改变答案：每次渲染都变的请求头值会让查询每次渲染都
重新执行，所以要让这样的值保持稳定。新的查询会中止正在进行的请求，所以迟到的响应不会覆盖较新的
结果。受控的 `query` 重新变为 `undefined` 时（如 `id ? singleQuery(…) : undefined`），中止进行中的
请求并回到 `idle`，保留上一次结果；再给出查询之前不会执行。`onSuccess`、`onError` 分别以结果和
错误为参数调用。

请求失败和 `abort()` 都保留上一次的 `result`，刷新失败不会让界面变空白；`reset()`
会中止进行中的请求，并清空 `result` 与 `error`。挂载即执行的 hook 首帧（服务端也一样）
就是 `loading`，服务端渲染时不发请求。没有 `initialResult` 选项：服务端渲染出的 HTML
是加载状态，页面水合之后才执行查询。

每个 hook 都返回 `status`、`loading`、`result`（流式与游标 hook 为 `items`）、`error`、
`problem`、`execute`、`abort`、`reset`、`getQuery` 和 `setQuery`。各 hook 的选项与返回
类型都扩展本包声明并导出的 `QueryHookOptions` 与 `QueryHookReturn`，同时导出的还有
`QueryStatus`、`QueryExecutor` 与 `StreamExecutor`。`status` 是普通的字符串联合类型
`'idle' | 'loading' | 'success' | 'error'`，组件 props 或测试里可以直接写字面量。

`error` 是一个 `Error`：本次运行失败的原因；抛出的值不是 `Error` 时，会被包进一个 `Error`，
原值作为它的 `cause`。Wow 服务以 problem 应答时，`problem` 就是这个错误，一个带着服务的
`code`、`status` 和重试提示的 `WowProblemError`；没有服务应答的失败（例如网络错误）时它是
`undefined`：

```tsx
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { FrameworkErrorCodes } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCountQuery } from '@ahoo-wang/wow-react';

export function OrderCount({ client }: { client: SnapshotQueryClient }) {
  const { result, problem, error } = useCountQuery({
    query: { filter: filter.matchAll() },
    execute: (query, options) => client.count(query, options),
  });
  if (problem?.code === FrameworkErrorCodes.QUERY_BUSY)
    return <p>繁忙，{problem.retry.afterMs} 毫秒后重试</p>;
  if (error) return <p role="alert">{error.message}</p>;
  return <p>共 {result?.count ?? '…'} 个订单</p>;
}
```

## Hook 一览

| Hook                | 查询                        | 结果                                         |
| ------------------- | --------------------------- | -------------------------------------------- |
| `useSingleQuery`    | `singleQuery(…)`            | `result`：一条                               |
| `useListQuery`      | `listQuery(…)`              | `result`：若干行                             |
| `usePagedQuery`     | `pagedQuery(…)`             | `result`：`{ total, exact, list }`           |
| `useCountQuery`     | `{ filter, limit? }`        | `result`：`{ count, exact }`                 |
| `useAggregateQuery` | `{ groupBy?, metrics, … }`  | `result`：若干行，每组一行                   |
| `useCursorQuery`    | `{ filter, sort?, size? }`  | 已读各页的 `items`、`hasMore`、`fetchNext()` |
| `useStreamQuery`    | `listQuery(…)`，以 SSE 事件 | `items` 陆续到达、`position`，结束后 `done`  |

查询用 `@ahoo-wang/wow-client/dsl` 的 DSL 构造。

在 Wow 9 中从 `@ahoo-wang/fetcher-react`（其中的 Wow hook）迁移而来；版本号跟随 Wow。

## 文档

- [TypeScript 指南](https://wow.ahoo.me/zh/typescript/)
- [交互式查询 hook Story](https://wow.ahoo.me/storybook/?path=/docs/react-hooks-wow-queries--docs)

[English](./README.md) · [许可证](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

# `@ahoo-wang/wow-react`

面向 [Wow](https://github.com/Ahoo-Wang/Wow) 查询的 React hook：单条、列表、分页、计数和
列表流。它们把查询、结果、加载中与错误保存为 React 状态，建立在
`@ahoo-wang/wow-client` 的查询类型之上。

## 环境要求

- **React 19.3 及以上。** 本包用 React Compiler 编译，产物导入只有 React 19 才有的
  `react/compiler-runtime`；不支持 React 18。
- 工具链与服务端渲染需要 Node.js 22.12 及以上；浏览器以 React 19 支持的为准。
- `@ahoo-wang/wow-client` 与本包的次版本号一致。
- TypeScript 使用 `"moduleResolution": "bundler"` 或 `"module": "nodenext"`。

## 安装

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/guide/typescript/compatibility#版本范围)。

这些 hook 自带请求状态机：不需要 `@ahoo-wang/fetcher-react`，也不需要
`@ahoo-wang/fetcher-wow`。

本包只发布 ES 模块。Node.js 22.12+ 上的 CommonJS 代码仍可以
`require('@ahoo-wang/wow-react')`，因为 Node.js 能通过 `require` 加载 ES 模块。

## 配合查询客户端

`use*Query` 这组 hook 接收一个 `execute` 函数。查询客户端的方法——
`@ahoo-wang/wow-client` 的 `SnapshotQueryClient`，或生成的 `…QueryClientFactory`
创建的客户端——签名恰好是 `execute` 需要的 `(query, attributes, abort)`，于是 URL、
请求头和响应处理都由客户端负责，不用手写。这些客户端的方法已绑定到实例上，所以
`execute: client.pagedState` 可以直接写；自己对象上的方法要 `.bind(对象)` 或包一层
箭头函数：

```tsx
import {
  filter,
  pagedQuery,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import { usePagedQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  id: string;
  status: string;
}
// 查询可用的字段；生成的客户端会导出这个类型。
type OrderFields = 'aggregateId' | 'state.status';

export function PaidOrders({
  client,
  page,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<OrderState, OrderFields>({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: page, size: 20 },
    }),
    execute: client.pagedState,
  });

  if (error) return <p role="alert">订单加载失败</p>;
  if (loading || !result) return <p>加载中…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.id}>{order.status}</li>
      ))}
    </ul>
  );
}
```

像上面这样把客户端的字段类型作为第二个类型参数传入：生成的客户端字段比 `string` 窄，
而一旦写出第一个类型参数，TypeScript 就不会再从 `execute` 推断它。
记得把 `abortController` 传下去：这样新的查询、`abort()`、`reset()` 和组件卸载都会取消请求。
快照查询过滤的是快照文档，聚合状态在它的 `state` 字段下，所以状态字段写作
`state.status`，而不是 `status`。

聚合查询暂时没有 Hook，通用的 `useQuery` 与聚合 Hook 计划在 9.3 提供。在此之前，在
effect 里调用 `client.aggregate`，并在清理函数里中止它，写法见
[参考页](https://wow.ahoo.me/zh/reference/typescript/wow-react/#聚合查询)。

## 配合端点 URL

`useFetcher*Query` 这组 hook 则通过 Fetcher 把查询 POST 到一个 URL：

```tsx
import { Fetcher } from '@ahoo-wang/fetcher';
import { filter, pagedQuery } from '@ahoo-wang/wow-client';
import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com/' });

export function PaidOrders() {
  const { result, loading, error } = useFetcherPagedQuery<OrderState>({
    fetcher,
    url: 'order/snapshot/paged/state',
    initialQuery: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: 1, size: 20 },
    }),
  });
  // …渲染同上
}
```

`fetcher` 可以是 Fetcher 实例，也可以是已注册 Fetcher 的名字；省略时用默认 Fetcher。
一个聚合的快照路由是 `{aggregate}/snapshot/{single|list|paged}[/state]` 与
`{aggregate}/snapshot/count`；聚合有上下文别名、owner 或租户路径时，路由前面还要加上它们。

## 流式读取列表

`useListStreamQuery` 与 `useFetcherListStreamQuery` 以服务端推送事件（SSE）读取列表，
行一到就保存下来。`listQuery()` 只在给出 `limit` 时才发送它：Wow 9.1.5 及以后此时用
默认列表大小，Wow 8.12～9.1.3 则以 HTTP 400 拒绝，对这些服务端请传 `limit`：

```tsx
import { filter, listQuery } from '@ahoo-wang/wow-client';
import { useFetcherListStreamQuery } from '@ahoo-wang/wow-react';

export function PaidOrderFeed() {
  const { items, done, loading, error, abort } =
    useFetcherListStreamQuery<OrderState>({
      fetcher,
      url: 'order/snapshot/list/state',
      initialQuery: listQuery({
        filter: filter.eq('state.status', 'PAID'),
        limit: 100,
      }),
    });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.id}>{order.id}</li>
        ))}
      </ul>
      {loading && <button onClick={abort}>停止</button>}
      {done && <p>共 {items.length} 个订单</p>}
    </>
  );
}
```

| 字段      | 含义                                                                                            |
| --------- | ----------------------------------------------------------------------------------------------- |
| `items`   | 当前查询到目前为止收到的行，按到达顺序。新查询从空列表开始；`reset()` 清空它。                  |
| `done`    | 流正常结束，`items` 已包含全部行。等同于 `status === 'success'`。                               |
| `loading` | 从发出请求直到流结束、出错或被中止。                                                            |
| `error`   | 请求失败时是 `FetcherError`；服务端在流中途发来错误事件时是带服务端 `errorCode` 的 `WowError`。 |
| `abort()` | 停止读取流并保留已收到的行。`reset()` 停止并清空 `items`。`execute()` 重新执行当前查询。        |

流由 hook 自己持有：它负责读取，无论到达多少行，最多约每帧（16 毫秒）渲染一次，并在新查询开始、
调用 `abort()` 或 `reset()`、组件卸载时取消流。组件不接触 reader，所以在 StrictMode 下
也是安全的。`useFetcherListStreamQuery` 会发送 `Accept: text/event-stream`——Wow
服务端要看到这个请求头才会以流的形式响应；用 `useListStreamQuery` 时，把客户端的
`listStream` 或 `listStateStream` 作为 `execute` 传入。

## 状态与错误

每个 hook 在挂载时执行查询，之后 `query` 选项（按内容比较）或 `setQuery()` 改变查询时
再次执行，`useFetcher*` hook 在 `url` 或 `fetcher` 变化时也会再次执行；设置
`autoExecute: false` 则只在调用 `execute()` 时执行。新的查询会中止正在进行的请求，所以
迟到的响应不会覆盖较新的结果。受控的 `query` 重新变为 `undefined` 时（如
`id ? singleQuery(…) : undefined`），中止进行中的请求并回到 `idle`，保留上一次结果；
再给出查询之前不会执行。`onSuccess`、`onError` 分别以结果和错误为参数调用。

请求失败和 `abort()` 都保留上一次的 `result`，刷新失败不会让界面变空白；`reset()`
会中止进行中的请求，并清空 `result` 与 `error`。挂载即执行的 hook 首帧（服务端也一样）
就是 `loading`。

每个 hook 都返回 `status`、`loading`、`result`（流式 hook 为 `items` 与 `done`）、
`error`、`execute`、`abort`、`reset`、`getQuery` 和 `setQuery`。各 hook 的选项与返回
类型都扩展本包声明并导出的 `QueryHookOptions` 与 `QueryHookReturn`，同时导出的还有
`QueryStatus` 与 `QueryExecutor`。`status` 是普通的字符串联合类型
`'idle' | 'loading' | 'success' | 'error'`，组件 props 或测试里可以直接写字面量。

`error` 的类型默认是 `Error`，需要更窄时传入类型参数 `E`：请求失败时是
`FetcherError`，流中的错误事件是 `WowError`，自定义的 `execute` 则是它抛出的任何错误。
`@ahoo-wang/wow-client` 的 `toWowError` 能从失败的请求中读出服务端的 `ErrorInfo`：

```ts
import { ErrorCodes, toWowError } from '@ahoo-wang/wow-client';

const wowError = await toWowError(error);
if (wowError?.errorCode === ErrorCodes.NOT_FOUND) {
  // …
}
```

## Hook 一览

| Hook                                              | 查询                        | 结果                            |
| ------------------------------------------------- | --------------------------- | ------------------------------- |
| `useSingleQuery`、`useFetcherSingleQuery`         | `singleQuery(…)`            | `result`：一条                  |
| `useListQuery`、`useFetcherListQuery`             | `listQuery(…)`              | `result`：若干行                |
| `usePagedQuery`、`useFetcherPagedQuery`           | `pagedQuery(…)`             | `result`：`{ total, list }`     |
| `useCountQuery`、`useFetcherCountQuery`           | 一个 `filter.*` 表达式      | `result`：记录数                |
| `useListStreamQuery`、`useFetcherListStreamQuery` | `listQuery(…)`，以 SSE 事件 | `items` 陆续到达，结束后 `done` |

查询类型默认使用 `@ahoo-wang/wow-client` 的 `filter` API。每个 hook 也接受
`@ahoo-wang/wow-client/legacy` 中已弃用的 Condition 查询（Wow 8.10 服务端需要）；
这个重载将在 v10 移除。

在 Wow 9 中从 `@ahoo-wang/fetcher-react`（其中的 Wow hook）迁移而来；版本号跟随 Wow。

## 文档

- [TypeScript 指南](https://wow.ahoo.me/zh/guide/typescript/)
- [wow-react 参考](https://wow.ahoo.me/zh/reference/typescript/wow-react/)
- [交互式查询 hook Story](https://wow.ahoo.me/storybook/?path=/docs/react-hooks-wow-queries--docs)

[English](./README.md) · [许可证](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

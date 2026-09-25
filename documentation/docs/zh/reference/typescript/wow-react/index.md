---
title: 'wow-react 查询 Hook'
description: 'Wow 查询 Hook — @ahoo-wang/wow-react'
---

# wow-react 查询 Hook

Wow Hook 为同一个查询执行器限定请求/结果类型，不替你创建客户端或构建条件。服务版本要求 `execute` 函数，Fetcher 版本要求 `url`，可选命名或实例 Fetcher。

| 服务 Hook          | Fetcher Hook              | 请求 → 结果                                           |
| ------------------ | ------------------------- | ----------------------------------------------------- |
| useSingleQuery     | useFetcherSingleQuery     | SingleQueryRequest → R                                |
| useListQuery       | useFetcherListQuery       | ListQueryRequest → R[]                                |
| usePagedQuery      | useFetcherPagedQuery      | PagedQueryRequest → PagedList&lt;R&gt;，含 total/list |
| useCountQuery      | useFetcherCountQuery      | FilterExpression 或旧版 Condition → number            |
| useListStreamQuery | useFetcherListStreamQuery | ListQueryRequest → 随到随增的 `items: R[]`            |

查询类型默认为 `FilterSingleQuery`、`FilterListQuery`、`FilterPagedQuery` 或 `FilterExpression`。每个 Hook 另有一个重载接受从 `@ahoo-wang/wow-client/legacy` 导入的已弃用 `Condition` 查询，供 Wow 8.10 服务端使用，该重载在 v10 移除。下文签名中的 `SingleQueryRequest`、`ListQueryRequest`、`PagedQueryRequest` 是两类查询的联合类型，同样由 `/legacy` 导出。`FIELDS` 仅在编译期约束字段名，泛型不校验服务端 JSON。每个 `Use…Options` 都扩展 [`QueryHookOptions`](#api-QueryHookOptions)，每个 `Use…Return` 都扩展 [`QueryHookReturn`](#api-QueryHookReturn)，两者都由本包声明；Fetcher 变体的选项用 `url` 和 `fetcher` 代替 `execute`。Fetcher 变体把查询 POST 到 `url` 并按 JSON 提取；流版本发送 `Accept: text/event-stream`，并用 wow-client 的 `QueryEventStreamResultExtractor` 读取响应。

新的查询会中止进行中的查询，迟到的响应不会覆盖更新的结果；组件卸载时同样会中止。把 controller 传给服务客户端，中止时才能真正停止 I/O：wow-client 每个查询方法的最后一个参数 `abort` 接受 `AbortController` 或其 `signal`。请求失败时 `error` 是 fetcher 的错误（`ExchangeError`），用 `@ahoo-wang/wow-client` 的 `await toWowError(error)` 可从中读出 `errorCode`、`errorMsg` 和 `status`。

### 选项与状态

| 选项 | 含义 |
| --- | --- |
| `query` | 受控查询。内容变化（深比较）时 Hook 重新执行；内容相同的新对象不会触发执行。 |
| `initialQuery` | 非受控的初始查询，之后用 `setQuery()` 替换。`query` 与 `initialQuery` 都没有时，在 `setQuery()` 之前不会执行。 |
| `autoExecute` | 默认 `true`：挂载时和查询变化时执行。设为 `false` 时只由 `execute()` 执行。 |
| `attributes` | 每次执行都传给 `execute`（Fetcher 变体则传给 Fetcher 的拦截器）。它变化不会触发重新执行。 |
| `execute` | 一个 [`QueryExecutor`](#api-QueryExecutor)：`(query, attributes, abortController)`，解析为结果。Fetcher 变体改用 `url` 和 `fetcher`。 |
| `url`、`fetcher` | 仅 Fetcher 变体：端点（相对 Fetcher 的 `baseURL` 解析），以及 Fetcher 或已注册 Fetcher 的名字，省略时用默认 Fetcher。 |
| `onSuccess`、`onError` | 每次执行成功时收到结果，失败时收到错误。 |

| 返回 | 含义 |
| --- | --- |
| `status` | 一个 [`QueryStatus`](#api-QueryStatus)：`'idle'`、`'loading'`、`'success'` 或 `'error'`。它是普通的字符串联合类型，props、测试和 story 里可以直接写字面量。 |
| `loading` | 等同于 `status === 'loading'`。 |
| `result` | 最近一次成功的结果，没有时为 `undefined`。列表流 Hook 改为返回 `items` 与 `done`。 |
| `error` | 最近一次执行失败的原因。`E` 默认为 `Error`：请求失败时是 `FetcherError`，流中的错误事件是 `WowError`，自定义 `execute` 则是它抛出的任何错误。需要更窄的类型时显式传入 `E`，例如 `useSingleQuery<Order, OrderFields, FetcherError>`，或用 `instanceof` 判断。 |
| `execute()` | 重新执行当前查询，并中止进行中的请求。 |
| `abort()` | 中止进行中的请求并回到 `idle`。请求型 Hook 同时清空 `result`；列表流 Hook 保留已收到的行。 |
| `reset()` | 回到 `idle`，清空 `result`（或 `items`）与 `error`。列表流 Hook 还会停止进行中的流。 |
| `getQuery()`、`setQuery(query)` | 读取与替换当前查询；开启 `autoExecute` 时 `setQuery` 会触发执行。 |

### 列表流

`useListStreamQuery` 与 `useFetcherListStreamQuery` 自己持有流：读取流，把每个事件的 `data` 收进 `items`，并在新查询开始、调用 `abort()` 或 `reset()`、组件卸载时取消流。组件只渲染 `items`，从不持有 reader，因此在 StrictMode 下也安全；渲染按网络分块批量进行，而不是每行一次。它们不返回 `result`，而是返回：

| 字段 | 含义 |
| --- | --- |
| `items` | 当前查询至今收到的行，按到达顺序排列。新查询从空列表开始，`reset()` 清空它，`abort()` 或出错时保留此前已收到的行。 |
| `done` | 流正常结束，`items` 已包含全部行；等同于 `status === 'success'`。 |
| `loading` | 从发出请求到流结束、失败或被中止。 |
| `error` | 请求失败时为 `FetcherError`；服务端在流中发出错误事件时为带 `errorCode` 的 `WowError`。`E` 默认为 `Error`，用 `instanceof` 判断是否为 `WowError`。 |
| `status`、`execute`、`abort`、`reset`、`getQuery`、`setQuery` | 与其他 Hook 相同；`execute()` 重新执行当前查询并中止进行中的流，`reset()` 停止流并清空 `items`。 |

`onSuccess` 在流结束后收到全部行。`useListStreamQuery` 的 `execute` 选项是 [`ListStreamExecutor`](#api-ListStreamExecutor)：`(query, attributes, abortController)`，返回解析为 `ReadableStream<JsonServerSentEvent<R>>` 的 Promise，例如查询客户端的 `listStateStream`。`useFetcherListStreamQuery` 改为接收 `url` 和可选的 `fetcher`，没有 `execute` 或 `resultExtractor` 选项。

### 配合查询客户端

查询客户端的方法（wow-client 的 `SnapshotQueryClient`，或生成的 `…QueryClientFactory` 通过 `createSnapshotQueryClient(...)` 创建的客户端）本身就是 `execute` 所需的 `(query, attributes, abort)` 形状。直接转交即可：`execute: (query, attributes, abortController) => client.pagedState(query, attributes, abortController)`，这样不必手写 URL，中止时也会取消请求。正因如此，没有为每个客户端单独提供 Hook：每个客户端方法配一个 Hook 只会成倍扩大公开 API，而带来的能力 `execute` 选项已经具备。

只有端点 URL 时再用 `useFetcher*Query` Hook。快照端点的形式是 `order/snapshot/paged/state`（不是 `snapshot_state/paged`），过滤状态字段写成 `state.status`（不是 `status`），因为快照查询过滤的是快照文档，聚合状态位于其 `state` 下。

## 安装

需要 React 19.3 或更高版本（peer `react ^19.3.0`）。本包用 React Compiler 构建，产物导入 `react/compiler-runtime`，只有 React 19 提供它；不支持 React 18。

```sh
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/fetcher-react @ahoo-wang/wow-client @ahoo-wang/wow-react
```

需要 `@ahoo-wang/fetcher-react` 5.1.3 或更高版本（peer 范围 `^5.1.3 || ^6`）：这些 Hook 只从 `@ahoo-wang/fetcher-react/core` 和 `@ahoo-wang/fetcher-react/fetcher` 两个子路径导入，因此不会装上 `@ahoo-wang/fetcher-wow`。`@ahoo-wang/fetcher-react` 自身又把 `react-dom` `^19.3.0`、`@ahoo-wang/fetcher-cosec`、`@ahoo-wang/fetcher-storage` 和 `@ahoo-wang/fetcher-eventbus` 声明为 peer；npm 7+ 与 pnpm 8+ 会自动安装 peer，Yarn 用户需把它们加进安装命令。`@ahoo-wang/wow-client` 必须与 `@ahoo-wang/wow-react` 处于同一个小版本。包声明 Node >=22.12.0。这些 Hook 原来是 `@ahoo-wang/fetcher-react` 里的 Wow Hook，参见[迁移指南](../../../guide/typescript/migration.md)。

## 完整示例

```tsx
import { usePagedQuery } from '@ahoo-wang/wow-react';
import {
  SnapshotQueryClient,
  pagedQuery,
  filter,
} from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export function Users() {
  const query = usePagedQuery<User>({
    initialQuery: pagedQuery({ filter: filter.matchAll() }),
    execute: (request, attributes, controller) =>
      client.pagedState(request, attributes, controller),
  });
  return (
    <section>
      {query.error && <p role="alert">{query.error.message}</p>}
      <p>{query.result?.total ?? 0}</p>
      {query.result?.list.map(user => (
        <p key={user.id}>{user.name}</p>
      ))}
    </section>
  );
}
```

列表流随行到达随时渲染：

```tsx
import { useListStreamQuery } from '@ahoo-wang/wow-react';
import {
  filter,
  listQuery,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
interface OrderState {
  id: string;
  status: string;
}
export function PaidOrders({
  client,
}: {
  client: SnapshotQueryClient<OrderState>;
}) {
  const { items, done, loading, error, abort } = useListStreamQuery<OrderState>(
    {
      initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
      execute: (query, attributes, abortController) =>
        client.listStateStream(query, attributes, abortController),
    },
  );
  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.id}>{order.id}</li>
        ))}
      </ul>
      {loading && <button onClick={abort}>Stop</button>}
      {done && <p>{items.length} orders</p>}
    </>
  );
}
```

只有端点 URL 时，`useFetcherListStreamQuery<OrderState>({ url: 'order/snapshot/list/state', initialQuery })` 返回同样的字段。

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期。共享类型排在最前，各 Hook 的选项与返回类型都扩展它们；查询类型可从 [wow-client 符号索引](../wow-client/symbols) 定位，`Condition`、`SingleQuery`、`ListQuery`、`PagedQuery` 和各 `…QueryRequest` 联合类型来自 `@ahoo-wang/wow-client/legacy`。运行时默认值和失败行为以本页上文为准。

### QueryStatus {#api-QueryStatus}

```ts
export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryExecutor {#api-QueryExecutor}

```ts
export type QueryExecutor<Q, R> = (
  query: Q,
  attributes: Record<string, unknown> | undefined,
  abortController: AbortController,
) => Promise<R>;
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### ListStreamExecutor {#api-ListStreamExecutor}

```ts
export type ListStreamExecutor<R, Q> = QueryExecutor<
  Q,
  ReadableStream<JsonServerSentEvent<R>>
>;
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryHookOptions {#api-QueryHookOptions}

```ts
export interface QueryHookOptions<Q, R, E = Error> {
  /**
   * The query, controlled: the hook runs it again whenever it changes by
   * content, so a new object with the same content does not.
   */
  query?: Q;
  /** The first query, uncontrolled: change it later with `setQuery()`. */
  initialQuery?: Q;
  /**
   * Whether the query runs on mount and whenever it changes; `true` by
   * default. With `false` it runs only through `execute()`.
   */
  autoExecute?: boolean;
  /** Handed to `execute` with every run; not part of what identifies a run. */
  attributes?: Record<string, unknown>;
  /** Runs one query. */
  execute: QueryExecutor<Q, R>;
  /** Called with the result of each run that succeeds. */
  onSuccess?: (result: R) => void | Promise<void>;
  /** Called with the error of each run that fails. */
  onError?: (error: E) => void | Promise<void>;
}
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryHookReturn {#api-QueryHookReturn}

```ts
export interface QueryHookReturn<Q, R, E = Error> {
  /** Where the hook stands; see {@link QueryStatus}. */
  status: QueryStatus;
  /** Whether a request is in flight: the same as `status === 'loading'`. */
  loading: boolean;
  /** The result of the latest successful run, or `undefined`. */
  result: R | undefined;
  /** Why the latest run failed, or `undefined`. */
  error: E | undefined;
  /** Runs the current query again, aborting the request in flight. */
  execute: () => Promise<void>;
  /** Aborts the request in flight and returns to `idle`. */
  abort: () => void;
  /** Returns to `idle` and clears `result` and `error`. */
  reset: () => void;
  /** The current query. */
  getQuery: () => Q | undefined;
  /** Replaces the query; it runs when `autoExecute` is on. */
  setQuery: (query: Q) => void;
}
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### useFetcherCountQuery {#api-useFetcherCountQuery}

```ts
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts)

```ts
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts)

### UseFetcherCountQueryOptions {#api-UseFetcherCountQueryOptions}

```ts
export interface UseFetcherCountQueryOptions<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends Omit<QueryHookOptions<Q, number, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/count` for the snapshots of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts)

### UseFetcherCountQueryReturn {#api-UseFetcherCountQueryReturn}

```ts
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookReturn<Q, number, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts)

### useFetcherListQuery {#api-useFetcherListQuery}

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts)

### UseFetcherListQueryOptions {#api-UseFetcherListQueryOptions}

```ts
export interface UseFetcherListQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/list/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts)

### UseFetcherListQueryReturn {#api-UseFetcherListQueryReturn}

```ts
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts)

### useFetcherListStreamQuery {#api-useFetcherListStreamQuery}

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<
    R,
    FIELDS,
    E,
    FilterListQuery<FIELDS>
  >,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts)

### UseFetcherListStreamQueryOptions {#api-UseFetcherListStreamQueryOptions}

```ts
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<UseListStreamQueryOptions<R, FIELDS, E, Q>, 'execute'> {
  /**
   * The list endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/list/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts)

### UseFetcherListStreamQueryReturn {#api-UseFetcherListStreamQueryReturn}

```ts
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseListStreamQueryReturn<R, FIELDS, E, Q> {}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts)

### useFetcherPagedQuery {#api-useFetcherPagedQuery}

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, Q>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts)

### UseFetcherPagedQueryOptions {#api-UseFetcherPagedQueryOptions}

```ts
export interface UseFetcherPagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, PagedList<R>, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/paged/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts)

### UseFetcherPagedQueryReturn {#api-UseFetcherPagedQueryReturn}

```ts
export interface UseFetcherPagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts)

### useFetcherSingleQuery {#api-useFetcherSingleQuery}

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherSingleQueryOptions<
    R,
    FIELDS,
    E,
    FilterSingleQuery<FIELDS>
  >,
): UseFetcherSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts)

### UseFetcherSingleQueryOptions {#api-UseFetcherSingleQueryOptions}

```ts
export interface UseFetcherSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/single/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts)

### UseFetcherSingleQueryReturn {#api-UseFetcherSingleQueryReturn}

```ts
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts)

### useCountQuery {#api-useCountQuery}

```ts
export function useCountQuery<FIELDS extends string = string, E = Error>(
  options: UseCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts)

```ts
export function useCountQuery<FIELDS extends string = string, E = Error>(
  options: UseCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts)

```ts
export function useCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts)

### UseCountQueryOptions {#api-UseCountQueryOptions}

```ts
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookOptions<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts)

### UseCountQueryReturn {#api-UseCountQueryReturn}

```ts
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookReturn<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts)

### useListQuery {#api-useListQuery}

```ts
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts)

```ts
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts)

### UseListQueryOptions {#api-UseListQueryOptions}

```ts
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookOptions<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts)

### UseListQueryReturn {#api-UseListQueryReturn}

```ts
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts)

### useListStreamQuery {#api-useListStreamQuery}

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts)

### UseListStreamQueryOptions {#api-UseListStreamQueryOptions}

```ts
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], E>, 'execute'> {
  /** Opens the stream for a query. */
  execute: ListStreamExecutor<R, Q>;
}
```

[typescript/wow-react/src/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts)

### UseListStreamQueryReturn {#api-UseListStreamQueryReturn}

```ts
export interface UseListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookReturn<Q, R[], E>, 'result'> {
  /** The rows of the current query received so far. */
  items: R[];
  /** Whether the stream ended normally, so `items` holds every row. */
  done: boolean;
}
```

[typescript/wow-react/src/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts)

### usePagedQuery {#api-usePagedQuery}

```ts
export function usePagedQuery<R, FIELDS extends string = string, E = Error>(
  options: UsePagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts)

```ts
export function usePagedQuery<R, FIELDS extends string = string, E = Error>(
  options: UsePagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts)

### UsePagedQueryOptions {#api-UsePagedQueryOptions}

```ts
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts)

### UsePagedQueryReturn {#api-UsePagedQueryReturn}

```ts
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts)

### useSingleQuery {#api-useSingleQuery}

```ts
export function useSingleQuery<R, FIELDS extends string = string, E = Error>(
  options: UseSingleQueryOptions<R, FIELDS, E, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts)

```ts
export function useSingleQuery<R, FIELDS extends string = string, E = Error>(
  options: UseSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts)

### UseSingleQueryOptions {#api-UseSingleQueryOptions}

```ts
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookOptions<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts)

### UseSingleQueryReturn {#api-UseSingleQueryReturn}

```ts
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts)

## 相关专题

[快照查询](../wow-client/snapshot-queries) · [过滤表达式](../wow-client/filters) · [投影、排序与分页](../wow-client/query-options) · [Storybook：Wow 查询 Hook](/storybook/?path=/docs/react-hooks-wow-queries--docs)

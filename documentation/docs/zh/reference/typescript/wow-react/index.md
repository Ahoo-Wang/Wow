---
title: 'wow-react 查询 Hook'
description: 'Wow 查询 Hook — @ahoo-wang/wow-react'
---

# wow-react 查询 Hook

Wow Hook 为同一个查询执行器限定请求/结果类型，不替你创建客户端或构建条件。服务版本要求 `execute` 函数，Fetcher 版本要求 `url`，可选命名或实例 Fetcher。

| 服务 Hook          | Fetcher Hook              | 请求 → 结果                                                       |
| ------------------ | ------------------------- | ----------------------------------------------------------------- |
| useSingleQuery     | useFetcherSingleQuery     | SingleQueryRequest → R                                            |
| useListQuery       | useFetcherListQuery       | ListQueryRequest → R[]                                            |
| usePagedQuery      | useFetcherPagedQuery      | PagedQueryRequest → PagedList&lt;R&gt;，含 total/list             |
| useCountQuery      | useFetcherCountQuery      | Condition 或 FilterExpression → number                            |
| useListStreamQuery | useFetcherListStreamQuery | ListQueryRequest → JsonServerSentEvent&lt;R&gt; 的 ReadableStream |

所有变体通过重载支持旧 condition 查询和新 filter 查询；`FIELDS` 仅在编译期约束字段名，泛型不校验服务端 JSON。对应 `Use…Options`、`Use…Return` 继承 [查询状态](https://fetcher.ahoo.me/zh/reference/react/promise-and-query-state)，Fetcher 变体还继承 [Fetcher 选项](https://fetcher.ahoo.me/zh/reference/react/fetcher-hooks)。默认 autoExecute true、propagateError false，没有传入就没有初始查询。Fetcher 变体 POST 查询，默认 JSON 提取；流版本在展开 options 后强制设置 `JsonEventStreamResultExtractor`。

把 controller 传给服务客户端才能停止被替代请求的 I/O。流的 success 表示已取得 ReadableStream，不代表已完整消费。Hook 不累积消息、不关闭 reader，也不重试失败流。请求提取完成后，当前执行器可能已经不再持有对应 controller；消费者必须在替换/卸载时取消 reader 或 stream。生命周期见 [EventStream 消费](https://fetcher.ahoo.me/zh/reference/eventstream/consumption-and-cancellation)。

## 安装

```sh
pnpm add react @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/fetcher-react @ahoo-wang/wow-client @ahoo-wang/wow-react
```

需要 `@ahoo-wang/fetcher-react` 5.1.3 或更高版本（peer 范围 `^5.1.3 || ^6`）：这些 Hook 只从 `@ahoo-wang/fetcher-react/core` 和 `@ahoo-wang/fetcher-react/fetcher` 两个子路径导入，因此不会装上 `@ahoo-wang/fetcher-wow`。`@ahoo-wang/wow-client` 必须与 `@ahoo-wang/wow-react` 处于同一个小版本。这些 Hook 原来是 `@ahoo-wang/fetcher-react` 里的 Wow Hook，参见[迁移指南](../../../guide/typescript/migration.md)。

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

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，`UseQueryOptions` 等继承类型可从 [fetcher-react 符号索引](https://fetcher.ahoo.me/zh/reference/react/symbols) 定位，查询类型可从 [wow-client 符号索引](../wow-client/symbols) 定位。运行时默认值和失败行为以本页上文为准。

### useFetcherCountQuery {#api-useFetcherCountQuery}

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:89](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L89)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:95](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L95)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:101](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L101)

### UseFetcherCountQueryOptions {#api-UseFetcherCountQueryOptions}

```ts
export interface UseFetcherCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
> extends UseFetcherQueryOptions<Q, number, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L29)

### UseFetcherCountQueryReturn {#api-UseFetcherCountQueryReturn}

```ts
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
> extends UseQueryReturn<Q, number, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:44](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L44)

### useFetcherListQuery {#api-useFetcherListQuery}

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:128](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L128)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:135](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L135)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:142](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L142)

### UseFetcherListQueryOptions {#api-UseFetcherListQueryOptions}

```ts
export interface UseFetcherListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, R[], E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L32)

### UseFetcherListQueryReturn {#api-UseFetcherListQueryReturn}

```ts
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryReturn<Q, R[], E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L47)

### useFetcherListStreamQuery {#api-useFetcherListStreamQuery}

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:140](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L140)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListStreamQueryOptions<
    R,
    FIELDS,
    E,
    FilterListQuery<FIELDS>
  >,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:147](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L147)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:159](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L159)

### UseFetcherListStreamQueryOptions {#api-UseFetcherListStreamQueryOptions}

```ts
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseFetcherQueryOptions<
  Q,
  ReadableStream<JsonServerSentEvent<R>>,
  E
> {}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:36](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L36)

### UseFetcherListStreamQueryReturn {#api-UseFetcherListStreamQueryReturn}

```ts
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryReturn<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:57](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L57)

### useFetcherPagedQuery {#api-useFetcherPagedQuery}

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:140](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L140)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:147](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L147)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, Q>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:154](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L154)

### UseFetcherPagedQueryOptions {#api-UseFetcherPagedQueryOptions}

```ts
export interface UseFetcherPagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L35)

### UseFetcherPagedQueryReturn {#api-UseFetcherPagedQueryReturn}

```ts
export interface UseFetcherPagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
> extends UseQueryReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:52](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L52)

### useFetcherSingleQuery {#api-useFetcherSingleQuery}

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:113](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L113)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherSingleQueryOptions<
    R,
    FIELDS,
    E,
    FilterSingleQuery<FIELDS>
  >,
): UseFetcherSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:120](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L120)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:132](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L132)

### UseFetcherSingleQueryOptions {#api-UseFetcherSingleQueryOptions}

```ts
export interface UseFetcherSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, R, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:33](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L33)

### UseFetcherSingleQueryReturn {#api-UseFetcherSingleQueryReturn}

```ts
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
> extends UseQueryReturn<Q, R, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:49](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L49)

### useCountQuery {#api-useCountQuery}

```ts
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts:61](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L61)

```ts
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts:64](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L64)

```ts
export function useCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/useCountQuery.ts:67](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L67)

### UseCountQueryOptions {#api-UseCountQueryOptions}

```ts
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
> extends UseQueryOptions<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L25)

### UseCountQueryReturn {#api-UseCountQueryReturn}

```ts
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = Condition<FIELDS>,
> extends UseQueryReturn<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts:38](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L38)

### useListQuery {#api-useListQuery}

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts:75](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L75)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts:82](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L82)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListQuery.ts:89](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L89)

### UseListQueryOptions {#api-UseListQueryOptions}

```ts
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryOptions<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L31)

### UseListQueryReturn {#api-UseListQueryReturn}

```ts
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryReturn<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts:46](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L46)

### useListStreamQuery {#api-useListStreamQuery}

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts:76](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L76)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts:83](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L83)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListStreamQuery.ts:90](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L90)

### UseListStreamQueryOptions {#api-UseListStreamQueryOptions}

```ts
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryOptions<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/useListStreamQuery.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L32)

### UseListStreamQueryReturn {#api-UseListStreamQueryReturn}

```ts
export interface UseListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryReturn<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/useListStreamQuery.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L47)

### usePagedQuery {#api-usePagedQuery}

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts:77](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L77)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts:84](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L84)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/usePagedQuery.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L91)

### UsePagedQueryOptions {#api-UsePagedQueryOptions}

```ts
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
> extends UseQueryOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L32)

### UsePagedQueryReturn {#api-UsePagedQueryReturn}

```ts
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = PagedQuery<FIELDS>,
> extends UseQueryReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L47)

### useSingleQuery {#api-useSingleQuery}

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts:75](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L75)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts:82](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L82)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useSingleQuery.ts:89](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L89)

### UseSingleQueryOptions {#api-UseSingleQueryOptions}

```ts
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
> extends UseQueryOptions<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L31)

### UseSingleQueryReturn {#api-UseSingleQueryReturn}

```ts
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = SingleQuery<FIELDS>,
> extends UseQueryReturn<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts:46](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L46)

## 相关专题

[Fetcher 请求 Hook](https://fetcher.ahoo.me/zh/reference/react/fetcher-hooks) · [Promise 与查询状态](https://fetcher.ahoo.me/zh/reference/react/promise-and-query-state) · [快照查询](../wow-client/snapshot-queries) · [过滤表达式](../wow-client/filters) · [投影、排序与分页](../wow-client/query-options) · [Storybook：Wow 查询 Hook](/storybook/?path=/docs/react-hooks-wow-queries--docs)

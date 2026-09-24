---
title: 'wow-react query hooks'
description: 'Wow query hooks — @ahoo-wang/wow-react'
---

# wow-react query hooks

Wow hooks specialize the same query executor with request/result types. They do not create query clients or build conditions for you. Use service-based variants with an `execute` function, or Fetcher variants with a required `url` and optional named/instance Fetcher.

| Service hook       | Fetcher hook              | Request → result                                                  |
| ------------------ | ------------------------- | ----------------------------------------------------------------- |
| useSingleQuery     | useFetcherSingleQuery     | SingleQueryRequest → R                                            |
| useListQuery       | useFetcherListQuery       | ListQueryRequest → R[]                                            |
| usePagedQuery      | useFetcherPagedQuery      | PagedQueryRequest → PagedList&lt;R&gt; (`total`, `list`)          |
| useCountQuery      | useFetcherCountQuery      | Condition or FilterExpression → number                            |
| useListStreamQuery | useFetcherListStreamQuery | ListQueryRequest → ReadableStream of JsonServerSentEvent&lt;R&gt; |

The query type defaults to `FilterSingleQuery`, `FilterListQuery`, `FilterPagedQuery`, or `FilterExpression`. A second overload of each hook accepts the deprecated `Condition` queries imported from `@ahoo-wang/wow-client/legacy`, which Wow 8.10 servers need; that overload is removed in v10. `SingleQueryRequest`, `ListQueryRequest`, and `PagedQueryRequest` in the signatures below are the unions of both kinds, also exported by `/legacy`. `FIELDS` restricts field names statically. Type arguments do not validate server JSON. The corresponding `Use…Options` and `Use…Return` interfaces inherit [query state](https://fetcher.ahoo.me/reference/react/promise-and-query-state) and, for Fetcher variants, [Fetcher options](https://fetcher.ahoo.me/reference/react/fetcher-hooks). Defaults: autoExecute true, propagateError false, no initial query unless provided. Fetcher variants POST the query, default to JSON extraction; the stream variant forcibly uses `JsonEventStreamResultExtractor` after spreading options.

Pass the controller to the service client so superseded requests can stop I/O. Stream success means a ReadableStream was obtained, not that it was fully consumed. The Hook does not accumulate messages, close a reader, or retry a failed stream. Once request extraction resolves its controller may no longer be owned by the active executor; the consumer must cancel its reader/stream on replacement or unmount. Use [EventStream consumption](https://fetcher.ahoo.me/reference/eventstream/consumption-and-cancellation) for that lifecycle.

## Install

```sh
pnpm add react @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/fetcher-react @ahoo-wang/wow-client @ahoo-wang/wow-react
```

`@ahoo-wang/fetcher-react` 5.1.3 or later is required (peer range `^5.1.3 || ^6`): the hooks import only its `@ahoo-wang/fetcher-react/core` and `@ahoo-wang/fetcher-react/fetcher` subpaths, so `@ahoo-wang/fetcher-wow` is not installed. `@ahoo-wang/wow-client` must be on the same minor version as `@ahoo-wang/wow-react`. The package declares Node >=22.12.0. These hooks were the Wow hooks of `@ahoo-wang/fetcher-react`; see the [migration guide](../../../guide/typescript/migration.md).

## Complete example

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

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited types such as `UseQueryOptions` through the [fetcher-react symbol index](https://fetcher.ahoo.me/reference/react/symbols) and query types through the [wow-client symbol index](../wow-client/symbols); `Condition`, `SingleQuery`, `ListQuery`, `PagedQuery`, and the `…QueryRequest` unions come from `@ahoo-wang/wow-client/legacy`. Runtime defaults and failure behavior are described above.

### useFetcherCountQuery {#api-useFetcherCountQuery}

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:93](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L93)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:99](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L99)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:105](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L105)

### UseFetcherCountQueryOptions {#api-UseFetcherCountQueryOptions}

```ts
export interface UseFetcherCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
> extends UseFetcherQueryOptions<Q, number, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L31)

### UseFetcherCountQueryReturn {#api-UseFetcherCountQueryReturn}

```ts
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
> extends UseQueryReturn<Q, number, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherCountQuery.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherCountQuery.ts#L47)

### useFetcherListQuery {#api-useFetcherListQuery}

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:126](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L126)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:133](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L133)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:140](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L140)

### UseFetcherListQueryOptions {#api-UseFetcherListQueryOptions}

```ts
export interface UseFetcherListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, R[], E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:30](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L30)

### UseFetcherListQueryReturn {#api-UseFetcherListQueryReturn}

```ts
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryReturn<Q, R[], E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListQuery.ts:45](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListQuery.ts#L45)

### useFetcherListStreamQuery {#api-useFetcherListStreamQuery}

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

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:138](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L138)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:150](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L150)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:157](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L157)

### UseFetcherListStreamQueryOptions {#api-UseFetcherListStreamQueryOptions}

```ts
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseFetcherQueryOptions<
  Q,
  ReadableStream<JsonServerSentEvent<R>>,
  E
> {}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:34](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L34)

### UseFetcherListStreamQueryReturn {#api-UseFetcherListStreamQueryReturn}

```ts
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryReturn<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts:55](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherListStreamQuery.ts#L55)

### useFetcherPagedQuery {#api-useFetcherPagedQuery}

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:140](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L140)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:147](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L147)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
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
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherPagedQuery.ts#L35)

### UseFetcherPagedQueryReturn {#api-UseFetcherPagedQueryReturn}

```ts
export interface UseFetcherPagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
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
  options: UseFetcherSingleQueryOptions<
    R,
    FIELDS,
    E,
    FilterSingleQuery<FIELDS>
  >,
): UseFetcherSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:114](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L114)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:126](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L126)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:133](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L133)

### UseFetcherSingleQueryOptions {#api-UseFetcherSingleQueryOptions}

```ts
export interface UseFetcherSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, R, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:34](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L34)

### UseFetcherSingleQueryReturn {#api-UseFetcherSingleQueryReturn}

```ts
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseQueryReturn<Q, R, E> {}
```

[typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/fetcher/useFetcherSingleQuery.ts#L50)

### useCountQuery {#api-useCountQuery}

```ts
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts:68](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L68)

```ts
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/useCountQuery.ts:71](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L71)

```ts
export function useCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/useCountQuery.ts:74](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L74)

### UseCountQueryOptions {#api-UseCountQueryOptions}

```ts
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
> extends UseQueryOptions<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts:30](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L30)

### UseCountQueryReturn {#api-UseCountQueryReturn}

```ts
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> = FilterExpression<FIELDS>,
> extends UseQueryReturn<Q, number, E> {}
```

[typescript/wow-react/src/useCountQuery.ts:44](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useCountQuery.ts#L44)

### useListQuery {#api-useListQuery}

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts:76](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L76)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListQuery.ts:83](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L83)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListQuery.ts:90](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L90)

### UseListQueryOptions {#api-UseListQueryOptions}

```ts
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryOptions<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L32)

### UseListQueryReturn {#api-UseListQueryReturn}

```ts
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryReturn<Q, R[], E> {}
```

[typescript/wow-react/src/useListQuery.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListQuery.ts#L47)

### useListStreamQuery {#api-useListStreamQuery}

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts:77](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L77)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/useListStreamQuery.ts:84](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L84)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useListStreamQuery.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L91)

### UseListStreamQueryOptions {#api-UseListStreamQueryOptions}

```ts
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryOptions<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/useListStreamQuery.ts:33](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L33)

### UseListStreamQueryReturn {#api-UseListStreamQueryReturn}

```ts
export interface UseListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseQueryReturn<Q, ReadableStream<JsonServerSentEvent<R>>, E> {}
```

[typescript/wow-react/src/useListStreamQuery.ts:48](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useListStreamQuery.ts#L48)

### usePagedQuery {#api-usePagedQuery}

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts:80](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L80)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/usePagedQuery.ts:87](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L87)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/usePagedQuery.ts:94](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L94)

### UsePagedQueryOptions {#api-UsePagedQueryOptions}

```ts
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends UseQueryOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L35)

### UsePagedQueryReturn {#api-UsePagedQueryReturn}

```ts
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends UseQueryReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/usePagedQuery.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/usePagedQuery.ts#L50)

### useSingleQuery {#api-useSingleQuery}

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts:79](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L79)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/useSingleQuery.ts:86](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L86)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/useSingleQuery.ts:93](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L93)

### UseSingleQueryOptions {#api-UseSingleQueryOptions}

```ts
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseQueryOptions<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L35)

### UseSingleQueryReturn {#api-UseSingleQueryReturn}

```ts
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseQueryReturn<Q, R, E> {}
```

[typescript/wow-react/src/useSingleQuery.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/useSingleQuery.ts#L50)

## Related topics

[Fetcher hooks](https://fetcher.ahoo.me/reference/react/fetcher-hooks) · [Promise and query state](https://fetcher.ahoo.me/reference/react/promise-and-query-state) · [Snapshot queries](../wow-client/snapshot-queries) · [Filter expressions](../wow-client/filters) · [Projection, sorting and pagination](../wow-client/query-options) · [Storybook: Wow query hooks](/storybook/?path=/docs/react-hooks-wow-queries--docs)

---
title: 'Projection, sorting and pagination'
description: 'Projection, sorting and pagination — @ahoo-wang/wow-client'
---

# Projection, sorting and pagination

Query builders return plain serializable objects and do not execute HTTP. The root entry's `singleQuery`, `listQuery`, and `pagedQuery` build filter queries. Builders of the same names that build deprecated `Condition` queries, for Wow 8.10 servers, come from `@ahoo-wang/wow-client/legacy`; their defaults are listed with each signature below. Everything on this page is also exported by `@ahoo-wang/wow-client/dsl`, which loads no HTTP code — see [entry points](./#entries).

| Builder / model                     | Defaults and precedence                                                                                          |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| pagination({ index?, size? }?)      | index 1, size 10; no integer/range validation in this helper.                                                    |
| projection({ include?, exclude? }?) | Both omitted; DEFAULT_PROJECTION is a frozen {}. defaultProjection() returns a new {} on each call.             |
| asc(field), desc(field)             | `{ field, direction: ASC/DESC }`; no field validation in these helpers.                                          |
| singleQuery(options?)               | filter defaults to `filter.matchAll()`; projection/sort remain undefined.                                        |
| listQuery(options?)                 | filter defaults to `filter.matchAll()`; limit is sent only when given, otherwise the server's default list size applies. |
| pagedQuery(options?)                | pagination defaults to a copy of DEFAULT_PAGINATION `{index:1,size:10}`.                                         |
| pagedList({ total?, list? }?)       | list defaults to a new []; total defaults to list.length; returns `{total,list}`.                                |

A `null` filter throws `TypeError` (a `null` condition does the same in the `/legacy` builders). These checks are not full nested-expression validation. Pagination/list limit helpers do not enforce server limits. An absent or 0 limit lets the server decide: over HTTP Wow applies its configured default list size (100 by default) and refuses a limit above its maximum list size (1000 by default). An absent limit is not a client-side promise to fetch all rows.

`FilterQueryable` and the Filter-prefixed forms use required filter. `Queryable`/SingleQuery/ListQuery/PagedQuery are the deprecated condition family and, with the `*QueryRequest` unions that accept either form, are exported by `@ahoo-wang/wow-client/legacy`; the query clients accept both forms. ProjectionCapable and SortCapable are optional mixins. PagedList is total/list, independent of the requested page index. DEFAULT_PAGINATION and DEFAULT_PROJECTION are frozen; the builders copy them. No network resources need cleanup. For validated cursor-size constraints use [cursorQuery](./cursor-queries).

## Complete example

```ts
import {
  listQuery,
  pagedQuery,
  filter,
  projection,
  desc,
  pagedList,
} from '@ahoo-wang/wow-client';
const page = pagedQuery({
  filter: filter.matchAll(),
  pagination: { index: 2, size: 20 },
  projection: projection({ include: ['state.name'] }),
  sort: [desc('state.name')],
});
console.assert(listQuery().limit === undefined);
console.assert(listQuery({ limit: 20 }).limit === 20);
console.assert(pagedList({ list: ['a'] }).total === 1);
console.log(page);
```

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### pagination {#api-pagination}

```ts
export function pagination(options?: Partial<Pagination>): Pagination;
```

Implementation defaults: `index = DEFAULT_PAGINATION.index`; `size = DEFAULT_PAGINATION.size`; `options = {}`.

[typescript/wow-client/src/dsl/pagination.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/pagination.ts)

### Pagination {#api-Pagination}

```ts
export interface Pagination {
  index: number;
  size: number;
}
```

[typescript/wow-client/src/dsl/pagination.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/pagination.ts)

### DEFAULT_PAGINATION {#api-DEFAULT_PAGINATION}

```ts
declare const DEFAULT_PAGINATION: Readonly<Pagination>;
```

[typescript/wow-client/src/dsl/pagination.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/pagination.ts)

### defaultProjection {#api-defaultProjection}

```ts
export function defaultProjection<
  FIELDS extends string = string,
>(): Projection<FIELDS>;
```

[typescript/wow-client/src/dsl/projection.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/projection.ts)

### projection {#api-projection}

```ts
export function projection<FIELDS extends string = string>(
  options?: Projection<FIELDS>,
): Projection<FIELDS>;
```

Implementation defaults: `options = defaultProjection()`.

[typescript/wow-client/src/dsl/projection.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/projection.ts)

### Projection {#api-Projection}

```ts
export interface Projection<FIELDS extends string = string> {
  include?: FIELDS[];
  exclude?: FIELDS[];
}
```

[typescript/wow-client/src/dsl/projection.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/projection.ts)

### DEFAULT_PROJECTION {#api-DEFAULT_PROJECTION}

```ts
declare const DEFAULT_PROJECTION: Readonly<Projection>;
```

[typescript/wow-client/src/dsl/projection.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/projection.ts)

### ProjectionCapable {#api-ProjectionCapable}

```ts
export interface ProjectionCapable<FIELDS extends string = string> {
  projection?: Projection<FIELDS>;
}
```

[typescript/wow-client/src/dsl/projection.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/projection.ts)

### singleQuery {#api-singleQuery}

```ts
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>>,
): FilterSingleQuery<FIELDS>;
```

Implementation defaults: `filter = filter.matchAll()`; `options = {}`. A `null` filter throws `TypeError`.

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

Deprecated Condition form, exported by `@ahoo-wang/wow-client/legacy` (removed in v10):

```ts
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<SingleQuery<FIELDS>>,
): SingleQuery<FIELDS>;
```

Implementation defaults: `condition = all()`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### listQuery {#api-listQuery}

```ts
export function listQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>> & { limit?: number },
): FilterListQuery<FIELDS>;
```

Implementation defaults: `filter = filter.matchAll()`; `limit` stays `undefined`; `options = {}`. A `null` filter throws `TypeError`.

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

Deprecated Condition form, exported by `@ahoo-wang/wow-client/legacy` (removed in v10):

```ts
export function listQuery<FIELDS extends string = string>(
  options?: Partial<ListQuery<FIELDS>>,
): ListQuery<FIELDS>;
```

Implementation defaults: `condition = all()`; `limit = DEFAULT_PAGINATION.size`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### pagedQuery {#api-pagedQuery}

```ts
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>> & { pagination?: Pagination },
): FilterPagedQuery<FIELDS>;
```

Implementation defaults: `filter = filter.matchAll()`; `pagination = { ...DEFAULT_PAGINATION }`; `options = {}`. A `null` filter throws `TypeError`.

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

Deprecated Condition form, exported by `@ahoo-wang/wow-client/legacy` (removed in v10):

```ts
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<PagedQuery<FIELDS>>,
): PagedQuery<FIELDS>;
```

Implementation defaults: `condition = all()`; `pagination = { ...DEFAULT_PAGINATION }`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### pagedList {#api-pagedList}

```ts
export function pagedList<T>(options?: Partial<PagedList<T>>): PagedList<T>;
```

Implementation defaults: `list = []` (a new array per call); `total = list.length`; `options = {}`.

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### Queryable {#api-Queryable}

```ts
export interface Queryable<FIELDS extends string = string>
  extends
    ConditionCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### FilterQueryable {#api-FilterQueryable}

```ts
export interface FilterQueryable<FIELDS extends string = string>
  extends
    FilterCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### SingleQuery {#api-SingleQuery}

```ts
export interface SingleQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {}
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### FilterSingleQuery {#api-FilterSingleQuery}

```ts
export interface FilterSingleQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {}
```

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### SingleQueryRequest {#api-SingleQueryRequest}

```ts
export type SingleQueryRequest<FIELDS extends string = string> =
  | FilterSingleQuery<FIELDS>
  | SingleQuery<FIELDS>;
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10. The query clients accept this union, so either form can be sent.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### ListQuery {#api-ListQuery}

```ts
export interface ListQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  limit?: number;
}
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### FilterListQuery {#api-FilterListQuery}

```ts
export interface FilterListQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  limit?: number;
}
```

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### ListQueryRequest {#api-ListQueryRequest}

```ts
export type ListQueryRequest<FIELDS extends string = string> =
  | FilterListQuery<FIELDS>
  | ListQuery<FIELDS>;
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10. The query clients accept this union, so either form can be sent.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### PagedQuery {#api-PagedQuery}

```ts
export interface PagedQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  pagination?: Pagination;
}
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### FilterPagedQuery {#api-FilterPagedQuery}

```ts
export interface FilterPagedQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  pagination?: Pagination;
}
```

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### PagedQueryRequest {#api-PagedQueryRequest}

```ts
export type PagedQueryRequest<FIELDS extends string = string> =
  | FilterPagedQuery<FIELDS>
  | PagedQuery<FIELDS>;
```

Exported by `@ahoo-wang/wow-client/legacy`; deprecated, removed in v10. The query clients accept this union, so either form can be sent.

[typescript/wow-client/src/legacy/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts)

### PagedList {#api-PagedList}

```ts
export interface PagedList<T> {
  total: number;
  list: T[];
}
```

[typescript/wow-client/src/dsl/queryable.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/queryable.ts)

### asc {#api-asc}

```ts
export function asc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/dsl/sort.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/sort.ts)

### desc {#api-desc}

```ts
export function desc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/dsl/sort.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/sort.ts)

### SortDirection {#api-SortDirection}

```ts
export enum SortDirection {
  ASC = 'ASC',
  DESC = 'DESC',
}
```

[typescript/wow-client/src/dsl/sort.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/sort.ts)

### FieldSort {#api-FieldSort}

```ts
export interface FieldSort<FIELDS extends string = string> {
  field: FIELDS;
  direction: SortDirection;
}
```

[typescript/wow-client/src/dsl/sort.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/sort.ts)

### SortCapable {#api-SortCapable}

```ts
export interface SortCapable<FIELDS extends string = string> {
  sort?: FieldSort<FIELDS>[];
}
```

[typescript/wow-client/src/dsl/sort.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/sort.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)

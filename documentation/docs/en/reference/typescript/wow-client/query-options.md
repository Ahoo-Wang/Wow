---
title: 'Projection, sorting and pagination'
description: 'Projection, sorting and pagination — @ahoo-wang/wow-client'
---

# Projection, sorting and pagination

Query builders return plain serializable objects and do not execute HTTP. The new filter form and legacy condition form have distinct request types and different list defaults. Prefer an explicit limit when migrating.

| Builder / model                     | Defaults and precedence                                                                                          |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| pagination({ index?, size? }?)      | index 1, size 10; no integer/range validation in this helper.                                                    |
| projection({ include?, exclude? }?) | Both omitted; DEFAULT_PROJECTION is {}. defaultProjection() returns the shared default object, not a fresh copy. |
| asc(field), desc(field)             | `{ field, direction: ASC/DESC }`; no field validation in these helpers.                                          |
| singleQuery(options?)               | No filter → condition all(); projection/sort remain undefined.                                                   |
| listQuery(options?)                 | Legacy condition form defaults limit 10; filter form defaults limit 0. Explicit 0 is retained.                   |
| pagedQuery(options?)                | pagination DEFAULT_PAGINATION `{index:1,size:10}`.                                                               |
| pagedList({ total?, list? }?)       | list defaults []; total defaults to list.length; returns `{total,list}`.                                         |

When filter is defined it wins over condition; builders output only one of these fields. Null filter throws. Null condition throws when it is selected. These checks are not full nested-expression validation. Pagination/list limit helpers do not enforce server limits, and limit 0's endpoint semantics must be supported by the server. The filter-form default is the wire value 0, not a client-side promise to fetch all rows.

`Queryable`/SingleQuery/ListQuery/PagedQuery are the legacy condition family. `FilterQueryable` and the Filter-prefixed forms use required filter. `*QueryRequest` unions accept either. ProjectionCapable and SortCapable are optional mixins. PagedList is total/list, independent of the requested page index. Shared DEFAULT_* and EMPTY_PAGED_LIST constants are mutable objects in JavaScript; treat them as read-only defaults. No network resources need cleanup. For validated cursor-size constraints use [cursorQuery](./cursor-queries).

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
console.assert(listQuery().limit === 10);
console.assert(listQuery({ filter: filter.matchAll() }).limit === 0);
console.assert(pagedList({ list: ['a'] }).total === 1);
console.log(page);
```

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### pagination {#api-pagination}

```ts
export function pagination(options?: Partial<Pagination>): Pagination;
```

Implementation defaults: `index = DEFAULT_PAGINATION.index`; `size = DEFAULT_PAGINATION.size`; `options = DEFAULT_PAGINATION`.

[typescript/wow-client/src/query/pagination.ts:46](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/pagination.ts#L46)

### Pagination {#api-Pagination}

```ts
export interface Pagination {
  index: number;
  size: number;
}
```

[typescript/wow-client/src/query/pagination.ts:20](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/pagination.ts#L20)

### DEFAULT_PAGINATION {#api-DEFAULT_PAGINATION}

```ts
declare const DEFAULT_PAGINATION: Pagination;
```

[typescript/wow-client/src/query/pagination.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/pagination.ts#L29)

### defaultProjection {#api-defaultProjection}

```ts
export function defaultProjection<
  FIELDS extends string = string,
>(): Projection<FIELDS>;
```

[typescript/wow-client/src/query/projection.ts:28](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L28)

### projection {#api-projection}

```ts
export function projection<FIELDS extends string = string>(
  options?: Projection<FIELDS>,
): Projection<FIELDS>;
```

Implementation defaults: `options = defaultProjection()`.

[typescript/wow-client/src/query/projection.ts:46](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L46)

### Projection {#api-Projection}

```ts
export interface Projection<FIELDS extends string = string> {
  include?: FIELDS[];
  exclude?: FIELDS[];
}
```

[typescript/wow-client/src/query/projection.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L17)

### DEFAULT_PROJECTION {#api-DEFAULT_PROJECTION}

```ts
declare const DEFAULT_PROJECTION: Projection<string>;
```

[typescript/wow-client/src/query/projection.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L26)

### ProjectionCapable {#api-ProjectionCapable}

```ts
export interface ProjectionCapable<FIELDS extends string = string> {
  projection?: Projection<FIELDS>;
}
```

[typescript/wow-client/src/query/projection.ts:58](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L58)

### singleQuery {#api-singleQuery}

```ts
export function singleQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterSingleQuery<FIELDS>>,
): FilterSingleQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L91)

```ts
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<SingleQuery<FIELDS>>,
): SingleQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:95](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L95)

### listQuery {#api-listQuery}

```ts
export function listQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterListQuery<FIELDS>>,
): FilterListQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:149](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L149)

```ts
export function listQuery<FIELDS extends string = string>(
  options?: Partial<ListQuery<FIELDS>>,
): ListQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:153](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L153)

### pagedQuery {#api-pagedQuery}

```ts
export function pagedQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterPagedQuery<FIELDS>>,
): FilterPagedQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:208](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L208)

```ts
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<PagedQuery<FIELDS>>,
): PagedQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:212](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L212)

### pagedList {#api-pagedList}

```ts
export function pagedList<T>(options?: Partial<PagedList<T>>): PagedList<T>;
```

Implementation defaults: `list = []`; `options = EMPTY_PAGED_LIST`.

[typescript/wow-client/src/query/queryable.ts:257](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L257)

### Queryable {#api-Queryable}

```ts
export interface Queryable<FIELDS extends string = string>
  extends
    ConditionCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L24)

### FilterQueryable {#api-FilterQueryable}

```ts
export interface FilterQueryable<FIELDS extends string = string>
  extends
    FilterCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L31)

### SingleQuery {#api-SingleQuery}

```ts
export interface SingleQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:42](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L42)

### FilterSingleQuery {#api-FilterSingleQuery}

```ts
export interface FilterSingleQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:47](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L47)

### SingleQueryRequest {#api-SingleQueryRequest}

```ts
export type SingleQueryRequest<FIELDS extends string = string> =
  SingleQuery<FIELDS> | FilterSingleQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L51)

### ListQuery {#api-ListQuery}

```ts
export interface ListQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  limit?: number;
}
```

[typescript/wow-client/src/query/queryable.ts:119](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L119)

### FilterListQuery {#api-FilterListQuery}

```ts
export interface FilterListQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  limit?: number;
}
```

[typescript/wow-client/src/query/queryable.ts:125](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L125)

### ListQueryRequest {#api-ListQueryRequest}

```ts
export type ListQueryRequest<FIELDS extends string = string> =
  ListQuery<FIELDS> | FilterListQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:132](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L132)

### PagedQuery {#api-PagedQuery}

```ts
export interface PagedQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  pagination?: Pagination;
}
```

[typescript/wow-client/src/query/queryable.ts:178](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L178)

### FilterPagedQuery {#api-FilterPagedQuery}

```ts
export interface FilterPagedQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  pagination?: Pagination;
}
```

[typescript/wow-client/src/query/queryable.ts:184](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L184)

### PagedQueryRequest {#api-PagedQueryRequest}

```ts
export type PagedQueryRequest<FIELDS extends string = string> =
  PagedQuery<FIELDS> | FilterPagedQuery<FIELDS>;
```

[typescript/wow-client/src/query/queryable.ts:190](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L190)

### PagedList {#api-PagedList}

```ts
export interface PagedList<T> {
  total: number;
  list: T[];
}
```

[typescript/wow-client/src/query/queryable.ts:236](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L236)

### EMPTY_PAGED_LIST {#api-EMPTY_PAGED_LIST}

```ts
declare const EMPTY_PAGED_LIST: PagedList<any>;
```

[typescript/wow-client/src/query/queryable.ts:241](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L241)

### asc {#api-asc}

```ts
export function asc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/query/sort.ts:36](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L36)

### desc {#api-desc}

```ts
export function desc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/query/sort.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L50)

### SortDirection {#api-SortDirection}

```ts
export enum SortDirection {
  ASC = 'ASC',
  DESC = 'DESC',
}
```

[typescript/wow-client/src/query/sort.ts:18](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L18)

### FieldSort {#api-FieldSort}

```ts
export interface FieldSort<FIELDS extends string = string> {
  field: FIELDS;
  direction: SortDirection;
}
```

[typescript/wow-client/src/query/sort.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L26)

### SortCapable {#api-SortCapable}

```ts
export interface SortCapable<FIELDS extends string = string> {
  sort?: FieldSort<FIELDS>[];
}
```

[typescript/wow-client/src/query/sort.ts:62](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L62)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)

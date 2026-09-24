---
title: '投影、排序与分页'
description: '投影、排序与分页 — @ahoo-wang/wow-client'
---

# 投影、排序与分页

查询构造器返回可序列化普通对象，不执行 HTTP。根入口的 `singleQuery`、`listQuery`、`pagedQuery` 构造 filter 查询。构造已弃用 `Condition` 查询的同名构造器（供 Wow 8.10 服务端使用）来自 `@ahoo-wang/wow-client/legacy`，其默认值见下文各签名。本页所有内容也由 `@ahoo-wang/wow-client/dsl` 导出，该入口不加载任何 HTTP 代码，参见[入口](./#entries)。

| 构造器 / 模型                       | 默认值与优先级                                                                     |
| ----------------------------------- | ---------------------------------------------------------------------------------- |
| pagination({ index?, size? }?)      | index 1、size 10，本工具不校验整数或范围。                                         |
| projection({ include?, exclude? }?) | 两者默认省略；DEFAULT_PROJECTION 为冻结的 {}；defaultProjection() 每次返回新的 {}。 |
| asc(field)、desc(field)             | `{ field, direction: ASC/DESC }`；不校验字段。                                     |
| singleQuery(options?)               | filter 默认为 `filter.matchAll()`；projection/sort 保持 undefined。                |
| listQuery(options?)                 | filter 默认为 `filter.matchAll()`；只在给出 limit 时发送，否则由服务端使用默认列表条数。 |
| pagedQuery(options?)                | pagination 默认为 DEFAULT_PAGINATION 的副本，即 `{index:1,size:10}`。              |
| pagedList({ total?, list? }?)       | list 默认为新的 []，total 默认 list.length，返回 `{total,list}`。                  |

filter 为 null 时抛出 `TypeError`（`/legacy` 构造器中 condition 为 null 同样如此）；这不是完整嵌套表达式校验。分页/list limit 工具不约束服务端限制。limit 省略或为 0 时由服务端决定：经 HTTP 时 Wow 使用配置的默认列表条数（默认 100），超过最大列表条数（默认 1000）的 limit 会被拒绝。省略 limit 并非客户端保证读取全部行。

FilterQueryable 和 Filter 前缀类型要求 filter。Queryable/SingleQuery/ListQuery/PagedQuery 是已弃用的 condition 家族，它们和接受任一形式的 `*QueryRequest` 联合类型都由 `@ahoo-wang/wow-client/legacy` 导出；查询客户端两种形式都接受。ProjectionCapable、SortCapable 是可选属性组合；PagedList 只含 total/list，与请求页码独立。DEFAULT_PAGINATION 和 DEFAULT_PROJECTION 已冻结，构造器会复制它们。没有网络资源需要清理。需要校验游标大小时见 [cursorQuery](./cursor-queries)。

## 完整示例

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

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### pagination {#api-pagination}

```ts
export function pagination(options?: Partial<Pagination>): Pagination;
```

实现默认值: `index = DEFAULT_PAGINATION.index`; `size = DEFAULT_PAGINATION.size`; `options = {}`.

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
declare const DEFAULT_PAGINATION: Readonly<Pagination>;
```

[typescript/wow-client/src/query/pagination.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/pagination.ts#L29)

### defaultProjection {#api-defaultProjection}

```ts
export function defaultProjection<
  FIELDS extends string = string,
>(): Projection<FIELDS>;
```

[typescript/wow-client/src/query/projection.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L32)

### projection {#api-projection}

```ts
export function projection<FIELDS extends string = string>(
  options?: Projection<FIELDS>,
): Projection<FIELDS>;
```

实现默认值: `options = defaultProjection()`.

[typescript/wow-client/src/query/projection.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L50)

### Projection {#api-Projection}

```ts
export interface Projection<FIELDS extends string = string> {
  include?: FIELDS[];
  exclude?: FIELDS[];
}
```

[typescript/wow-client/src/query/projection.ts:19](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L19)

### DEFAULT_PROJECTION {#api-DEFAULT_PROJECTION}

```ts
declare const DEFAULT_PROJECTION: Readonly<Projection>;
```

[typescript/wow-client/src/query/projection.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L29)

### ProjectionCapable {#api-ProjectionCapable}

```ts
export interface ProjectionCapable<FIELDS extends string = string> {
  projection?: Projection<FIELDS>;
}
```

[typescript/wow-client/src/query/projection.ts:66](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/projection.ts#L66)

### singleQuery {#api-singleQuery}

```ts
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>>,
): FilterSingleQuery<FIELDS>;
```

实现默认值: `filter = filter.matchAll()`; `options = {}`。filter 为 `null` 时抛出 `TypeError`。

[typescript/wow-client/src/query/queryable.ts:83](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L83)

已弃用的 Condition 形式，由 `@ahoo-wang/wow-client/legacy` 导出（v10 移除）：

```ts
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<SingleQuery<FIELDS>>,
): SingleQuery<FIELDS>;
```

实现默认值: `condition = all()`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts:99](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L99)

### listQuery {#api-listQuery}

```ts
export function listQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>> & { limit?: number },
): FilterListQuery<FIELDS>;
```

实现默认值: `filter = filter.matchAll()`; `limit` 保持 `undefined`; `options = {}`。filter 为 `null` 时抛出 `TypeError`。

[typescript/wow-client/src/query/queryable.ts:106](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L106)

已弃用的 Condition 形式，由 `@ahoo-wang/wow-client/legacy` 导出（v10 移除）：

```ts
export function listQuery<FIELDS extends string = string>(
  options?: Partial<ListQuery<FIELDS>>,
): ListQuery<FIELDS>;
```

实现默认值: `condition = all()`; `limit = DEFAULT_PAGINATION.size`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts:113](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L113)

### pagedQuery {#api-pagedQuery}

```ts
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<FilterQueryable<FIELDS>> & { pagination?: Pagination },
): FilterPagedQuery<FIELDS>;
```

实现默认值: `filter = filter.matchAll()`; `pagination = { ...DEFAULT_PAGINATION }`; `options = {}`。filter 为 `null` 时抛出 `TypeError`。

[typescript/wow-client/src/query/queryable.ts:130](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L130)

已弃用的 Condition 形式，由 `@ahoo-wang/wow-client/legacy` 导出（v10 移除）：

```ts
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<PagedQuery<FIELDS>>,
): PagedQuery<FIELDS>;
```

实现默认值: `condition = all()`; `pagination = { ...DEFAULT_PAGINATION }`; `options = {}`.

[typescript/wow-client/src/legacy/queryable.ts:128](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L128)

### pagedList {#api-pagedList}

```ts
export function pagedList<T>(options?: Partial<PagedList<T>>): PagedList<T>;
```

实现默认值: `list = []`（每次调用都是新数组）; `total = list.length`; `options = {}`.

[typescript/wow-client/src/query/queryable.ts:154](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L154)

### Queryable {#api-Queryable}

```ts
export interface Queryable<FIELDS extends string = string>
  extends
    ConditionCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。

[typescript/wow-client/src/legacy/queryable.ts:30](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L30)

### FilterQueryable {#api-FilterQueryable}

```ts
export interface FilterQueryable<FIELDS extends string = string>
  extends
    FilterCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L24)

### SingleQuery {#api-SingleQuery}

```ts
export interface SingleQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {}
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。

[typescript/wow-client/src/legacy/queryable.ts:38](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L38)

### FilterSingleQuery {#api-FilterSingleQuery}

```ts
export interface FilterSingleQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {}
```

[typescript/wow-client/src/query/queryable.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L32)

### SingleQueryRequest {#api-SingleQueryRequest}

```ts
export type SingleQueryRequest<FIELDS extends string = string> =
  | FilterSingleQuery<FIELDS>
  | SingleQuery<FIELDS>;
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。查询客户端接受这个联合类型，两种形式都能发送。

[typescript/wow-client/src/legacy/queryable.ts:63](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L63)

### ListQuery {#api-ListQuery}

```ts
export interface ListQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  limit?: number;
}
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。

[typescript/wow-client/src/legacy/queryable.ts:43](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L43)

### FilterListQuery {#api-FilterListQuery}

```ts
export interface FilterListQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  limit?: number;
}
```

[typescript/wow-client/src/query/queryable.ts:37](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L37)

### ListQueryRequest {#api-ListQueryRequest}

```ts
export type ListQueryRequest<FIELDS extends string = string> =
  | FilterListQuery<FIELDS>
  | ListQuery<FIELDS>;
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。查询客户端接受这个联合类型，两种形式都能发送。

[typescript/wow-client/src/legacy/queryable.ts:72](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L72)

### PagedQuery {#api-PagedQuery}

```ts
export interface PagedQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  pagination?: Pagination;
}
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。

[typescript/wow-client/src/legacy/queryable.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L51)

### FilterPagedQuery {#api-FilterPagedQuery}

```ts
export interface FilterPagedQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  pagination?: Pagination;
}
```

[typescript/wow-client/src/query/queryable.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L50)

### PagedQueryRequest {#api-PagedQueryRequest}

```ts
export type PagedQueryRequest<FIELDS extends string = string> =
  | FilterPagedQuery<FIELDS>
  | PagedQuery<FIELDS>;
```

由 `@ahoo-wang/wow-client/legacy` 导出；已弃用，v10 移除。查询客户端接受这个联合类型，两种形式都能发送。

[typescript/wow-client/src/legacy/queryable.ts:81](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/queryable.ts#L81)

### PagedList {#api-PagedList}

```ts
export interface PagedList<T> {
  total: number;
  list: T[];
}
```

[typescript/wow-client/src/query/queryable.ts:142](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryable.ts#L142)

### asc {#api-asc}

```ts
export function asc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/query/sort.ts:38](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L38)

### desc {#api-desc}

```ts
export function desc<FIELDS extends string = string>(
  field: FIELDS,
): FieldSort<FIELDS>;
```

[typescript/wow-client/src/query/sort.ts:52](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L52)

### SortDirection {#api-SortDirection}

```ts
export enum SortDirection {
  ASC = 'ASC',
  DESC = 'DESC',
}
```

[typescript/wow-client/src/query/sort.ts:20](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L20)

### FieldSort {#api-FieldSort}

```ts
export interface FieldSort<FIELDS extends string = string> {
  field: FIELDS;
  direction: SortDirection;
}
```

[typescript/wow-client/src/query/sort.ts:28](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L28)

### SortCapable {#api-SortCapable}

```ts
export interface SortCapable<FIELDS extends string = string> {
  sort?: FieldSort<FIELDS>[];
}
```

[typescript/wow-client/src/query/sort.ts:64](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/sort.ts#L64)

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)

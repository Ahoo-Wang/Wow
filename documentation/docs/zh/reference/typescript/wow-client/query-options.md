---
title: '投影、排序与分页'
description: '投影、排序与分页 — @ahoo-wang/wow-client'
---

# 投影、排序与分页

查询构造器返回可序列化普通对象，不执行 HTTP。新 filter 与旧 condition 有不同请求类型和 list 默认值，迁移时优先显式指定 limit。

| 构造器 / 模型                       | 默认值与优先级                                                                     |
| ----------------------------------- | ---------------------------------------------------------------------------------- |
| pagination({ index?, size? }?)      | index 1、size 10，本工具不校验整数或范围。                                         |
| projection({ include?, exclude? }?) | 两者默认省略；DEFAULT_PROJECTION 为 {}；defaultProjection() 返回共享对象而非副本。 |
| asc(field)、desc(field)             | `{ field, direction: ASC/DESC }`；不校验字段。                                     |
| singleQuery(options?)               | 没有 filter 时 condition 为 all()；projection/sort 保持 undefined。                |
| listQuery(options?)                 | 旧 condition 默认 limit 10，新 filter 默认 0，显式 0 会保留。                      |
| pagedQuery(options?)                | pagination 默认 DEFAULT_PAGINATION，即 `{index:1,size:10}`。                       |
| pagedList({ total?, list? }?)       | list 默认 []，total 默认 list.length，返回 `{total,list}`。                        |

filter 已定义时优先于 condition，输出只保留其中一个字段。filter 为 null 会抛错，被选中的 condition 为 null 也抛错；这不是完整嵌套表达式校验。分页/list limit 工具不约束服务端限制；limit 0 的端点语义必须由服务端支持。filter 默认只是线上值 0，并非客户端保证读取全部行。

Queryable/SingleQuery/ListQuery/PagedQuery 是旧 condition 家族；FilterQueryable 和 Filter 前缀类型要求 filter；`*QueryRequest` 联合类型接受任一。ProjectionCapable、SortCapable 是可选属性组合；PagedList 只含 total/list，与请求页码独立。共享 DEFAULT_* 和 EMPTY_PAGED_LIST 在 JavaScript 中可变，应按只读默认值使用。没有网络资源需要清理。需要校验游标大小时见 [cursorQuery](./cursor-queries)。

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
console.assert(listQuery().limit === 10);
console.assert(listQuery({ filter: filter.matchAll() }).limit === 0);
console.assert(pagedList({ list: ['a'] }).total === 1);
console.log(page);
```

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### pagination {#api-pagination}

```ts
export function pagination(options?: Partial<Pagination>): Pagination;
```

实现默认值: `index = DEFAULT_PAGINATION.index`; `size = DEFAULT_PAGINATION.size`; `options = DEFAULT_PAGINATION`.

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

实现默认值: `options = defaultProjection()`.

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

实现默认值: `list = []`; `options = EMPTY_PAGED_LIST`.

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

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)

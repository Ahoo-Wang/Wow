---
title: '游标查询'
description: '游标查询 — @ahoo-wang/wow-client'
---

# 游标查询

游标分页使用不透明服务端 token 和 filter，不使用页码。`cursorQuery(options)` 本地校验 size 与排序字段数量，返回普通 CursorQuery，再由 SnapshotQueryClient.cursor/cursorState 或 EventStreamQueryClient.cursor 发送。

| 字段 / 常量         | 契约                                                                        |
| ------------------- | --------------------------------------------------------------------------- |
| filter              | 必填 FilterExpression，旧 Condition 不能作为 CursorQuery filter。           |
| projection、sort    | 默认 {} 和 []，续传 token 时保持同一个逻辑查询。                            |
| size                | 默认 DEFAULT_CURSOR_SIZE = 10；整数范围 1 到 MAX_CURSOR_SIZE = 2147483646，即游标模型的上限。经 HTTP 时服务端另有页大小上限，默认 100，更大的 size 会得到 400。 |
| sort 数量           | 最多 MAX_CURSOR_SORT_FIELDS = 32。                                          |
| cursor              | 首次默认 null，后续原样使用 response.nextCursor。                           |
| CursorPage&lt;T&gt; | `{ list: T[], nextCursor: string \| null }`；null 表示结束。                |

非法大小或排序字段过多会在联网前抛 TypeError。构造器不解析 token、不校验服务端能力、不验证唯一排序键、不冻结数据库快照，也不自动补平局排序键。游标有效性、过期和一致性属于服务端契约，不能编辑或推导 token。用 nextCursor 判断结束，不要用 list 长度；不足一页也可能有非 null 续传 token。

本包没有内置异步迭代器或 cursor-close 方法。下面循环接收一个 AbortSignal（来自控制器、`AbortSignal.timeout(ms)` 或数据请求库），被中止后即停止。退出循环停止后续 HTTP，中止该 signal 会取消当前请求；这不保证关闭服务端 PIT/session，因为本包不暴露释放端点。传输错误或无效/过期游标通过 Fetcher 拒绝（`toWowError` 可读出服务端错误码），应显式决定是否从 null 重启。

## 完整示例

```ts
import {
  SnapshotQueryClient,
  cursorQuery,
  filter,
  asc,
} from '@ahoo-wang/wow-client';
import type { CursorPage } from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export async function readUsers(signal: AbortSignal) {
  let cursor: string | null = null;
  const users: User[] = [];
  do {
    signal.throwIfAborted();
    const page: CursorPage<User> = await client.cursorState(
      cursorQuery({
        filter: filter.matchAll(),
        sort: [asc('state.name')],
        size: 100,
        cursor,
      }),
      undefined,
      signal,
    );
    users.push(...page.list);
    cursor = page.nextCursor;
  } while (cursor !== null);
  return users;
}
```

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### cursorQuery {#api-cursorQuery}

```ts
export function cursorQuery<FIELDS extends string = string>(
  options: CursorQuery<FIELDS>,
): CursorQuery<FIELDS>;
```

实现默认值: `projection = {}`; `sort = []`; `size = DEFAULT_CURSOR_SIZE`; `cursor = null`.

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

### DEFAULT_CURSOR_SIZE {#api-DEFAULT_CURSOR_SIZE}

```ts
declare const DEFAULT_CURSOR_SIZE: 10;
```

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

### MAX_CURSOR_SIZE {#api-MAX_CURSOR_SIZE}

```ts
declare const MAX_CURSOR_SIZE: 2147483646;
```

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

### MAX_CURSOR_SORT_FIELDS {#api-MAX_CURSOR_SORT_FIELDS}

```ts
declare const MAX_CURSOR_SORT_FIELDS: 32;
```

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

### CursorQuery {#api-CursorQuery}

```ts
export interface CursorQuery<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
  projection?: Projection<FIELDS>;
  sort?: FieldSort<FIELDS>[];
  size?: number;
  cursor?: string | null;
}
```

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

### CursorPage {#api-CursorPage}

```ts
export interface CursorPage<T> {
  list: T[];
  nextCursor: string | null;
}
```

[typescript/wow-client/src/query/cursorQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/cursorQuery.ts)

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)

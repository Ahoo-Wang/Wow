---
title: 读取与查询
description: 通过生成的客户端读取聚合的状态（在命令之后或某个版本上），用类型化的 DSL 查询快照和事件流，流式读取长列表，以及查询数据集。
---

# 读取与查询

生成的客户端以两种方式读取聚合：它的状态客户端按 ID 读取一个聚合，它的查询客户端对它所有的快照或事件流运行查询。查询 DSL `@ahoo-wang/wow-client/dsl` 构建查询，并以聚合状态的字段提供类型。本页使用 eShop 生成的 ordering 客户端（[生成客户端](./generate)）。

<!-- typecheck-generated: examples/eshop/web/src/generated -->

## 读取一个聚合 {#read-one-aggregate}

<!-- typecheck-context
import { OrderStateClient } from './generated/ordering/index.js';
declare const orderStates: OrderStateClient;
-->

```ts
const order = await orderStates.get('order-1');
console.log(order.version, order.state.status, order.state.totalAmount);
```

快照包含状态，以及聚合的 `aggregateId`、`tenantId` 和 `version`，它的 `ownerId` 与 `spaceId`、`tags`、是否 `deleted`，以及首末事件的时间与操作人。不存在的聚合以 404 `wow.aggregate.not-found` reject；`find(id, options?)` 则对它回答 `undefined`，其他 404 仍然 reject。`totalAmount` 在服务端是 `BigDecimal`，所以在这里是 `string`，数字与写入时完全相同。

选项可以选择另一个状态：

| 选项 | 读取 |
| --- | --- |
| `minVersion` | 这个版本或更新的最新状态：写后即可查 |
| `version` | 在这个版本提交的状态 |
| `at` | 截至这个时刻的状态，ISO-8601 文本或 `Date` |

命令之后，用它的结果给出的版本读取。落后的快照会先用日志追上来：

<!-- typecheck-context
import { OrderCommandClient, OrderStateClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
declare const orderStates: OrderStateClient;
-->

```ts
const placed = await orders.submitOrder({
  items: [{ productId: 'p-1', quantity: 1, unitPrice: '19.90' }],
});
const order = await orderStates.get(placed.ref.id, {
  minVersion: placed.version ?? 1,
});
```

聚合没有及时达到的版本，以 409 `wow.query.min-version-not-reached` reject；problem 的 `version` 是当前的版本。

`read` 以状态的实体标签 `etag` 回答；把它作为 `ifNoneMatch` 传回去，没有变化的状态会回答不带内容的 `{ notModified: true }`。`trace(id, { from, to, limit })` 读取其间的提交，每次提交带着它的事件和之后的状态：默认每页 100 次，最多 1,000 次。

## 查询快照 {#query-snapshots}

`<Aggregate>SnapshotQueryClient` 在 `POST /<context>/<aggregate>/snapshot/query` 上查询聚合的快照。快照记录把状态放在 `state` 下，旁边是系统字段（`aggregateId`、`version`、`ownerId`、`tags`、`lastEventTime` 等），所以过滤要指明 `state.status`，而不是 `status`：

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { desc, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';

const page = await orderQueries.paged(
  pagedQuery({
    filter: filter.and([
      filter.isIn('state.status', ['PAID', 'SHIPPED']),
      filter.afterNow('lastEventTime', '-P7D'),
    ]),
    sort: [desc('lastEventTime')],
    pagination: { index: 1, size: 20 },
  }),
);
console.log(page.total, page.list.map(record => record.state.status));
```

字段名的类型来自生成的 `<Aggregate>StateField` 和系统字段，所以拼错的字段无法编译。客户端的每个方法运行一种查询：

| 方法 | 返回 |
| --- | --- |
| `single` | 唯一匹配的那条记录；没有匹配时为 `undefined`（服务端的 404 `wow.query.not-found`）；其他 404 仍会 reject |
| `list` | 至多 `limit` 条记录；没有时取服务的默认上限 |
| `paged` | 一页，`{ total, exact, list }` |
| `cursor` | 一页，以及下一页的 `nextCursor`，最后一页之后为 `null` |
| `count` | `{ count, exact }` |
| `aggregate` | 聚合的行：分组和指标 |
| `stream` | 以服务端推送事件逐条返回记录 |
| `describe` | 查询模型的描述符：字段、每个字段接受的操作符、排序和聚合，以及上限 |

每个操作符、每种请求及其回答的定义，见[查询语言](../reference/query-language)。

### 投影收窄结果 {#projections-narrow-the-result}

`projection.include` 列出字面路径的查询，返回只带这些路径的记录，结果的类型也会这样说明：

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const statuses = await orderQueries.list({
  filter: filter.eq('state.status', 'PAID'),
  projection: { include: ['aggregateId', 'state.totalAmount'] },
  limit: 50,
});
for (const record of statuses) console.log(record.aggregateId, record.state.totalAmount);
```

这里 `record.state.status` 无法编译：它没有被包含。收窄要求查询写在调用中，并且路径是字面量。用 `listQuery` 或 `pagedQuery` 构建的查询，或只有 `exclude` 的查询，在类型上返回完整的记录。

### 相对时间与时区 {#relative-time-and-time-zones}

`filter.beforeNow` 和 `filter.afterNow` 把字段与服务器时钟加上一个偏移量比较，从不使用浏览器的时钟。日历过滤（`today`、`thisWeek`、`lastMonth` 等）在某个时区中读取它们的日期：你传入的 `zoneId`，否则是 UTC。要按用户自己的日期，就传入用户的时区：

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const zoneId = Intl.DateTimeFormat().resolvedOptions().timeZone;
const placedToday = filter.today('firstEventTime', { zoneId });
```

### 长列表 {#long-lists}

`stream` 以服务端推送事件读取列表，用于长到无法在一次响应中返回的列表。当服务为了让它续读而结束流、连接被切断，或者以可重试的 problem 失败时，客户端会从它读到的最后一条记录之后续读（`Last-Event-ID`），所以读者对每条记录只看到一次：

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';

const stream = await orderQueries.stream(
  listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 10_000 }),
);
const reader = stream.getReader();
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  console.log(value.aggregateId);
}
```

客户端无法续读的失败，会以它的 `WowProblemError` 让流出错。

## 查询事件流 {#query-event-streams}

`<Aggregate>EventQueryClient` 对聚合的事件流运行同样的查询，每次提交一条记录，它的事件在 `events` 中，类型是生成的 `<Aggregate>Event` 联合类型：

<!-- typecheck-context
import { OrderEventQueryClient } from './generated/ordering/index.js';
declare const orderEvents: OrderEventQueryClient;
-->

```ts
import { asc, filter, listQuery } from '@ahoo-wang/wow-client/dsl';

const commits = await orderEvents.list(
  listQuery({
    filter: filter.eq('aggregateId', 'order-1'),
    sort: [asc('version')],
  }),
);
for (const commit of commits)
  for (const event of commit.events) console.log(commit.version, event.name);
```

较旧的事件按它当前的修订号回答，以服务升级它的方式升级（[查询事件流](../guide/event-queries)）。

## 查询数据集 {#query-datasets}

数据集是服务为分析而发布的表（[从服务发布数据集](../guide/datasets)）。数据集在运行时注册，所以不在 Manifest 中，也没有生成的客户端：`DatasetClient` 列出调用方能看到的数据集，并按名字查询其中一个：

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { DatasetClient } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';

const datasets = new DatasetClient({
  fetcher: new Fetcher({ baseURL: 'https://ordering.example.com' }),
});
const sales = datasets.dataset<{ status: string; totalAmount: number }>(
  'eshop-ordering',
  'sales',
);
const rows = await sales.list({ filter: filter.matchAll(), limit: 100 });
console.log(rows.length);
```

数据集查询在 10.0 中是实验性的：它的 API 仍可能改变。

## 查询失败时 {#when-a-query-fails}

被拒绝的查询以 `WowProblemError` reject。400 `wow.query.invalid` 为它违反的每条规则列出一个 violation：它的 `code` 是 `QueryErrorCodes`（来自 `/dsl`）之一，以及它的 `path` 和一个 `message`。这份列表只会增加，所以对你不处理的错误码，退回到 problem 的 `detail`。429 `wow.query.busy` 表示存储正在运行它所允许的最多查询：`error.retryAfterMs` 说明何时再试。每个方法的最后一个参数都是请求选项，其中的 `signal` 用于取消查询。

React 组件通过 `@ahoo-wang/wow-react` 的 hooks 运行这些查询，hooks 保存查询、它的结果和加载状态。

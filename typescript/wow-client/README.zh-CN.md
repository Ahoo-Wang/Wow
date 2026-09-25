# `@ahoo-wang/wow-client`

面向 Wow 命令、快照、领域事件、过滤、分页与聚合的类型化 Fetcher 客户端和契约。只在
对接 Wow HTTP 端点时使用。

支持的服务端：Wow 8.11 及以后通过 `filter` API；Wow 8.10 通过
[`/legacy`](#wow-810-服务端ahoo-wangwow-clientlegacy)。CI 用同版本的服务端测试
客户端，并对 Wow 8.11.5、9.1.3、9.1.5 做运行时冒烟测试；对 8.10.8 只检查生成代码的类型。
Node `>=22.12.0` 或现代浏览器。TypeScript 6 或更高版本：CI 测试 6.0 到最新的 7.x。详见
[兼容性矩阵](https://wow.ahoo.me/zh/guide/typescript/compatibility)。

随 Wow 9.2.0 发布，与 Wow 同一个 tag、同一个版本号。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/guide/typescript/compatibility#版本范围)。

Peer 依赖：`fetcher`、`fetcher-decorator` 和 `fetcher-eventstream`。

## 查询

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/wow-client';

interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
}

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const snapshots = new SnapshotQueryClient<CartState>({
  fetcher,
  basePath: 'cart',
});

const carts = await snapshots.listState(
  listQuery({
    filter: filter.and([
      filter.ownerId('u-42'),
      filter.eq('state.status', 'ACTIVE'),
    ]),
    limit: 50,
  }),
);
```

`listQuery()`、`pagedQuery()`、`singleQuery()` 不传过滤条件时匹配全部。`listQuery()`
只在给出 `limit` 时才发送它；不带 `limit` 时服务端怎么做，取决于它的版本：

- Wow 9.1.5 及以后：用服务端的默认列表大小（未另行配置时为 100）。
- Wow 8.11～9.1.3：以 `IllegalArgument` 拒绝，信息里有
  `limit[0] must be between 1 and 1000`；列表是 HTTP 400，列表流以这个错误事件结束。对这些服务端请显式传 `limit`；
  这次拒绝得到的 `WowError` 也会这样提示。

相对时间由服务端求值，不用浏览器的时钟。`filter.beforeNow` 与 `filter.afterNow`
把时间字段与服务端的「现在 + ISO-8601 `offset`」做严格比较（默认 `PT0S`，负值表示回看），
每个查询只取一次时钟，所以保存的视图在每个客户端上含义相同：

```ts
import { filter } from '@ahoo-wang/wow-client';

const overdue = filter.beforeNow('state.timeoutAt');
const lastHalfHour = filter.afterNow('state.createTime', '-PT30M');
```

`BEFORE_NOW` 与 `AFTER_NOW` 需要 Wow 9.2.0 及以上；更早的服务端会拒绝该查询。

## 发送命令

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import {
  CommandClient,
  CommandStage,
  commandHeaders,
  waitStrategy,
} from '@ahoo-wang/wow-client';

const commands = new CommandClient({ fetcher, basePath: 'cart' });
const result = await commands.send({
  path: 'add_cart_item',
  method: 'POST',
  headers: {
    ...commandHeaders({ ownerId: 'u-42', requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
  },
  body: { productId: 'p-1', quantity: 1 },
});
```

`CommandRequestHeaders` 按服务端的解析方式给每个命令头定类型：等待阶段必须是
`CommandStage`，超时是整数毫秒。`waitStrategy()` 也能构建 Wow 的等待链
（`SAGA_HANDLED` 加 `tail`）。`sendAndWaitStream()` 在命令每到达一个阶段时产出一个
`CommandResult`。

## 错误

服务端返回的错误是 `WowError`，带着服务端的 `ErrorInfo`（`errorCode`、`errorMsg`、
`bindingErrors`）和 HTTP 状态码。用 `ErrorCodes` 比较 `errorCode`。Wow 以 HTTP 错误
状态应答被拒绝的请求，包括命令处理函数失败的命令，所以调用会拒绝（reject）。

```ts
import {
  ErrorCodes,
  toWowError,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

async function findCart(snapshots: SnapshotQueryClient<unknown>, id: string) {
  try {
    return await snapshots.getStateById(id);
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
    throw wowError ?? error;
  }
}
```

- 请求失败时抛出的是 fetcher 的错误。`toWowError()` 读取响应体里的 `ErrorInfo`，
  读不到时读 `Wow-Error-Code` 响应头；不是 Wow 服务端的应答（网络故障、超时、取消、
  代理自己的错误页）时返回 `undefined`。
- 流（`listStream`、`aggregateStream`、`sendAndWaitStream` 等）中途失败时，流以
  `WowError` 结束，`for await` 会抛出它。服务端此时的 HTTP 状态仍是 200，错误作为
  最后一个事件发出；不这样处理的话，错误会被当成一行数据。

## 取消

每个查询方法的最后一个参数（在拦截器属性之后）接受 `AbortController` 或
`AbortSignal`：

<!-- typecheck-context
import type { FilterPagedQuery, SnapshotQueryClient } from '@ahoo-wang/wow-client';
declare const snapshots: SnapshotQueryClient<unknown>;
declare const query: FilterPagedQuery;
-->

```ts
const page = await snapshots.pagedState(
  query,
  undefined,
  AbortSignal.timeout(5_000),
);
```

## 不带 HTTP 的查询构建：`@ahoo-wang/wow-client/dsl`

`filter`、`aggregation`、排序、投影、分页、游标查询和查询工厂，不带客户端：不加载
Fetcher、装饰器、`reflect-metadata`，也不装 `fetcher-eventstream` 的全局流补丁。
只构建查询的包从这里导入。

## Wow 8.10 服务端：`@ahoo-wang/wow-client/legacy`

根入口只使用 `FilterExpression`，Wow 8.11 及以后的服务端都支持。Wow 8.10 只认
已弃用的 Condition 模型，本包把它放在单独的子路径里，保留到 v10：

<!-- typecheck-context
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
declare const snapshots: SnapshotQueryClient<unknown>;
-->

```ts
import { and, eq, listQuery, ownerId } from '@ahoo-wang/wow-client/legacy';

const carts = await snapshots.listState(
  listQuery({ condition: and(ownerId('u-42'), eq('state.status', 'ACTIVE')) }),
);
```

查询客户端两种查询都接受，其余一切都从根入口导入。这个子路径在 v10 删除。

## 核心能力

- 命令结果与流式等待阶段。
- 快照、领域事件、状态加载与所有者状态客户端；按版本区间加载事件流
  （`EventStreamQueryClient.load`）；服务端元数据（`WowMetadataClient`）。
- 提前校验的数组优先 `FilterExpression` 构建器。
- 单条、列表、分页、游标、计数与流查询契约。
- 投影、排序、嵌套聚合、建模、ABAC 与元数据类型。
- `aggregation.query()` 按 Wow 服务端同样的规则校验整个聚合查询，
  让错误在构建处暴露，而不是等到请求被拒。

暂时没有客户端的：`GET {id}/snapshot`、`GET {id}/state/tracing`，以及
`/wow/command/send` 路由（用 `CommandClient` 加上 `COMMAND_AGGREGATE_CONTEXT`、
`COMMAND_AGGREGATE_NAME`、`COMMAND_TYPE` 三个头发送）。

## 文档

- [快速开始：调用 Wow 服务](https://wow.ahoo.me/zh/guide/typescript/quick-start)
- [错误处理](https://wow.ahoo.me/zh/guide/typescript/error-handling)与
  [认证](https://wow.ahoo.me/zh/guide/typescript/authentication)
- [TypeScript 指南](https://wow.ahoo.me/zh/guide/typescript/)
- [wow-client 参考](https://wow.ahoo.me/zh/reference/typescript/wow-client/)
- [交互式查询 Story](https://wow.ahoo.me/storybook/)

[English](./README.md) · [许可证](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

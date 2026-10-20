# `@ahoo-wang/wow-client`

Wow 服务路由的 TypeScript 客户端，构建在 Fetcher 之上：命令及其等待、状态读取、快照
查询、事件查询与数据集查询、Manifest，以及服务应答的 RFC 9457 问题（problem）。查询
DSL（`filter`、`aggregation`、排序、投影、分页）在单独的入口
`@ahoo-wang/wow-client/dsl`。

Node `>=22.12.0` 或现代浏览器。TypeScript 6 或更高版本：CI 测试 6.0 到最新的 7.x。
随 Wow 发布，与 Wow 同一个 tag、同一个版本号。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/guide/typescript/compatibility#版本范围)。

Peer 依赖：`@ahoo-wang/fetcher` 和 `@ahoo-wang/fetcher-eventstream`。

多数应用用 `wow-generator` 按服务的 Manifest 生成的客户端，它们建在下面这些类之上；
每个类也能手工调用任意路由。

## 查询

聚合的快照在 `/{context}/{aggregate}/snapshot/query` 上查询，请求体用 `kind` 说明
操作，由客户端填写。

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';

interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
}

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const carts = new SnapshotQueryClient<CartState>(
  { context: 'shop', name: 'cart' },
  { fetcher },
);

const active = await carts.list(
  listQuery({ filter: filter.eq('state.status', 'ACTIVE'), limit: 50 }),
);
```

`single`、`list`、`paged`、`cursor`、`count`、`aggregate` 各对应一种操作；
`listStream` 以服务端推送事件读列表，服务中断的流从中断处续读。`describe(version?)`
读查询模型的能力描述（同一路由的 `GET`）：每个字段可用的运算符、排序与聚合，以及
各项上限；传入已持有的版本，304 时返回 `{ notModified: true, version }`。
`EventQueryClient` 以同样方式查询聚合的事件流，`QueryModelClient` 查询任意查询路由，
`DatasetClient` 列出服务的数据集（`GET /wow/dataset`）。

每个方法最后一个参数是 `RequestOptions`：要在其中操作的 `scope` 和 `space`、附加的
`headers`，以及取消请求的 `signal`。

相对时间由服务端解析，从不用浏览器的时钟：

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const overdue = filter.beforeNow('state.timeoutAt');
const lastHalfHour = filter.afterNow('state.createTime', '-PT30M');
```

## 发送命令

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import { CommandGateway, wait } from '@ahoo-wang/wow-client';

const commands = new CommandGateway({ fetcher });
const result = await commands.send(
  '/shop/cart/{id}/add-cart-item',
  { productId: 'p-1', quantity: 1 },
  { id: 'cart-42', wait: { plan: [wait.snapshot()], timeout: 10_000 } },
);
```

路由是 `POST /{context}/{aggregate}/{id}/{command}`；可以创建聚合的命令不带 `{id}`。
`wait` 说明命令走到哪一步才应答（`accepted`、`committed`、`snapshot`，或某个订阅的
`handled`，也可以跨过 Saga 派生的命令）；`stream` 每到一跳应答一个 `CommandResult`。
每条命令都带请求 id（`Wow-Request-Id`），传入的或生成的：用同一个 id 重试，应答的是
第一次提交的重复结果；`requestIdOf(error)` 取回失败请求所用的 id。`lifecycle` 发送
聚合列出的生命周期命令（删除、恢复、转移、标签）。

`StateClient` 按版本或时间读取聚合的状态及其轨迹；`fetchManifest` 读取服务的
Manifest（`GET /wow/manifest`）。

## 错误

服务以 RFC 9457 问题拒绝请求，调用以 `WowProblemError` 拒绝：原样的 `problem`、
`status`、Wow 的 `code` 和 `violations`。未成功的命令还带着它的 `CommandResult`，
`resolveRejected: true` 时改为返回该结果。中途失败的流以同一个错误结束，`for await`
会抛出它。

```ts
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';

declare const error: unknown;
if (
  error instanceof WowProblemError &&
  error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND
) {
  // …
}
```

被拒绝的查询在每条 violation 里说明违反的规则：`code` 是 `QueryErrorCodes`（来自
`/dsl`）之一，`path` 是 JSON 路径或字段，还有 `message`。代码只增不减，没处理的代码
回退到问题的 `detail`。错误的 `retry` 说明请求能否重试、何时重试、是否沿用同一个
请求 id。

## 不带 HTTP 构建查询：`@ahoo-wang/wow-client/dsl`

`filter`、`aggregation`、排序、投影、分页、游标查询、查询工厂、能力描述的类型和
`QueryErrorCodes`，不含客户端：没有 Fetcher，也没有 `fetcher-eventstream` 安装的流
补丁。只构建查询的包用它。`aggregation.query()` 按 Wow 收到聚合时执行的规则校验整个
聚合，错误的查询在构建处就失败。

## 文档

- [TypeScript 指南](https://wow.ahoo.me/zh/guide/typescript/)
- [wow-client 参考](https://wow.ahoo.me/zh/reference/typescript/wow-client/)
- [交互式查询示例](https://wow.ahoo.me/storybook/)

[English](./README.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)

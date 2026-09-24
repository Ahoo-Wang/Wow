# `@ahoo-wang/wow-client`

面向 Wow 命令、快照、领域事件、过滤、分页与聚合的类型化 Fetcher 客户端和契约。只在
对接 Wow HTTP 端点时使用。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
```

Peer 依赖：`fetcher`、`fetcher-decorator` 和 `fetcher-eventstream`。

## 示例

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

## Wow 8.10 服务端：`@ahoo-wang/wow-client/legacy`

根入口只使用 `FilterExpression`，Wow 8.11 及以后的服务端都支持。Wow 8.10 及更早
的服务端只认已弃用的 Condition 模型，本包把它放在单独的子路径里，保留到 v10：

```ts
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { and, eq, listQuery, ownerId } from '@ahoo-wang/wow-client/legacy';

const carts = await snapshots.listState(
  listQuery({ condition: and(ownerId('u-42'), eq('state.status', 'ACTIVE')) }),
);
```

查询客户端两种查询都接受，其余一切都从根入口导入。这个子路径在 v10 删除。

## 核心能力

- 命令结果与流式等待阶段。
- 快照、领域事件、状态加载与所有者状态客户端。
- 提前校验的数组优先 `FilterExpression` 构建器。
- 单条、列表、分页、游标、计数与流查询契约。
- 投影、排序、嵌套聚合、建模、ABAC 与元数据类型。
- `aggregation.query()` 按 Wow 服务端同样的规则校验整个聚合查询，
  让错误在构建处暴露，而不是等到请求被拒。

## 文档

- [Wow CQRS 实战](https://fetcher.ahoo.me/zh/guides/integrations/wow)
- [Wow 参考](https://fetcher.ahoo.me/zh/reference/wow)
- [交互式查询 Story](https://fetcher.ahoo.me/storybook/)

[English](./README.md) · [许可证](../../LICENSE)

# `@ahoo-wang/fetcher-wow`

面向 Wow 命令、快照、领域事件、过滤、分页与聚合的类型化 Fetcher 客户端和契约。只在
对接 Wow HTTP 端点时使用。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-wow
```

Peer 依赖：`fetcher`、`fetcher-decorator` 和 `fetcher-eventstream`。

## 示例

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/fetcher-wow';

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

## 核心能力

- 命令结果与流式等待阶段。
- 快照、领域事件、状态加载与所有者状态客户端。
- 提前校验的数组优先 `FilterExpression` 构建器。
- 单条、列表、分页、游标、计数与流查询契约。
- 投影、排序、嵌套聚合、建模、ABAC 与元数据类型。

## 文档

- [Wow CQRS 实战](https://fetcher.ahoo.me/zh/recipes/wow-cqrs)
- [Wow 参考](https://fetcher.ahoo.me/zh/reference/wow)
- [交互式查询 Story](https://fetcher.ahoo.me/storybook/)

[English](./README.md) · [许可证](../../LICENSE)

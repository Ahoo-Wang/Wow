---
title: 发送 Wow 命令并读取状态
description: 配置应用聚合路由、检查命令结果，并查询或流式读取快照状态。
---

# 发送 Wow 命令并读取状态

适用于实现 Wow 命令和快照查询的服务端。命令完成和查询结果是独立契约，应用应分别处理。

安装时还需要解析该包声明的全部 peer 依赖；完整清单见[包接入前提](../../reference/typescript/wow-client/)。以下服务路由和身份由应用提供，不由安装过程创建。

## 1. 确认聚合端点

安装 `@ahoo-wang/fetcher` 和 `@ahoo-wang/wow-client`。本例假定应用在 `owner/{ownerId}/cart` 提供所有者范围购物车、`add_cart_item` POST 命令，以及该路径下的 Wow 快照端点。请从服务端 OpenAPI 确认路径，并独立配置认证。路由名称和状态模型都是应用示例。

## 2. 创建命令与查询客户端

```ts
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import {
  CommandClient,
  CommandHeaders,
  CommandStage,
  SnapshotQueryClient,
  filter,
  pagedQuery,
  listQuery,
} from '@ahoo-wang/wow-client';

interface AddCartItem {
  productId: string;
  quantity: number;
}
interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
  items: Array<{ productId: string; quantity: number }>;
}

export function createCartClients(baseURL: string, ownerId: string) {
  const metadata = {
    fetcher: new Fetcher({ baseURL }),
    basePath: 'owner/{ownerId}/cart',
    urlParams: { path: { ownerId } },
  };
  const commands = new CommandClient<AddCartItem>(metadata);
  const snapshots = new SnapshotQueryClient<CartState>(metadata);
  const active = filter.and([
    filter.ownerId(ownerId),
    filter.eq('state.status', 'ACTIVE'),
  ]);
  return {
    addItem: (body: AddCartItem) =>
      commands.send({
        path: 'add_cart_item',
        method: HttpMethod.POST,
        body,
        headers: { [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT },
      }),
    loadPage: (controller: AbortController) =>
      snapshots.pagedState(
        pagedQuery({ filter: active, pagination: { index: 1, size: 20 } }),
        undefined,
        controller,
      ),
    streamStates: (controller: AbortController) =>
      snapshots.listStateStream(
        listQuery({ filter: active, limit: 50 }),
        undefined,
        controller,
      ),
  };
}
```

`createCartClients(apiOrigin, ownerId)` 返回添加条目、读取第一页、流式读取最多 50 个状态的函数。pagedState 返回包含 list、total 的 `PagedList<CartState>`；state 方法解开快照外壳，但过滤字段仍针对存储的快照，因此使用 `state.status`。

## 3. 发送一次并检查结果

等待 `clients.addItem({ productId: 'book-1', quantity: 2 })`，按服务端命令契约检查返回的 stage、errorCode 和 errorMsg。HTTP 成功本身不等于业务成功。`WAIT_STAGE: SNAPSHOT` 请求等待该阶段，不保证所有投影已经可查询，也不会消除失败。重试结果不确定的命令时，遵循服务端的 request-ID 策略。

决定如何处理命令结果后，再通过 `clients.loadPage(controller)` 加载页面。区分传输失败和返回的命令失败。每个独立所有者的查询使用新 AbortController，并在 UI/任务结束时调用 abort。

## 4. 消费有界查询流

以下独立示例假定查询路径是无所有者范围的 cart，请改成实际聚合路径：

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/wow-client';

export async function printActiveStates(
  baseURL: string,
  controller: AbortController,
) {
  const snapshots = new SnapshotQueryClient<{ status: string }>({
    fetcher: new Fetcher({ baseURL }),
    basePath: 'cart',
  });
  const stream = await snapshots.listStateStream(
    listQuery({ filter: filter.eq('state.status', 'ACTIVE'), limit: 50 }),
    undefined,
    controller,
  );
  const reader = stream.getReader();
  let finished = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        finished = true;
        break;
      }
      console.log(value.data.status);
    }
  } finally {
    try {
      if (!finished) await reader.cancel();
    } finally {
      reader.releaseLock();
    }
  }
}
```

方法第三个参数为 AbortController，第二个参数为可选 attributes。取消传入的控制器可以停止等待中的请求或活动流。发起请求和后续读取都可能拒绝，因此应由所有者捕获函数返回的 Promise。渲染或处理某行时抛错也会执行 reader 清理。

## 5. 验证边界

模拟 fetch，断言命令路径/请求体/WAIT_STAGE、查询 JSON、从 1 开始的分页，以及 SSE 请求的 Accept。分别返回符合契约的命令结果夹具和 `{list: [], total: 0}` 页面夹具。不要从 mock 推断一致性；在真实服务集成测试中确认命令阶段和快照可见性。

数组优先的过滤构造器要求单个非空数组，空数组在执行前抛错。需要无过滤查询时显式使用 `filter.matchAll()`。大结果集可以选择[游标查询](../../reference/typescript/wow-client/cursor-queries)，服务端计算可以使用[聚合](../../reference/typescript/wow-client/aggregations)，读取第一页不需要它们。

参见[命令](../../reference/typescript/wow-client/commands)、[快照查询](../../reference/typescript/wow-client/snapshot-queries)、[过滤器](../../reference/typescript/wow-client/filters)及[分页、投影和排序](../../reference/typescript/wow-client/query-options)。

[snapshotQueryClient.ts:326](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts#L326) 定义流方法参数。

[评估集成边界](https://fetcher.ahoo.me/zh/architecture/integration-decisions)；[返回本组任务](./index.md)。

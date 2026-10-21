# `@ahoo-wang/wow-view-store`

[English](./README.md)

视图引擎的 `ViewStore` 落在 Wow 视图存储服务上：[`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.zh-CN.md)
保存的视图与偏好，由视图存储服务（Wow 仓库中的 `platform/view-store`）保管——两个 Wow 聚合，经由从服务
Manifest 生成的客户端访问；另有视图引擎在 Wow 服务查询路由上的数据源。

随 Wow 发布，与 Wow 同一个 tag、同一个版本号，与视图引擎一起发布。

> **兼容性。** 补丁版本不破坏公开面（入口的导出与错误码）；次版本可以破坏，它的发布说明
> 逐条列出每个破坏与迁移步骤。Wow 包请停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/guide/typescript/compatibility#版本范围)。

## 使用

peer 依赖：`@ahoo-wang/fetcher`、`@ahoo-wang/wow-client` 与 `@ahoo-wang/wow-view-engine`，以及它们声明的
peer，由宿主一并安装：`@ahoo-wang/fetcher-eventstream`（wow-client 的），以及视图引擎自己的入口需要时的可选
`react`、`react-dom`、`react-router` 与 `mingo`。Node `>=22.12.0` 或当前的浏览器；TypeScript 6 或更高。

`WowViewStore` 接收一个 fetcher，以及可选的、决定按钮的权限。它不接收租户、用户或应用：服务从主体
（principal）取得它们，fetcher 的拦截器像宿主的其他 Wow 请求一样带上它们。`viewSourceOf` 把一个定义放到
查询路由上，`datasetCatalogOf` 放上服务的数据集，`WowDatasetWordings` 保存租户对它们的措辞。

<!-- typecheck-context
import type { ViewDefinition, ViewPermissions } from '@ahoo-wang/wow-view-engine';
declare const orders: ViewDefinition;
declare const permissionsFromRoles: (definitionId: string) => ViewPermissions;
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { DatasetClient, SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { DatasetResources, ViewEngine } from '@ahoo-wang/wow-view-engine';
import {
  WowDatasetWordings,
  WowViewStore,
  datasetCatalogOf,
  viewSourceOf,
} from '@ahoo-wang/wow-view-store';

// fetcher 的拦截器带上授权与 `CoSec-App-Id`；路径里不出现租户与 owner。
const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });

const engine = new ViewEngine({
  store: new WowViewStore({ fetcher, permissions: permissionsFromRoles }),
  resources: [
    {
      definition: orders,
      // 任何查询路由都可以：生成的 `…SnapshotQueryClient` 同样适用。
      source: viewSourceOf(
        new SnapshotQueryClient(
          { context: 'sales', name: 'order' },
          { fetcher },
        ),
      ),
    },
  ],
});

// 服务的数据集（`GET /wow/dataset`），按租户的措辞注册。
const datasets = new DatasetResources(engine, {
  ...datasetCatalogOf(new DatasetClient({ fetcher })),
  wordings: new WowDatasetWordings({ fetcher }),
});
await datasets.refresh();
```

`permissions` 只决定按钮是否可用，从不决定写入能做什么：每次写入由服务授权。`createShared` 与
`changeAudience` 按能写保留 owner `(shared)` 的角色给出（出厂为 `view-store.shared-writer`）——新建共享视图、
认领共享视图和共享个人视图都需要它。不给时一切都允许——但引擎的 `editSystem`（发布、编辑与撤下存储的系统视图）
除非宿主明确答 `true`，否则关闭：按能写 `(platform)` 下 `(system)` 的角色给出。

`WowDatasetWordings` 保存租户对数据集的措辞（视图引擎的数据集覆盖：标题、记录名词、字段标签、顺序与收窄），
每个数据集、每个应用一份。`DatasetResources` 每次刷新前读取它，按存储的措辞注册每个数据集，并在宿主授予
`editDatasetWording` 时给视图管理器加上措辞页。`set(dataset, wording, revision, { requestId })` 在期望的
修订号上整体写入一份（从未写过的为 `'0'`），`clear` 删除它。

### 存储怎样与服务交互

- **谁在请求**来自主体；共享视图以保留 owner `(shared)` 写入，存储的系统视图以 `(platform)` 下的 `(system)`
  写入，由存储自己用 `Wow-Scope` 说明。
- **写入**以 `Wow-Request-Id` 带上端口的请求 id，以 `If-Match` 带上修订号；服务按请求 id 对每次写入去重，
  创建也一样，所以任何标签页的重试都得到第一次的结果。把视图设为共享或个人时，会等服务把它移到新 owner 的 saga。
- **错误**按 problem 的 `code` 读取：`ViewStoreError` 以 `detail.code` 带着它（`WowViewStoreErrorCodes`
  列出视图存储自己的码），服务不提供的路由（没有码的 `404`）是 `UNSUPPORTED`。
- **一页的总数**是服务默认的 `BOUNDED` 计数：服务经 HTTP 拒绝精确总数。

## 文档

| 想做的事                                                          | 读                                                                                                       |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| 选择视图放在哪里、接入 `WowViewStore`、给出权限、服务不登录的宿主 | [视图存放在哪里](https://wow.ahoo.me/zh/typescript/view-engine/storage#wowviewstore-视图存在-wow-服务上) |
| 导出、合同、端口发出的每个请求，以及服务端错误怎样映射到端口      | [wow-view-store 参考](https://wow.ahoo.me/zh/reference/typescript/wow-view-store/)                       |

## 测试

- `pnpm --filter @ahoo-wang/wow-view-store test`——对着内存中的服务的单元测试、公开面与 API 报告。
- 端口的一致性测试套件对着视图存储服务跑 `WowViewStore`，另有租户与应用的隔离测试；
  `pnpm --dir typescript/integration-test test` 把服务起在绑定 0 的端口上（`test/view-store/`）。

以 Apache License 2.0 许可。

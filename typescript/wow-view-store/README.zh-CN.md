# `@ahoo-wang/wow-view-store`

[English](./README.md)

视图引擎的 `ViewStore` 落在 Wow 服务端上：[`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.zh-CN.md)
保存的视图与偏好，由[视图存储](../../view-store/README.md)保管——两个 Wow 聚合，由独立的
`wow-view-store-server` 提供，或由任何引入了 `wow-view-store-starter` 的 Wow 服务提供。

随 Wow 发布，与 Wow 同一个 tag、同一个版本号，与视图引擎和视图存储的服务端模块一起发布。

> **兼容性。** 从 9.2.0 起，补丁版本不破坏公开面（入口的导出与错误码）；次版本可以破坏，它的发布说明
> 逐条列出每个破坏与迁移步骤。Wow 包请停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/guide/typescript/compatibility#版本范围)。

## 使用

对等依赖：`@ahoo-wang/fetcher`、`@ahoo-wang/wow-client` 与 `@ahoo-wang/wow-view-engine`，以及它们自己声明、
宿主要一并安装的对等依赖：wow-client 的 `@ahoo-wang/fetcher-decorator` 与 `@ahoo-wang/fetcher-eventstream`，
视图引擎的可选依赖 `react`、`react-dom`、`react-router` 与 `mingo`（用到它对应的入口时）。Node `>=22.12.0`
或当前的浏览器；TypeScript 6 及以上。

`WowViewStore` 接收一个 fetcher，以及可选的、决定按钮是否可用的权限。它不收租户、用户或应用：
是谁在请求由 fetcher 的拦截器带上，与宿主的其他 Wow 请求一样。

<!-- typecheck-context
import type { QueryApi } from '@ahoo-wang/wow-client';
import type { ViewDefinition, ViewPermissions } from '@ahoo-wang/wow-view-engine';
declare const orders: ViewDefinition;
declare const ordersSource: Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>;
declare const tokenStorage: TokenStorage;
declare const permissionsFromRoles: (definitionId: string) => ViewPermissions;
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  ResourceAttributionRequestInterceptor,
  type TokenStorage,
} from '@ahoo-wang/fetcher-cosec';
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';

// 视图存储前面的网关。宿主里这个 fetcher 已经带着 CoSec 的拦截器：认证、
// `CoSec-App-Id`（CoSecRequestInterceptor），以及路径里的 {tenantId} 与 {ownerId}
// （ResourceAttributionRequestInterceptor，取自令牌）。
const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
fetcher.interceptors.request.use(
  new ResourceAttributionRequestInterceptor({ tokenStorage }),
);

const engine = new ViewEngine({
  store: new WowViewStore({ fetcher, permissions: permissionsFromRoles }),
  resources: [{ definition: orders, source: ordersSource }],
});
```

`permissions` 只决定哪些按钮可用，从不决定一次写入能不能做：服务端信任路径，谁能用哪条路径由 CoSec
网关决定。`createShared` 与 `changeAudience` 按能写 `owner/(shared)` 的角色给——新建共享视图、收为个人、设为共享都需要它。不给则全部允许——
但引擎的 `editSystem`（发布、编辑、取消发布存储的系统视图）只在宿主明确答 `true` 时打开：按网关放行
`tenant/(platform)/owner/(system)` 的角色给（`view-store/README.md`「System views」）。

### 数据集措辞

`WowDatasetWordings` 在 `owner/(shared)` 下存租户给数据集写的措辞（视图引擎的数据集覆盖：标题、名词、字段的
口径、次序与收窄），每个应用、每个数据集一份，按数据集的定义 id 存。把它交给数据集作措辞存储：`DatasetResources`
每次刷新前读它，按存着的措辞登记每个数据集，并在宿主给了 `editDatasetWording` 时给视图管理器一个「措辞」页签：

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  DatasetDirectoryClient,
  DatasetQueryClient,
} from '@ahoo-wang/wow-client';
import { DatasetResources, type ViewEngine } from '@ahoo-wang/wow-view-engine';
import { WowDatasetWordings } from '@ahoo-wang/wow-view-store';

declare const engine: ViewEngine;
declare const fetcher: Fetcher;

const directory = new DatasetDirectoryClient();
const datasets = new DatasetResources(engine, {
  list: () => directory.list(),
  source: entry =>
    new DatasetQueryClient({ context: entry.contextName, dataset: entry.name }),
  wordings: new WowDatasetWordings({ fetcher }),
});
await datasets.refresh();
```

`set(dataset, wording, revision, { requestId })` 按期望版本整份写入（从没写过的是 `'0'`），`clear` 清掉；谁能写由网关
按放行 `…/owner/(shared)/datasets/{dataset}/wording` 的角色决定。

## 文档

| 想做的事                                                          | 读                                                                                                             |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| 选择视图放在哪里、接入 `WowViewStore`、给出权限、服务不登录的宿主 | [视图存放在哪里](https://wow.ahoo.me/zh/guide/typescript/view-engine-storage#wowviewstore-视图存在-wow-服务上) |
| 导出、合同、端口发出的每个请求，以及服务端错误怎样映射到端口      | [wow-view-store 参考](https://wow.ahoo.me/zh/reference/typescript/wow-view-store/)                             |
| 运行视图存储服务端、它的配置与网关规则                            | [视图存储](https://wow.ahoo.me/zh/guide/extensions/view-store)                                                 |

## 测试

- `pnpm --filter @ahoo-wang/wow-view-store test`——对着假服务端的单元测试、公开面与 API 报告。
- 端口的一致性测试套件在 `typescript/integration-test`（`test/view-store/`）里对着视图存储服务端跑
  `WowViewStore`，另有租户与应用的隔离测试。

以 Apache License 2.0 许可。

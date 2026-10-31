---
title: 'wow-view-store 参考'
description: '@ahoo-wang/wow-view-store 包的 WowViewStore：视图引擎的 ViewStore 落在 Wow 视图存储服务上，保留的作用域、错误码，以及它发出的每个请求。'
---

# wow-view-store 参考

::: info Wow 9.2.0 起在 npm 上
`@ahoo-wang/wow-view-store` 从 Wow 9.2.0 起在 npm 上，与 [wow-view-engine](../wow-view-engine/) 一起，同一个 tag、同一个版本号发布。补丁版本不破坏它的公开面（入口的导出与错误码）；次版本可以破坏，它的发布说明逐条列出每个破坏与迁移步骤（[版本范围](../../../typescript/compatibility.md#版本范围)）。以[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) 与[设计文档](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md)为准。
:::

`WowViewStore` 在[视图存储服务](https://github.com/Ahoo-Wang/Wow/tree/main/platform/view-store)上实现视图引擎的 [`ViewStore` 端口](../wow-view-engine/#persistence)：保存的视图与偏好是两个 Wow 聚合，通过根据服务 Manifest 生成的客户端访问。同一个包还为视图引擎提供基于 Wow 服务的数据集目录，以及保存租户对数据集措辞的存储。

## 入口 {#entry}

| 导出 | 作用 |
|---|---|
| `WowViewStore` | store 本身：`new WowViewStore({ fetcher, permissions?, pageSize? })`，交给 `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`：由它的拦截器带上授权信息与 `CoSec-App-Id`；`permissions`：哪些按钮可用；`pageSize`：`list` 一页的视图数 |
| `SHARED_SCOPE`、`SYSTEM_SCOPE` | 保留所有者的 `Wow-Scope`：共享视图与共享偏好是 `{ ownerId: '(shared)' }`，存储的系统视图是 `{ tenantId: '(platform)', ownerId: '(system)' }` |
| `WowViewStoreErrorCodes`、`WowViewStoreErrorCode` | 视图存储自己的 problem 错误码，与 Wow 的并列，以及它们的联合类型 |
| `datasetCatalogOf(client)` | 基于 `DatasetClient` 的视图引擎数据集目录：Wow 服务提供的数据集（`GET /wow/dataset`），以及每个数据集的数据源 |
| `WowDatasetWordings`、`WowDatasetWordingsOptions` | 视图引擎落在视图存储上的 `DatasetWordingStore`：租户对每个数据集的措辞，每个数据集与应用一份；它接收一个 `fetcher` |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
  pageSize?: number;
}
```

### WowViewStore {#api-WowViewStore}

视图引擎的 [`ViewStore`](../wow-view-engine/store#api-ViewStore) 落在 Wow 视图存储上：端口的方法全部实现，包括可选的 `changeAudience`；`permissions` 只在宿主给了 `options.permissions` 时才有。端口只按 id 称呼视图，所以 store 记住它上次在哪里见到每个视图（一次列表、一次读取、一次写入）；没见过的视图用一次按 id 的读取找到，服务按顺序回答调用者自己的这个 id 的视图、共享的视图、系统视图。

- `fetcher` 走的基础 URL 要能提供 `/view-store/…`：视图存储服务前面的网关。它的拦截器携带是谁在请求，即授权信息与 `CoSec-App-Id`；服务从主体中取得租户与所有者，视图属于保留所有者时由 store 自己加上 `Wow-Scope`。没有哪条路径写出租户或所有者。
- `permissions` 决定一份定义的视图哪些按钮可用，从不决定写入能做什么：每次写入都由服务授权。宿主按调用者持有的角色回答：`createShared` 与 `changeAudience` 按能写所有者 `(shared)` 的角色（出厂为 `view-store.shared-writer`），因为新建共享视图、共享个人视图和认领共享视图都需要它；`editSystem` 按能写 `(platform)` 的 `(system)` 的角色（`view-store.system-writer`）。不给时除 `editSystem` 外全部允许，`editSystem` 只有回答为 `true` 才开启。
- `pageSize` 是 `list` 一页最多返回的视图数，默认 100，即服务的默认游标页大小。系统视图在第一页整体返回，所以第一页可能更多。

```ts
export declare class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteOptions): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteOptions): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteOptions): Promise<void>;
  get(id: string, options?: ReadOptions): Promise<ViewInstance>;
  getPreferences(definitionId: string, options?: ReadOptions): Promise<ViewPreferences>;
  list(definitionId: string, options?: ViewListOptions): Promise<ViewListPage>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteOptions): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteOptions): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteOptions): Promise<ViewPreferences>;
}
```

### 保留的作用域 {#api-SHARED_SCOPE}

个人视图属于调用者，所以请求它时不写所有者。共享视图属于保留所有者 `(shared)`，存储的系统视图属于租户 `(platform)` 的 `(system)`，与调用者的租户无关，是全局的。store 通过发送 `Wow-Scope`，即 `SHARED_SCOPE` 或 `SYSTEM_SCOPE`，以这些所有者的身份操作，服务只对持有写它们的角色的调用者放行。所有者 ID 本身属于视图存储的契约，留在包内部；宿主只有在用自己的 Wow 客户端访问这些所有者时，才需要这两个作用域。

```ts
export declare const SHARED_SCOPE: TargetScope;
export declare const SYSTEM_SCOPE: TargetScope;
```

### 错误码 {#api-WowViewStoreErrorCodes}

`WowViewStoreErrorCodes` 是视图存储自己的 problem 错误码，由它的契约生成；`ViewStoreError` 把服务回答的错误码放在 `detail.code`。它们与引擎的 `ViewStoreErrorCode`（端口的错误码）分开命名：

```ts
export declare const WowViewStoreErrorCodes: {
  readonly APP_REQUIRED: 'view-store.app-required';
  readonly INVALID: 'view-store.invalid';
  readonly NOT_FOUND: 'view-store.not-found';
  readonly OWNER_REQUIRED: 'view-store.owner-required';
  readonly REFERENCED_BY_SHARED_DASHBOARD: 'view-store.referenced-by-shared-dashboard';
  readonly SYSTEM_VIEW_READ_ONLY: 'view-store.system-view-read-only';
};
export type WowViewStoreErrorCode = (typeof WowViewStoreErrorCodes)[keyof typeof WowViewStoreErrorCodes];
```

| 错误码 | 状态 | 何时 |
|---|---|---|
| `view-store.app-required` | 400 | 请求的作用域没有应用：在 CoSec 网关之后，即它转发的 `CoSec-App-Id` |
| `view-store.invalid` | 400 | 写入无效：它的配置、标题、偏好或措辞 |
| `view-store.owner-required` | 400 | 写入没有所有者：视图由用户、`(shared)` 或 `(platform)` 下的 `(system)` 保存 |
| `view-store.not-found` | 404 | 聚合属于另一个应用，读起来就像不存在 |
| `view-store.referenced-by-shared-dashboard` | 409 | 认领一个共享看板展示着的视图，它保持共享；problem 在 `boards` 里列出这些看板 |
| `view-store.system-view-read-only` | 403 | 共享或认领系统视图，系统视图从不改变受众 |

## 合同 {#contract}

| 规则 | 行为 |
|---|---|
| 谁在请求 | store 不接收租户、用户或应用。fetcher 的拦截器发送授权信息与 `CoSec-App-Id`，服务从主体中读取租户与所有者；没人登录的宿主用自己的拦截器发送一个调用者（[保存的视图](../../../typescript/view-engine/storage.md#wowviewstore-views-on-a-wow-service)） |
| 受众 | 所有者就是受众：个人视图属于调用者自己，共享视图属于所有者 `(shared)`。「设为共享」以调用者（视图的所有者）身份发送 `share-view`；「设为个人」以 `(shared)` 身份发送 `claim-view`，让调用者成为所有者。两者都原地移动视图、保留 id，并等待服务的 `ViewAudienceSaga` 完成移动 |
| 写入 | `Wow-Request-Id` 是端口的 `requestId`，`If-Match` 是它的 `revision`（从未写过的偏好为 `"0"`）；每次写入都等待快照，回答写入后的版本上读回的视图 |
| 重试 | 服务按请求 ID 对每次写入去重，包括新建，所以来自任何标签页或 store 的重试都以第一次尝试的结果回答。过期的 revision 为 `CONFLICT`，带着视图当前的样子 |
| 错误 | 按 problem 的 `code` 映射到 `CONFLICT`、`NOT_FOUND`、`FORBIDDEN`、`INVALID` 与 `UNAVAILABLE`；只有没回来已知错误码时才看 HTTP 状态；服务不提供该路由时为 `UNSUPPORTED`。错误把错误码保留在 `detail.code` |
| 列表顺序 | 系统视图，然后共享，然后个人，每个受众内最早的在前；一次一页，每页 `pageSize` 个视图，用一个跨受众的游标 |
| 系统视图 | 配置的只读；存储的带 `stored: true`，以 `(platform)` 的 `(system)` 身份新建（以副本发布）、保存、重命名与删除，从不共享或认领。它们的 `revision` 是内容哈希；store 在旁边发送它读到的版本。引擎的 `editSystem` 由宿主给出，不说就关闭 |
| 共享看板 | 共享看板展示着的视图保持共享：认领为 `INVALID`，它的 `boards` 是这些看板存储时的标题，视图引擎用自己的话说出来 |

## 请求 {#requests}

端口的每个方法怎样变成发给视图存储服务的请求。命令经根据它的 Manifest 生成的客户端发送，所以路由就是 Manifest 里的路由。

| 端口 | 请求 |
|---|---|
| `list` | 先 `GET /view-store/system-views?definitionId=` 取系统视图，整体返回；再用 `view` 快照模型的游标查询取共享视图（`Wow-Scope: owner="(shared)"`）与调用者自己的视图，各自按定义过滤，按 `firstEventTime` 再按 id 排序 |
| `get` | `GET /view-store/views/{id}`：调用者自己的这个 id 的视图，否则共享的，否则系统视图 |
| `create` | `POST /view-store/view/create-view`，以视图 `scope` 的所有者身份发送；服务生成 id |
| `save`、`rename` | `POST /view-store/view/{id}/save-view`、`rename-view`，以视图的所有者身份发送 |
| `delete` | `DELETE /view-store/view/{id}`，即生命周期命令，以视图的所有者身份发送 |
| `changeAudience('shared')` | `POST /view-store/view/{id}/share-view`，带 `{}`，以调用者身份发送 |
| `changeAudience('personal')` | `POST /view-store/view/{id}/claim-view`，带 `{}`，以 `(shared)` 身份发送 |
| `getPreferences` | 按 `state.definitionId` 对 `view_preferences` 快照做一次 single 查询；没写过时读作端口的空偏好，revision 为 `'0'` |
| `setPreferences` | `POST /view-store/view_preferences/set-view-preferences`；服务由所有者、应用与定义推导出它们的 id |

视图写入的回答，用 `GET /view-store/view/{id}/state?version=` 在写入后的版本上读回。

**列表一次一页。** 一次 `list` 调用依次读取系统视图、共享视图和调用者的视图，页还有空间时就继续读下一个受众，所以视图不多的定义一页就答完。它的 `nextCursor` 指明正在读的受众与该受众自己的游标；引擎把它当作不透明的字符串传回。

**移动过的视图。** 另一个标签页把 store 记为个人的视图设为了共享（或反过来）时，从旧所有者发出的写入会被拒绝；store 重新查找这个视图，从它现在所在的地方再发一次。移动到视图已有的受众不改变任何东西：revision 对得上时，store 回答视图当前的样子。

**系统视图。** 服务的系统视图有配置的，只读；也有存储的（`source: 'stored'`），`WowViewStore` 在列出和读取时给摘要和实例带上 `stored: true`。`create({ scope: 'system', … })` 发布一个（副本：源视图保留），`save`、`rename` 与 `delete` 编辑和取消发布它；`changeAudience` 以及对配置的或代码中的系统视图的任何写入，在发送之前就是 `FORBIDDEN`。系统视图的 `revision` 是它内容的哈希，这是引擎做脏基线需要的，而写入期望的是聚合版本：store 记住每次读取在 revision 旁边给出的版本，发送那个版本；持有一个没有对应版本的 revision 时，先重新读取视图。revision 不是视图当前的，就是带着视图当前样子的 `CONFLICT`，什么也不发送。

## 错误码映射 {#errors}

每个拒绝都是引擎的 `ViewStoreError`，按 problem 的错误码读取，只有回答里没有 store 认识的错误码时才看 HTTP 状态：

| 服务 | 端口 |
|---|---|
| `wow.aggregate.version-mismatch`、`wow.command.request-id-reused`；没有 problem 的拒绝；没有已知错误码时的 409 与 412 | `CONFLICT` |
| `wow.aggregate.not-found`、`wow.aggregate.deleted`、`wow.query.not-found`、`view-store.not-found`；没有已知错误码时，带错误码的 404 与 410 | `NOT_FOUND` |
| `wow.auth.unauthenticated`、`wow.auth.forbidden`、`wow.aggregate.forbidden`、`view-store.system-view-read-only`；没有已知错误码时的 401 与 403 | `FORBIDDEN` |
| `view-store.invalid`、`view-store.app-required`、`view-store.owner-required`、`view-store.referenced-by-shared-dashboard`、`wow.aggregate.exists`、`wow.command.malformed`、`wow.command.invalid`、`wow.command.unhandled`、`wow.wait.invalid`、`wow.query.malformed`、`wow.query.invalid`；没有已知错误码时的 400 与 422 | `INVALID` |
| 服务说可以重试的其他 problem（它的 `retry` 不是 `none`）；没有已知错误码时的其他状态；完全没有回答 | `UNAVAILABLE` |
| 不带错误码的 `404`：服务不提供的路由 | `UNSUPPORTED` |

每个 `ViewStoreError` 都保留它从哪里读来：请求的失败作为 `cause`，problem 的错误码作为 `detail.code`，所以宿主能分清 `view-store.app-required` 与 `view-store.invalid`，两者都是 `INVALID`。

服务因为有共享看板展示视图而拒绝认领时，错误的 `boards` 带着这些看板存储时的**标题**，视图引擎用自己的话把拒绝说在它们周围：写成键的标题仍是键，在显示拒绝的地方说出来。`message` 保留服务的原话，供日志使用。

## 宿主与网关 {#hosts-and-the-gateway}

宿主怎样接入 `WowViewStore`、给出 `permissions`、服务没人登录的页面，见[保存的视图](../../../typescript/view-engine/storage.md#wowviewstore-views-on-a-wow-service)。服务前面的网关认证每个请求，服务按调用者的角色授权每次写入；部署、角色与路径规则见[视图存储](https://github.com/Ahoo-Wang/Wow/blob/main/deploy/view-store/README.md)页面。

## 源码 {#source}

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) · [视图存储服务](https://github.com/Ahoo-Wang/Wow/tree/main/platform/view-store)

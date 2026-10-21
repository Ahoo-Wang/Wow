---
title: 'wow-view-store 参考'
description: '@ahoo-wang/wow-view-store 包的 WowViewStore：视图引擎的 ViewStore 落在 Wow 视图存储服务端上。'
---

# wow-view-store 参考

::: info Wow 9.2.0 起在 npm 上
`@ahoo-wang/wow-view-store` 从 Wow 9.2.0 起在 npm 上，与 [wow-view-engine](../wow-view-engine/) 一起，同一个 tag、同一个版本号发布。补丁版本不破坏它的公开面（入口的导出与错误码）；次版本可以破坏，它的发布说明逐条列出每个破坏与迁移步骤（[版本范围](../../../guide/typescript/compatibility.md#版本范围)）。以[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) 与[设计文档](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md)为准。
:::

`WowViewStore` 在[视图存储](https://github.com/Ahoo-Wang/Wow/tree/v10/platform/view-store)上实现视图引擎的 [`ViewStore` 端口](../wow-view-engine/#persistence)：保存的视图与偏好是两个 Wow 聚合，由独立的 `wow-view-store-server` 提供，或由引入了 `wow-view-store-starter` 的 Wow 服务提供。

## 入口

| 导出 | 作用 |
|---|---|
| `WowViewStore` | store 本身：`new WowViewStore({ fetcher, permissions? })`，交给 `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`：由它的拦截器带上租户、所有者与应用；`permissions`：哪些按钮可用 |
| `SHARED_OWNER_ID` | `(shared)`，共享视图与共享偏好的所有者段 |
| `SYSTEM_OWNER_ID`、`SYSTEM_TENANT_ID` | `(system)` 与 `(platform)`：存储的系统视图只写在这一条路径上，与调用者的租户无关 |
| `WowViewStoreErrorCodes` | 视图存储自己的错误码，与 Wow 的并列 |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
}
```

### WowViewStore {#api-WowViewStore}

视图引擎的 [`ViewStore`](../wow-view-engine/store#api-ViewStore) 落在 Wow 视图存储上：端口的方法全部实现，包括可选的 `changeAudience`；`permissions` 只在宿主给了 `options.permissions` 时才有。端口只按 id 称呼视图，所以 store 记住它上次在哪里见到每个视图（一次列表、一次读取、一次写入），不认识的 id 依次在个人路径、共享路径与服务端的系统视图里查找。

- `fetcher` 走的基础 URL 要能提供 `/view-store/…`：视图存储服务端前面的 CoSec 网关，或引入了 starter 的服务。它的拦截器携带是谁在请求，store 自己从不携带。
- `permissions` 决定一份定义的视图哪些按钮可用，缺省全部允许，和不带 `permissions` 的存储一样。服务端不做授权，CoSec 网关做；宿主按它在网关上持有的角色回答——`changeAudience` 要能写 `owner/(shared)` 的角色，认领视图需要它。

```ts
export declare class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteContext): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteContext): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  getPreferences(definitionId: string, signal?: AbortSignal): Promise<ViewPreferences>;
  list(definitionId: string, signal?: AbortSignal): Promise<ViewInstanceSummary[]>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteContext): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteContext): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteContext): Promise<ViewPreferences>;
}
```

`WowViewStoreErrorCodes` 是服务端的 `errorCode` 字符串，`ViewStoreError` 把它带在 `detail.code` 上；它与引擎的 `ViewStoreErrorCode`（端口的六个代码）分开命名：

```ts
export declare const WowViewStoreErrorCodes: Readonly<{
  readonly VIEW_INVALID: 'ViewInvalid';
  readonly VIEW_APP_REQUIRED: 'ViewAppRequired';
  readonly SYSTEM_VIEW_READ_ONLY: 'SystemViewReadOnly';
  readonly VIEW_SCOPE_REQUIRED: 'ViewScopeRequired';
  readonly VIEW_EVENT_STREAM_CLOSED: 'ViewEventStreamClosed';
}>;
```

## 合同

| 规则 | 行为 |
|---|---|
| 谁在请求 | store 不收租户、用户或应用。由 fetcher 的拦截器填路径里的 `{tenantId}` 与个人路径的 `{ownerId}`（fetcher-cosec 的 `ResourceAttributionRequestInterceptor`，取自令牌），并发送 `CoSec-App-Id`；不登录的宿主用自己的拦截器填缺省值 |
| 受众 | 路径的所有者段：个人视图在调用者的路径上，共享视图在 `owner/(shared)` 上。设为共享是在视图所在路径上 `share`，设为个人是在调用者自己的路径上 `claim` |
| 写入 | `Command-Request-Id` 是端口的 `requestId`，`Command-Aggregate-Version` 是它的 `revision`；每次写入等到快照落地，以读回的视图作答 |
| 重试 | 以过期版本或重复请求 id 被拒的写入，先按请求 id 查一次，重试答第一次写下的结果。服务端不对创建去重（id 由它生成）；store 重发自己的创建前先问重放路由，只有从别的 store 发出的重试才会再建一个视图 |
| 错误 | 按 Wow 的错误码映射到 `CONFLICT`、`NOT_FOUND`、`FORBIDDEN`、`INVALID` 与 `UNAVAILABLE`；只有没回来认得的错误码时才看 HTTP 状态；没有视图存储的服务端是 `UNSUPPORTED`。错误把服务端的错误码留在 `detail.code`，服务端答了话的 `UNAVAILABLE` 带 `reachable` |
| 列表顺序 | 系统视图、共享视图、个人视图，每种受众按创建先后；每种受众至多 1000 个 |
| 系统视图 | 配置的只读；存储的（全局，在 `tenant/(platform)/owner/(system)` 下）带 `stored: true`，在那里新建（以复制的方式发布）、保存、改名、删除，从不设为共享或收为个人。它们的 `revision` 是内容散列，store 写入时发它读到的版本。引擎的 `editSystem` 由宿主给，不说即关 |
| 共享看板 | 被共享仪表盘显示着的视图保持共享：收为个人是 `INVALID`，`boards` 原样带上那几块看板的标题，由视图引擎用自己的话说出来 |

## 请求 {#requests}

端口的每个方法怎样变成发给视图存储服务端的请求。

每条路由都在 `/view-store/tenant/{tenantId}/owner/{ownerId}` 之下，**所有者段就是受众**：个人视图在调用者自己的路径上，共享视图在 `owner/(shared)` 上。

| 端口 | 请求 |
| --- | --- |
| `list` | 调用者路径与 `(shared)` 上的快照列表，投影为摘要（`state.config.kind`，不带配置），以及 `GET (shared)/system-views?definitionId=`，一起发出 |
| `get` | 在最后一次见到该视图的路径上读它的快照；未知 id 依次在调用者路径、`(shared)`、系统视图中查找 |
| `create` | 在其 `scope` 对应路径上 `POST …/view`（`system`：`tenant/(platform)/owner/(system)`）；id 由服务端生成 |
| `save`、`rename`、`delete` | 在视图所在路径上 `PUT …/view/{id}/save`、`/rename`、`DELETE …/view/{id}` |
| `changeAudience('shared')` | 在视图所在路径上 `PUT …/view/{id}/share` |
| `changeAudience('personal')` | 在调用者自己的路径上 `PUT …/view/{id}/claim` |
| `getPreferences`、`setPreferences` | 在调用者路径上 `GET`、`PUT …/definitions/{definitionId}/preferences` |

列表按端口约定的顺序应答——系统视图、共享视图、调用者的视图，每种受众按创建先后（服务端按 `firstEventTime` 排序）——每种受众最多读 1,000 个，这是服务端的查询预算：最早的 1,000 个。超出的视图仍能按 id 读到，地址里点名的视图，工作台会先去读，再决定是否放弃。

**写入**把端口的 `requestId` 作为 `Command-Request-Id`、`revision` 作为 `Command-Aggregate-Version`（从未写过的偏好为 `'0'`）发出，并等待快照。应答是按写入后的版本读回的视图。`share` 与 `delete` 发送 `{}`，`claim` 不带请求体。

**重试应答第一次的结果。** 服务端以过期版本或重复请求 id 拒绝的写入，先按请求 id 查一次（`GET …/view/requests/{requestId}`，先查发出写入的路径，再查另一条）：如果第一次已经落下，它的结果就是应答。只有这之后，过期版本才是 `CONFLICT`，带上视图当前的样子——视图已不在时则是 `NOT_FOUND`。服务端查询失败不算应答：在写入所在的路径上，结果未知（`UNAVAILABLE`，可以安全重试）；在另一条路径上——`(shared)` 拒绝没有共享角色的调用者——视为没有重放，拒绝成立。写入已落下、但在读回之前又被别的写入者推进的，按重放路由给出的视图应答；那条路由问不到时，按读到的应答。偏好没有这样的路由：store 对重试应答它自己第一次的应答；第一次的应答丢失时，若存储的内容正是它写的，就应答存储的内容。

**创建在服务端不是幂等的**，因为 id 由服务端生成。store 记住自己发起的创建的请求 id（最近 256 个），重试其中之一时先在其 `scope` 对应路径上问重放路由：第一次已经落下，就以那个视图应答，不再重复 POST。从另一个 store 实例发起的重试——另一个标签页、刷新之后——会生成第二个视图。

**被移动过的视图。** 另一个标签页把 store 记为个人的视图设为共享（或反过来）后，写入会在旧路径上被拒绝；store 会重新查找视图，再把写入发到它现在所在的路径一次。

**系统视图。** 服务端的系统视图（`GET (shared)/system-views`）分为配置的（只读）与存储的（`source: 'stored'`），`WowViewStore` 列出和读取后者时在摘要与实例上带 `stored: true`。存储的系统视图写在 `tenant/(platform)/owner/(system)`（`SYSTEM_TENANT_ID`、`SYSTEM_OWNER_ID`）上，与调用者的租户无关：它们是全局的。`create({ scope: 'system', … })` 发布一个（副本：源视图保留），`save`、`rename`、`delete` 编辑与取消发布；`changeAudience` 以及对配置的或代码中的系统视图的任何写入，在发出之前就是 `FORBIDDEN`。

系统视图的 `revision` 是其内容的哈希，这是引擎判断草稿是否改动所需要的，而写入期望的是聚合版本：store 记住每次读取存储视图时与 revision 一起拿到的 `version` 并发送它；引擎持有一个它没有对应版本的 revision 时，先重新读一次视图——与视图当前不符的 revision 是 `CONFLICT`，带上视图当前的样子，什么也不发。写入的应答按写入后的版本从系统视图路由读回。服务端以重复请求拒绝的重试，可以通过系统路径上的重放路由找到，但那条路由应答的是快照，其内容哈希只有服务端会算：store 以系统视图**当前的样子**应答，这就是被重试的写入的结果，除非中间有别的管理员写过。第一次尝试已经把视图推进（它持有的 revision 不再是视图的）的重试，在被拒为 `CONFLICT` 之前会先问重放路由；被重试的系统视图创建，以系统视图中的那个视图应答，带上标记，revision 为其哈希。

## 错误码映射 {#errors}

每个拒绝都是引擎的 `ViewStoreError`，按 Wow 的错误码判定——只有应答里没有 store 认识的错误码时才看 HTTP 状态：

| 服务端 | 端口 |
| --- | --- |
| `CommandExpectVersionConflict`、`EventVersionConflict`、`SourcingVersionConflict`；没有已知错误码时，409 与 412 | `CONFLICT` |
| `NotFound`（也包括属于别的应用的视图）、`IllegalAccessDeletedAggregate`；没有已知错误码时，404 与 410 | `NOT_FOUND` |
| `IllegalAccessOwnerAggregate`、`IllegalAccessSpaceAggregate`、`IllegalAccessQueryScope`、`SystemViewReadOnly`、`ViewEventStreamClosed`；没有已知错误码时，401 与 403 | `FORBIDDEN` |
| `ViewInvalid`、`ViewAppRequired`、`ViewScopeRequired`、`BadRequest`、`CommandValidation`、`IllegalArgument`、`DuplicateAggregateId`、`QuerySchemaValidation`；没有已知错误码时，400 与 422；fetcher 的拦截器没有填上的路径变量（什么也不发） | `INVALID` |
| `IllegalState`、`RequestTimeout`、`TooManyRequests`、`InternalServerError`、`QuerySchemaUnavailable`、`QuerySchemaConflict`；没有已知错误码时，其他任何状态；完全没有应答 | `UNAVAILABLE` |
| 完全没有视图存储的服务端（在它之前发布的版本）返回的 `404`：一次列表，或一次每个位置都是 `404`、同时服务端的系统视图也是 `404` 的读取 | `UNSUPPORTED` |

每个 `ViewStoreError` 都保留它的来源：请求的失败作为 `cause`，服务端的 `errorCode` 作为 `detail.code`（所以宿主能区分同为 `INVALID` 的 `ViewAppRequired` 与 `ViewInvalid`）；服务端应答了的 `UNAVAILABLE`——5xx、服务端报告的超时、不是 JSON 的页面——带 `reachable: true`，引擎据此说「服务端暂时无法处理」而不是「无法连接服务端」。

重复请求 id（`DuplicateRequestId`）本身不是错误：store 会去查第一次的尝试（见[请求](#requests)）。没有填上的路径变量是宿主的接线问题——它的拦截器没有填 `{tenantId}`，或个人路径上的 `{ownerId}`——所以是 `INVALID`，与服务端自己的 `ViewScopeRequired` 一样：请求构造得不对，重试只会发出同样的请求。

因为共享仪表盘正在显示而被服务端拒绝的收为个人，错误的 `boards` 原样带上那些看板的**标题**，视图引擎用自己的措辞围绕它们说出拒绝：写成键的标题仍是键，在显示拒绝的地方按语言说出。`message` 保留服务端的原话，供日志使用。

## 宿主与网关

宿主怎样接上 `WowViewStore`、怎样给出 `permissions`、不登录的页面怎样用它，见[视图存在哪里](../../../typescript/view-engine/storage.md#wowviewstore-视图存在-wow-服务上)。服务端不做认证，每个部署都放在 CoSec 网关后面；网关的路径规则，包括只让平台管理员写系统视图的那一条，见[视图存储](https://github.com/Ahoo-Wang/Wow/blob/v10/deploy/v10/view-store/README.md)。

## 源码

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) · [视图存储服务端](https://github.com/Ahoo-Wang/Wow/tree/v10-9x-baseline/view-store)

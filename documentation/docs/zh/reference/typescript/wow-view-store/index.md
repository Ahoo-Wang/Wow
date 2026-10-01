---
title: 'wow-view-store 参考'
description: '尚未发布的 @ahoo-wang/wow-view-store 包的 WowViewStore：视图引擎的 ViewStore 落在 Wow 视图存储服务端上。'
---

# wow-view-store 参考

::: warning 尚未发布
`@ahoo-wang/wow-view-store` 还没有发布到 npm，不承诺兼容；它随 [wow-view-engine](../wow-view-engine/) 一起发布。在那之前，以[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) 与[设计文档](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md)为准。
:::

`WowViewStore` 在[视图存储](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md)上实现视图引擎的 [`ViewStore` 端口](../wow-view-engine/#persistence)：保存的视图与偏好是两个 Wow 聚合，由独立的 `wow-view-store-server` 提供，或由引入了 `wow-view-store-starter` 的 Wow 服务提供。

## 入口

| 导出 | 作用 |
|---|---|
| `WowViewStore` | store 本身：`new WowViewStore({ fetcher, permissions? })`，交给 `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`：由它的拦截器带上租户、所有者与应用；`permissions`：哪些按钮可用 |
| `SHARED_OWNER_ID` | `(shared)`，共享视图与共享偏好的所有者段 |
| `ViewStoreErrorCodes` | 视图存储自己的错误码，与 Wow 的并列 |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
}
```

## 合同

| 规则 | 行为 |
|---|---|
| 谁在请求 | store 不收租户、用户或应用。由 fetcher 的拦截器填路径里的 `{tenantId}` 与个人路径的 `{ownerId}`（fetcher-cosec 的 `ResourceAttributionRequestInterceptor`，取自令牌），并发送 `CoSec-App-Id`；不登录的宿主用自己的拦截器填缺省值 |
| 受众 | 路径的所有者段：个人视图在调用者的路径上，共享视图在 `owner/(shared)` 上。设为共享是在视图所在路径上 `share`，设为个人是在调用者自己的路径上 `claim` |
| 写入 | `Command-Request-Id` 是端口的 `requestId`，`Command-Aggregate-Version` 是它的 `revision`；每次写入等到快照落地，以读回的视图作答 |
| 重试 | 以过期版本或重复请求 id 被拒的写入，先按请求 id 查一次，重试答第一次写下的结果。服务端不对创建去重（id 由它生成）；store 重发自己的创建前先问重放路由，只有从别的 store 发出的重试才会再建一个视图 |
| 错误 | 按 Wow 的错误码映射到 `CONFLICT`、`NOT_FOUND`、`FORBIDDEN`、`INVALID` 与 `UNAVAILABLE`；只有没回来认得的错误码时才看 HTTP 状态 |
| 共享看板 | 被共享仪表盘显示着的视图保持共享：收为个人是 `INVALID`，`boards` 原样带上那几块看板的标题，由视图引擎用自己的话说出来 |

## 宿主

示例服务端与补偿服务引入了 `wow-view-store-starter`，补偿控制台的视图就存在那里。控制台不登录，于是它插入自己的请求拦截器：请求没给租户、所有者与应用时，填上租户 `(0)`、所有者 `(shared)` 与它的应用（`compensation-dashboard`）——它存下的视图与偏好都是共享的，权限里关掉个人视图。引入 starter 的宿主用 `wow.view-store.kafka.topic-prefix` 给视图存储的 Kafka 主题一个自己的前缀，宿主自己的主题不变。

## CoSec 网关规则

服务端不做认证：租户、所有者与应用按请求写的取，所以每个部署都放在 CoSec 网关后面，由网关的路径规则把它们与令牌对上：

| 路径 | 放行条件 |
|---|---|
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`，`…/view/{id}/claim` 与 `…/view/{id}/share` 除外 | `{tenantId}` 是令牌的租户，且 `{ownerId}` 是令牌的 `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` 的读 | `{tenantId}` 是令牌的租户 |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` 的写 | 令牌的租户，且具备写共享视图的角色 |
| `PUT /view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`、`…/share` | 令牌的租户，`{ownerId}` 是令牌的 `sub`，**且**具备写共享视图的角色 |

设为个人会把共享视图从所有人的列表里拿走，设为共享会把个人视图发布到所有人的列表里，所以个人规则不能单独放行它们：个人规则里排除 `…/view/{id}/claim` 与 `…/view/{id}/share`，只由改受众那一条规则决定（否则谁都能先建个人视图再设为共享，发布共享视图）。`CoSec-App-Id` 由 CoSec 认证，服务端按它隔离应用；宿主按网关校验的同一个角色给出 `permissions`：`createShared` 与 `changeAudience` 需要写共享视图的角色。完整的规则与理由见[视图存储的 README](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md#cosec-gateway-rules)。

## 源码

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.zh-CN.md) · [视图存储服务端](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md)

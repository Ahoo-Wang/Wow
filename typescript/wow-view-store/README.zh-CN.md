# `@ahoo-wang/wow-view-store`

[English](./README.md)

视图引擎的 `ViewStore` 落在 Wow 服务端上：[`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.zh-CN.md)
保存的视图与偏好，由[视图存储](../../view-store/README.md)保管——两个 Wow 聚合，由独立的
`wow-view-store-server` 提供，或由任何引入了 `wow-view-store-starter` 的 Wow 服务提供。

> **尚未发布。** 与视图引擎一样，本包还没有发布到 npm，不承诺兼容。它随视图引擎一起发布，与 Wow
> 同一个标签、同一个版本。

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
网关决定。`createShared` 与 `changeAudience` 按能写 `owner/(shared)` 的角色给——新建共享视图、收为个人、设为共享都需要它。不给则全部允许。

### 不登录的宿主

没有令牌，就没有谁去填路径里的租户与所有者。这样的宿主加一个自己的拦截器，在请求没给时填上缺省值，并说明自己
是哪个应用；所有者填 `(shared)`（`SHARED_OWNER_ID`）时，它只有共享视图与共享偏好，所以它的权限把
`createPersonal` 关掉。

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';
import { SHARED_OWNER_ID } from '@ahoo-wang/wow-view-store';

class ConsoleDefaults implements RequestInterceptor {
  readonly name = 'ConsoleDefaults';
  readonly order = 0;

  intercept(exchange: FetchExchange): void {
    const path = exchange.ensureRequestUrlParams().path;
    path.tenantId ??= '(0)';
    path.ownerId ??= SHARED_OWNER_ID;
    exchange.ensureRequestHeaders()['CoSec-App-Id'] = 'console';
  }
}

fetcher.interceptors.request.use(new ConsoleDefaults());
```

## 端口怎样落到请求上

所有路由都在 `/view-store/tenant/{tenantId}/owner/{ownerId}` 之下，**所有者段就是受众**：个人视图在调用者
自己的路径上，共享视图在 `owner/(shared)` 上。

| 端口                               | 请求                                                                                                                                                    |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list`                             | 调用者路径与 `(shared)` 上的快照列表，只投影摘要的字段（`state.config.kind`，不含配置），以及 `GET (shared)/system-views?definitionId=`，三个请求一起发 |
| `get`                              | 在上次见到这个视图的地方按 id 读快照；没见过的 id 依次在调用者路径、`(shared)`、系统视图上找                                                            |
| `create`                           | 在其 `scope` 对应的路径上 `POST …/view`；id 由服务端生成                                                                                                |
| `save`、`rename`、`delete`         | 在视图所在路径上 `PUT …/view/{id}/save`、`/rename`，`DELETE …/view/{id}`                                                                                |
| `changeAudience('shared')`         | 在视图所在路径上 `PUT …/view/{id}/share`                                                                                                                |
| `changeAudience('personal')`       | 在调用者自己的路径上 `PUT …/view/{id}/claim`                                                                                                            |
| `getPreferences`、`setPreferences` | 调用者路径上的 `GET`、`PUT …/definitions/{definitionId}/preferences`                                                                                    |

列表按端口规定的顺序作答——系统视图、共享视图、调用者的个人视图，每种受众按创建先后（服务端按 `firstEventTime`
排序）——每种受众最多读 1000 个，即服务端的查询上限：最早的 1000 个。截断之外的视图仍可按 id 读到，地址里点名的
视图工作台会先按 id 问一次，再决定是不是别的定义的。

**写入**把端口的 `requestId` 作为 `Command-Request-Id`、`revision` 作为 `Command-Aggregate-Version`
（从没写过的偏好是 `'0'`）发送，并等到快照落地。作答的是按这次写入留下的版本读回的视图。`share` 与 `delete`
发送 `{}`，`claim` 不带请求体。

**重试答第一次的结果。** 服务端以过期版本或重复的请求 id 拒绝一次写入时，先按请求 id 查一次
（`GET …/view/requests/{requestId}`，先在这次发往的路径上，再在另一条上）：第一次已经落地，就以它的结果作答。
之后过期版本才是 `CONFLICT`，带上视图现在的样子——视图已经不在了则是 `NOT_FOUND`。服务端答不上来的查询不算回答：
在这次发往的路径上，结局未知（`UNAVAILABLE`，重试安全）；在另一条路径上——没有共享角色的调用者在 `(shared)`
会被拒——就当没有重放，原来的拒绝照旧。已经落地、读回前又被别人推过的写入，以重放路由给的那一版作答；
那条路由问不了时，以读回的作答。偏好没有这样的路由：
store 以自己第一次得到的结果回答重试；第一次的回答丢了的重试，在存着的正是它写的内容时，以存着的作答。

**创建在服务端不是幂等的**，id 由服务端生成。store 记着自己发出的创建的请求 id（最近 256 个），
重试其中一个时先在其 `scope` 的路径上问重放路由：第一次已经落地，就以那个视图作答，不再发一次。
从另一个 store 实例发出的重试——另一个标签页、刷新之后——会再建一个视图。

**挪了地方的视图。** 另一个标签页把这个 store 记作个人的视图设为了共享（或反过来），写入在旧路径上被拒；
store 重新找到视图，把写入再发一次到它现在所在的地方。

## 错误

所有拒绝都是引擎的 `ViewStoreError`，按 Wow 的错误码读——只有回答里没有 store 认得的错误码时才看 HTTP 状态：

| 服务端                                                                                                                                                                                                                                  | 端口          |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `CommandExpectVersionConflict`、`EventVersionConflict`、`SourcingVersionConflict`；没有认得的错误码时 409、412                                                                                                                          | `CONFLICT`    |
| `NotFound`（别的应用的视图也是）、`IllegalAccessDeletedAggregate`；没有认得的错误码时 404、410                                                                                                                                          | `NOT_FOUND`   |
| `IllegalAccessOwnerAggregate`、`IllegalAccessSpaceAggregate`、`IllegalAccessQueryScope`、`SystemViewReadOnly`、`ViewEventStreamClosed`；没有认得的错误码时 401、403                                                                     | `FORBIDDEN`   |
| `ViewInvalid`、`ViewAppRequired`、`ViewScopeRequired`、`BadRequest`、`CommandValidation`、`IllegalArgument`、`DuplicateAggregateId`、`QuerySchemaValidation`；没有认得的错误码时 400、422；fetcher 的拦截器没填的路径变量（什么都没发） | `INVALID`     |
| `IllegalState`、`RequestTimeout`、`TooManyRequests`、`InternalServerError`、`QuerySchemaUnavailable`、`QuerySchemaConflict`；没有认得的错误码时其余状态；根本没有回答                                                                   | `UNAVAILABLE` |
| 没有视图存储的服务端（早于它发布的）答的 `404`：列表，或每处都 `404` 且服务端的系统视图也 `404` 的读写                                                                                                                                  | `UNSUPPORTED` |

每个 `ViewStoreError` 都留着它的来处：请求本身的失败是 `cause`，服务端的 `errorCode` 是 `detail.code`（宿主据此分辨同为
`INVALID` 的 `ViewAppRequired` 与 `ViewInvalid`）；服务端答了话的 `UNAVAILABLE`——5xx、它报的超时、不是 JSON 的页面——带
`reachable: true`，引擎据此说「服务端暂时无法处理」，而不是「无法连接服务端」。

重复的请求 id（`DuplicateRequestId`）本身不是错误：store 去查第一次的结果（见上）。没填的路径变量是宿主的配置问题——
拦截器没填 `{tenantId}`，或个人路径上的 `{ownerId}`——所以与服务端自己的 `ViewScopeRequired` 一样是 `INVALID`：
请求一构造就是错的，重试发出的还是同一个。

服务端因为有共享仪表盘显示着视图而拒绝收为个人时，错误的 `boards` 原样带上那几块看板的**标题**，由视图引擎用自己的话
说出这次拒绝：以键写的标题仍是键，在显示拒绝的地方说成话。`message` 保留服务端的原话，供日志。

`WowViewStoreErrorCodes` 列出视图存储自己的错误码。

## 测试

- `pnpm --filter @ahoo-wang/wow-view-store test`——对着假服务端的单元测试、公开面与 API 报告。
- 端口的一致性测试套件在 `typescript/integration-test`（`test/view-store/`）里对着视图存储服务端跑
  `WowViewStore`，另有租户与应用的隔离测试。

以 Apache License 2.0 许可。

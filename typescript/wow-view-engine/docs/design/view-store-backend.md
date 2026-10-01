# Wow 存储后端：`ViewStore` 的服务端（阶段 6）

**状态**：用户已确认（2026-09-29），已全部实现（V0～V3，2026-09-30）。第 4 节是隔离与路径，第 5 节是聚合根，第 6 节是客户端与端口；各节的代码是拟定的形状，名字以实现为准。裁定见 [D75](decisions.md#d75-首发前做-wow-存储后端用真服务端验证-viewstore2026-09-29)。

## 1. 为什么首发前做

`ViewStore` 是引擎唯一的持久化端口（[management.md](management.md)「持久化端口与一致性」）。今天它只有两种实现：`MemoryViewStore` 与它的本地快照 `localStorageSnapshot`——都是单用户、单进程，没有经历过多用户、共享与个人的可见性、服务端鉴权、真实的并发冲突与超时重试。端口随首发公开，之后改它就是破坏性改动；用一个真服务端把它验一遍，发现要改就趁现在改。

Wow 自己做这个后端也最顺：端口的两条一致性规则在 Wow 里是现成的——期望版本对上聚合版本，`requestId` 去重对上命令幂等；租户、所有者、应用的隔离沿用 Wow 与 CoSec 的约定，服务端只定义路径规则，鉴权由 CoSec 安全网关负责。

## 2. 范围

| 做                                                                                             | 不做（首发后）                                       |
| ---------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| 端口的 8 个方法                                                                                | 订阅与告警、版本历史、验证、缓存（D22）              |
| **就地改受众**：已有视图「设为共享／设为个人」（[D18](decisions.md) 第 10 条），端口加一个方法 | 图上的注释（[todo.md](todo.md)「首发后再议」）       |
| 服务端配置的系统视图（只读）                                                                   | 管理员维护系统视图的界面（由业务系统的管理入口负责） |
| 租户、应用、所有者三维隔离                                                                     | 按 space 隔离（见 4.4）                              |

**补偿控制台也迁移**：补偿服务引入 **wow-view-store-starter**，自动获得视图能力（第 3 节）；控制台不登录，由它自己插入一个 fetcher 请求拦截器，给租户、所有者、应用注入缺省值（fetcher-cosec 的拦截器只在请求没给时才填，控制台的缺省值因此生效）。它是视图后端的第一个真实宿主，与 `typescript/integration-test` 一起做验证（第 7 节）。

## 3. 模块

照补偿的样子在 Wow 仓加一组模块，放在 `view-store/` 目录下：

| 模块                                | 内容                                                                                                                                   |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **wow-view-store-api**              | 限界上下文、命令、事件、查询形状；生成 OpenAPI 的元数据                                                                                |
| **wow-view-store-domain**           | 两个聚合                                                                                                                               |
| **wow-view-store-starter**          | Spring Boot 自动配置：业务服务引入它，就把视图的聚合、路由与系统视图接口装进自己（补偿服务、示例服务端都这样用）                       |
| **wow-view-store-server**           | 独立的视图宿主服务：引入 starter 的一个 Spring Boot 应用（MongoDB 存事件与快照），给没有自己 Wow 服务、或想把视图集中存放的业务部署    |
| **@ahoo-wang/wow-view-store**（TS） | 手写的 **WowViewStore**：用 wow-client 的查询 DSL 与 fetcher 直接发请求，不生成客户端（6.2）；随后端合同一起发布（management.md 末段） |

全部是新增模块。唯一的框架改动是前置的 **V0**（4.4）：没有开启 `spaced` 的聚合不写入、不过滤 spaceId。

## 4. 隔离与路径

### 4.1 四个维度

| 维度   | 来源                                                | 怎样隔离                                                                                                   | 谁保证                         |
| ------ | --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------ |
| 租户   | 路径 `tenant/{tenantId}`                            | Wow 的租户路由：聚合不设静态租户，路由都带租户前缀                                                         | CoSec 按路径校验调用者的租户   |
| 所有者 | 路径 `owner/{ownerId}`                              | Wow 的所有者路由（聚合要求所有者）；**所有者段就是受众**（4.2）                                            | CoSec 按路径校验               |
| 应用   | 请求头 `CoSec-App-Id`（fetcher-cosec 每个请求都带） | 创建时写进状态；查询由服务端的查询策略按请求的应用追加过滤（调用方去不掉）；命令的应用与状态不符读作不存在 | CoSec 认证应用；服务端按头隔离 |
| 空间   | —                                                   | **不隔离**（4.4）                                                                                          | —                              |

服务端不做鉴权：谁能读写哪个路径，由 CoSec 安全网关按路径规则决定。服务端要做的是把路径规则定义清楚、把隔离维度落进数据与查询。

### 4.2 受众就是所有者段

| 受众     | 路径                                                            | CoSec 的规则（示意）                                                                                                                      |
| -------- | --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| 个人     | `/view-store/tenant/{tenantId}/owner/{ownerId}/view/…`          | 所有者段等于调用者本人（令牌的 `sub`）时放行；`…/claim` 与 `…/share` 除外（见下两行）                                                     |
| 共享     | `/view-store/tenant/{tenantId}/owner/(shared)/view/…`           | 所有者段是保留值 `(shared)`：按角色或权限放行                                                                                             |
| 收为个人 | `/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim` | 所有者段等于调用者本人，**且**具备写共享视图（`owner/(shared)`）的角色——收走一个共享视图，等于把它从所有人的列表里拿掉（用户 2026-09-29） |
| 设为共享 | `/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/share` | 同收为个人：所有者段等于调用者本人，**且**具备写共享视图的角色——设为共享等于把视图发布到所有人的列表里，否则谁都能先建个人视图再设为共享  |

- `(shared)` 是保留的所有者值：带括号，不会与任何用户 id 相同。
- **改受众 = 转移所有者**（Wow 的转移所有者事件），视图 id 不变，看板的引用不断。身份也从路径来（用户 2026-09-29）：「设为个人」发到**调用者自己的个人路径**（`owner/{ownerId}` 由 fetcher-cosec 按令牌自动填，网关只放行本人），服务端把视图从 `(shared)` 转给这个 `{ownerId}`；「设为共享」发到视图当前所在的个人路径，转给 `(shared)`。服务端不从令牌取身份，独立的 server 也不需要 CoSec 依赖。谁能「收为个人」「设为共享」与谁能改共享视图同一个权限：网关给 `…/claim` 与 `…/share` 都配上写 `owner/(shared)` 的角色，并把它们从个人规则里排除；引擎侧宿主按同一个角色给出 `permissions.createShared` 与 `permissions.instance(id).changeAudience`，没有权限的人看不到这些按钮。
- 不登录的宿主只用 `owner/(shared)`，没有个人视图。

### 4.3 路径一览

所有路径的前缀是 `/view-store/tenant/{tenantId}/owner/{ownerId}`，下表省略它。命令路由由 Wow 按聚合元数据生成；读的合并与偏好走自定义路由，同样带这个前缀。

| 方法与路径                                           | 端口方法                                 | 说明                                                                                                                                          |
| ---------------------------------------------------- | ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /view`（CreateView）                           | `create`                                 | 聚合 id 由服务端生成                                                                                                                          |
| `PUT /view/{id}/save`、`/rename`                     | `save`、`rename`                         | 带期望版本                                                                                                                                    |
| `PUT /view/{id}/claim`、`/share`                     | **changeAudience**（设为个人、设为共享） | 带期望版本；claim 发到自己的个人路径，share 发到视图当前所在的路径                                                                            |
| `DELETE /view/{id}`                                  | `delete`                                 | 带期望版本                                                                                                                                    |
| Wow 的快照查询路由（列表、按 id 读）                 | `list`、`get`                            | 摘要只投影不含 `config` 的字段；`kind` 取自 `config.kind`                                                                                     |
| `GET /system-views?definitionId=…`                   | `list` 的一部分                          | 服务端配置的系统视图（只读），与存储在 `tenant/(platform)/owner/(system)` 下的全局系统视图（`source`、`version`），只挂在 `owner/(shared)` 下 |
| `GET /view/requests/{requestId}`                     | 重放                                     | 这次写入落地时的实例，只在本路径的租户、所有者与请求的应用内查找；删除类答 204（第 6 节）                                                     |
| `GET`、`PUT /definitions/{definitionId}/preferences` | `getPreferences`、`setPreferences`       | 偏好聚合的 id 由服务端按「所有者 × 应用 × 定义」算出                                                                                          |

每个写入都带 `Command-Request-Id`（端口的 `requestId`）与 `Command-Wait-Stage: SNAPSHOT`；修改类另带 `Command-Aggregate-Version`（端口的 `revision`）。**`Command-Request-Id` 必须显式给**：fetcher-cosec 每个请求都生成一个新的 `CoSec-Request-Id`，Wow 只在命令没有 requestId 时拿它来补，重试若不显式带同一个 requestId 就去不了重。

### 4.4 不按 space 隔离

视图是「怎么看」，不装数据；打开视图时的数据查询自带 space，数据的隔离在那里完成。视图若按 space 隔离，一个人管三家门店就要把同一个视图建三遍、总部的标准视图要每个 space 各一份、个人偏好换一个 space 就回到空白。以后真要「只在本团队内共享」，加一个受众即可：没带 space 的数据就在缺省的空 space，语义不变、不必迁移。

**V0（前置框架修复）**：今天 `@AggregateRoute(spaced)` 只影响 OpenAPI，运行时不看——命令提取一律把 space 头（`CoSec-Space-Id`、`Wow-Space-Id`）写进命令，查询范围一律按它过滤。按用户 2026-09-29 的裁定这是契约错误：没有开启 `spaced` 的聚合不写入、不过滤 spaceId。视图的两个聚合不开 `spaced`，便天然不按 space 隔离。

## 5. 聚合根

限界上下文 **view-store**（别名同名，目录也是 `view-store/`）。两个聚合根都要求所有者、不设静态租户、不开 `spaced`。

### 5.1 视图实例（**View**）

一个保存下来的视图：记录视图、分析视图或仪表盘。

```kotlin
enum class ViewAudience { PERSONAL, SHARED }

data class ViewState(
    val id: String,          // 服务端生成
    val definitionId: String,
    val title: String,
    /** 由所有者推出：所有者是 (shared) 即共享，否则个人。 */
    val audience: ViewAudience,
    /** 创建时的 CoSec-App-Id。 */
    val appId: String,
    /** 引擎的 ViewConfig，整份 JSON，服务端不解释其语义。 */
    val config: ObjectNode,
    // tenantId、ownerId 是 Wow 聚合状态自带的
)
```

| 命令                                                   | 事件                                 | 规则                                                                                                                                                                                                   |
| ------------------------------------------------------ | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **CreateView** `{definitionId, title, config}`（创建） | **ViewCreated**                      | id 由服务端生成；应用取自 `CoSec-App-Id`（缺则拒绝）；受众由路径的所有者段推出                                                                                                                         |
| **SaveView** `{config}`                                | **ViewSaved**                        | 期望版本；应用与状态不符读作不存在                                                                                                                                                                     |
| **RenameView** `{title}`                               | **ViewRenamed**                      | 同上；标题去空白后非空、不超过 120 字                                                                                                                                                                  |
| **ClaimView**（`…/owner/{ownerId}/view/{id}/claim`）   | **ViewAudienceChanged** + 转移所有者 | 同上；视图须当前共享（所有者 `(shared)`）；转给路径的 `{ownerId}`（不得是带括号的保留值）；**若被共享看板引用则拒绝**（**ViewInvalid**，带引用它的看板）；已是本人的个人视图则原样答当前版本，不发命令 |
| **ShareView**（`…/owner/{ownerId}/view/{id}/share`）   | **ViewAudienceChanged** + 转移所有者 | 同上；发往视图当前所在的个人路径，转给 `(shared)`；已共享则原样答当前版本，不发命令                                                                                                                    |
| **DeleteView**（删除聚合）                             | **ViewDeleted**（聚合已删除）        | 同上；软删，之后读作不存在                                                                                                                                                                             |

- `config` 只做形状与大小的检查（是对象、`kind` 为记录／分析／仪表盘之一、不超过 240 KB——低于 WebFlux 默认缓冲的 256 KB 请求体；有 `panels` 时是对象数组，其中的引用 `instanceId`、`opens`、`click.instanceId` 是不超过 256 字的字符串——按类型建映射的存储（Elasticsearch）索引这几条路径，别的类型会让整条快照写不进去）；`definitionId` 不超过 256 字；语义由引擎打开时准入。
- 所有者不符由 Wow 自己拒绝（命令里的所有者与状态不符）；应用不符由领域拒绝为不存在，不暴露它在别的应用里存在。
- 「被共享看板引用」由领域内一个快照查询回答：同租户、同应用、所有者为 `(shared)` 的仪表盘里，面板引用了这个 id 的。

### 5.2 视图偏好（**ViewPreferences**）

一个所有者在一个定义下的偏好：视图排序、默认视图、自动运行、上次打开的标签页。

```kotlin
data class ViewPreferencesState(
    val id: String,            // 服务端按「所有者 × 应用 × 定义」算出
    val definitionId: String,
    val appId: String,
    val order: List<String>,
    val defaultInstanceId: String?,
    val autoRun: Boolean?,
    val lastTabs: Map<String, String>?,
)
```

| 命令                                                                                               | 事件                   | 规则                                                                                                  |
| -------------------------------------------------------------------------------------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------- |
| **SetViewPreferences** `{definitionId, order, defaultInstanceId, autoRun?, lastTabs?}`（允许创建） | **ViewPreferencesSet** | 聚合 id 由自定义路由在服务端算出后派发；期望版本 0 即「从没写过」，对上 `emptyPreferences()` 的 `'0'` |

不登录的宿主，偏好挂在 `owner/(shared)` 下，按定义共用一份。

### 5.3 系统视图

系统视图有两个来源，引擎合并在同一个列表里，都只读、只能另存（[management.md](management.md)「范围与许可」）：

| 来源       | 怎样给出                                                                                                 | 适合                                     |
| ---------- | -------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| **宿主**   | 前端代码里 `defineView` 的 `views`，随前端发版；id 带 `system:` 前缀，revision 是 `'code'`，不经过 store | 每个业务对象的基础视图与常用视图         |
| **服务端** | **SystemViewProvider**（配置文件或业务服务自己的实现）按租户、应用、定义给出，随列表返回                 | 运维要在线调整的标准视图，不必发前端版本 |

服务端给出的系统视图挂在 `owner/(shared)` 下：id 不用 `system:` 前缀（留给代码声明的），revision 是内容的散列，`scope` 为 `system`，任何写入答「无权」。两个来源同时有时，宿主声明的排在前面。

## 6. 客户端与端口

### 6.1 端口的变化（`@ahoo-wang/wow-view-engine`）

```ts
export interface ViewStore {
  // …原有 8 个方法不变
  /** 设为共享／设为个人。可选：没有它的 store，管理器不出这个入口。 */
  changeAudience?(
    id: string,
    audience: ViewAudience,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance>;
}

export interface InstancePermissions {
  save: boolean;
  rename: boolean;
  delete: boolean;
  /** 缺省读作 true（沉默不是拒绝），与其余几项的读法一致。 */
  changeAudience?: boolean;
}
```

运行时加一个写入动作「改受众」，走同一套写入账本（冲突、结局未知、重试）；管理器每一行加「设为共享」或「设为个人」。

### 6.2 **@ahoo-wang/wow-view-store**

```ts
export interface WowViewStoreOptions {
  /**
   * 带 CoSec 拦截器的 fetcher：路径里的 {tenantId}、{ownerId} 由它从令牌自动填，
   * 应用、space、认证也由它带上。
   */
  fetcher: Fetcher;
  /** 按钮是否可用。服务端不鉴权，宿主按自己在 CoSec 里的角色给；缺省全部允许。 */
  permissions?: (definitionId: string) => ViewPermissions;
}

export class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  // 端口的 8 个方法 + changeAudience + permissions
}
```

- **不生成客户端**：路由只有十来条，路径变量要留给拦截器填、重放要分辨 `204`、错误要按错误码读，生成的装饰器客户端这几样都帮不上，还要多一份与服务端逐字节核对的生成代码；所以请求在 **WowViewStore** 里手写，查询体用 wow-client 的 DSL。依赖不因此变：wow-client 本来就以 `fetcher-decorator` 与 `fetcher-eventstream` 为对等依赖，宿主随它一并安装（README 的「Use」列出）。
- **路径参数**：`{tenantId}` 与个人视图的 `{ownerId}` 留空，由 fetcher 的拦截器填——fetcher-cosec 按令牌的 `tenantId` 与 `sub`；不登录的宿主（如补偿控制台）插入自己的拦截器注入缺省值。共享视图显式填 `(shared)`（拦截器只填没给的参数）。**WowViewStore** 不收租户、用户这类选项。
- **列表**：个人与共享各查一次（快照列表，只投影摘要的字段，`kind` 取 `state.config.kind`，按 `firstEventTime`、`aggregateId` 升序，每种受众至多 1000 个——最早的 1000 个，即服务端的查询上限），再合并系统视图，三个请求一起发；按端口的顺序作答：系统、共享、个人。截断之外的视图仍可按 id 读，地址里点名而列表里没有的视图，工作台先按 id 问一次再判断归属。
- **错误的来处**：`ViewStoreError` 带 `cause`（请求本身的失败）与 `detail.code`（服务端的 `errorCode`），宿主据此分辨同为 `INVALID` 的 `ViewAppRequired` 与 `ViewInvalid`。服务端自己的错误码常量在客户端叫 `WowViewStoreErrorCodes`，与引擎的 `ViewStoreErrorCode`（端口的码）分开。
- **按 id 读写要知道是个人还是共享**：端口只给 id。客户端记住列表、读取、创建时得到的「id → 受众」；没见过的 id 依次按个人、共享、服务端系统视图去读。系统视图的 id 由系统视图接口给出，客户端认得。
- 写入：显式带 `Command-Request-Id` 与期望版本，等到 `SNAPSHOT`，成功后按 id 读回实例作答；读回的版本已被别人推过时，按 `requestId` 重放读回这次写入留下的那一版。`share` 与删除发 `{}`，`claim` 不带请求体。
- **挪了地方的视图**：记着在个人路径上的视图被别的标签页设为了共享（或反过来），写入在旧路径上被拒（所有者不符或找不到）；客户端重新找到它，把同一个写入（同一个 `requestId`）再发一次到它现在的路径。
- **按 Wow 的错误码映射，不按 HTTP 状态**：

| Wow 错误码                                    | 端口                                                                                     |
| --------------------------------------------- | ---------------------------------------------------------------------------------------- |
| 期望版本冲突、事件版本冲突                    | `CONFLICT`（先查重放；再读一次，填进 `instance` 或 `preferences`；读不到则 `NOT_FOUND`） |
| 重复的 `requestId`                            | 不是错误：**重放**                                                                       |
| 找不到、访问已删除的聚合、应用不符            | `NOT_FOUND`                                                                              |
| 未认证、所有者不符、CoSec 拒绝（401、403）    | `FORBIDDEN`                                                                              |
| 校验失败、参数非法、领域的 **ViewInvalid**    | `INVALID`                                                                                |
| 拦截器没填的路径变量（请求没有发出）          | `INVALID`（宿主的配置错了，与服务端缺租户或所有者时的回答同；重试发出的还是同一个）      |
| `IllegalState`、等待超时、限流、5xx、网络失败 | `UNAVAILABLE`（结局未知，重试复用同一个 `requestId`）；服务端答了话的带 `reachable`      |
| 没有视图存储的服务端（早于它发布）答的 `404`  | `UNSUPPORTED`（列表的路由 `404`；按 id 读写时各处都 `404` 且系统视图列表也 `404`）       |

**重放**：端口要求重试答第一次的结果，Wow 对重复的 `requestId` 报错。客户端收到「重复」——或期望版本冲突、找不到，重试遇到的正是这几种——时调 `GET /view/requests/{requestId}`：服务端按 `requestId` 在事件流里找到那次写入的聚合与版本，读回当时的实例作答（删除类答 204）。它只在写入者自己的路径上找得到，而重试可能发往与第一次不同的路径（设为共享的重试发往 `(shared)`，第一次发往个人路径），所以客户端先查这次发往的路径，再查另一条。这次发往的路径查不了（非「找不到」的失败）时结局未知，答 `UNAVAILABLE`；另一条路径查不了（没有共享角色的调用者在 `(shared)` 被拒）就当没有重放，原来的拒绝照旧；已经落地的写入回读时查不了，以读回的作答。事件流查询不对外开放，因为事件里有别人个人视图的配置。

**创建的重放**：聚合 id 由服务端生成，服务端**不对创建去重**——全局的 `requestId` 唯一索引无法只作用于视图聚合（会波及引入 starter 的业务服务自己的聚合），用户 2026-09-29 接受（第 9 节）。客户端在一个 store 实例里补上：记着自己发出的创建的请求 id（有界，与偏好相同），重试其中一个时先在其受众的路径上问 `GET /view/requests/{requestId}`，第一次已经落地就以那个视图作答、不再发；不用客户端生成的 id。别的 store 实例（另一个标签页、刷新之后）的重试仍会再建一个。

偏好没有重放路由：客户端记着自己每次偏好写入的结果，同一个 `requestId` 的重试直接答第一次的结果；第一次的回答丢了的重试被拒为冲突或重复时，存着的内容正是它要写的，就以存着的作答，否则是 `CONFLICT`。

## 7. 验证

- **V0 的框架测试**：没开 `spaced` 的聚合带着 space 头（两种头都测）——命令不写入 spaceId、查询不加 space 过滤；开了的照旧。
- **端口一致性测试**：一套用例，`MemoryViewStore` 与 **WowViewStore** 都要通过。覆盖：列表只含所问定义、没有 `config`、没有保留前缀；个人与共享的可见；创建、保存、改名、删除、改受众；过期 revision 是 `CONFLICT`；系统视图不可写；重放答第一次的结果（包括别人在中间改过之后）；偏好从 `'0'` 起、按所有者分开；所有失败都是 `ViewStoreError`。revision 只比变与不变。
- **真服务端的端到端**：`typescript/integration-test` 的 `test/view-store/` 连 **wow-view-store-server**（内存总线、固定机器号，与示例服务端共用 MongoDB，8090 端口），在 CI 的同源契约任务里跑一致性套件与隔离测试；示例服务端引入 **wow-view-store-starter** 后，引擎的端到端连示例服务端。示例服务端没有 CoSec 网关，端到端直接按路径扮演两个用户与共享，验证数据与行为；网关的鉴权规则不在本仓测试之内。
- **隔离测试**：两个租户、两个应用各存视图，互相读不到、改不到。
- **补偿控制台**：控制台换成 **WowViewStore**，由自己的拦截器注入缺省的租户、所有者、应用；它的端到端（`CI=1 pnpm test:browser`）走一遍保存、另存、共享与看板。
- **引擎端到端**：引擎配 **WowViewStore** 走一遍保存、另存、冲突后重载覆盖、结局未知后重试、设为共享。

## 8. 批次

| 批  | 内容                                                                                                                                                                                                                                                                                                                                                             | 判据                                                                  |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| V0  | 框架：`spaced` 成为聚合元数据；没开 `spaced` 的聚合，任何来源的命令都不写入 spaceId、查询不按它过滤——**已合并（#3791）**                                                                                                                                                                                                                                         | 框架测试两种头、两种聚合、saga 都覆盖；发布说明写明行为变化与滚动升级 |
| V1  | 后端：api、domain、**wow-view-store-starter**、**wow-view-store-server** 四个模块、两个聚合、系统视图接口、偏好与系统视图的自定义路由、重放端点、全局 `requestId` 唯一约束；领域测试                                                                                                                                                                             | 领域测试覆盖第 5 节每条规则；视图服务能起，OpenAPI 可生成             |
| V2  | 端口：**changeAudience** 与管理器入口；端口一致性测试套件（先让 `MemoryViewStore` 过）——**已实现**                                                                                                                                                                                                                                                               | 公开面快照更新；一致性套件在内存实现上全绿                            |
| V3  | TS：**@ahoo-wang/wow-view-store**；示例服务端与补偿服务引入 starter；补偿控制台迁移（自己的拦截器注入缺省租户、所有者、应用）；端到端与隔离测试——**已实现**：V3a 包、单元测试，一致性套件与隔离测试对独立服务端（#3810）；V3b 示例服务端与补偿服务内嵌 starter、视图存储自己的 Kafka 主题前缀、控制台迁移、一致性套件／隔离测试／引擎端到端对两种服务端（#3814） | 一致性套件在 Wow 实现上全绿；端到端、隔离测试、引擎端到端在 CI 里过   |

V0～V3 都已完成。

## 9. 另外定下的两点

- **一致性测试套件**放在 `wow-view-engine` 的测试目录里，由 `integration-test` 按工作区路径引用；不放进公开的 `/testing` 入口（会把测试框架带进发布的入口）。落点是 `test/conformance/viewStoreConformance.ts` 的 `describeViewStoreConformance`，接口见 [management.md](management.md)「持久化端口与一致性」；V3 从 `typescript/integration-test/test/` 以 `../../wow-view-engine/test/conformance/viewStoreConformance.js` 引入，传 **WowViewStore** 的工厂与能力声明（`integration-test` 的 `tsconfig.test.json` 要把 `rootDir` 放宽到 `..`，否则 TS6059）；拒绝改成个人时 `INVALID` 的 `ViewStoreError.boards` 是那些看板的**标题**：服务端在每个绑定错误里给看板的 id（`name`）与标题（`msg`），客户端取标题，没给标题的按 id 读一次看板；标题原样放进 `boards`，以键写的标题仍是键，句子由引擎用自己的目录说（`view.changeAudience.invalid.shared-boards`），标题在显示处说成话（D2）。**`create` 的重放**由能力 `idempotentCreate` 决定考不考：Wow 服务端不对 `create` 去重（全局 `requestId` 索引无法只作用于视图聚合，用户 2026-09-29 接受），**WowViewStore** 在同一个实例里先问重放路由再重发（第 6 节「创建的重放」），一致性用例用的正是同一个实例，于是声明 `true`；其余写入（保存、改名、删除、改受众、偏好）的重放照考。
- **删除一个被共享看板引用的视图**保持允许，与今天的引擎一致（那块面板按 A 只坏自己）；只拒绝「改成个人」。

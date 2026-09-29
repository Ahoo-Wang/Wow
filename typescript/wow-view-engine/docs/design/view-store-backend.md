# Wow 存储后端：`ViewStore` 的服务端（阶段 6）

**状态**：方案，待用户确认（2026-09-29）。第 4 节是隔离与路径，第 5 节是聚合根，第 6 节是客户端与端口；两节的代码是拟定的形状，名字以实现为准。裁定见 [D75](decisions.md#d75-首发前做-wow-存储后端用真服务端验证-viewstore2026-09-29)。

## 1. 为什么首发前做

`ViewStore` 是引擎唯一的持久化端口（[management.md](management.md)「持久化端口与一致性」）。今天它只有两种实现：`MemoryViewStore` 与它的本地快照 `localStorageSnapshot`——都是单用户、单进程，没有经历过多用户、共享与个人的可见性、服务端鉴权、真实的并发冲突与超时重试。端口随首发公开，之后改它就是破坏性改动；用一个真服务端把它验一遍，发现要改就趁现在改。

Wow 自己做这个后端也最顺：端口的两条一致性规则在 Wow 里是现成的——期望版本对上聚合版本，`requestId` 去重对上命令幂等；租户、所有者、应用的隔离沿用 Wow 与 CoSec 的约定，服务端只定义路径规则，鉴权由 CoSec 安全网关负责。

## 2. 范围

| 做                                                                                             | 不做（首发后）                                           |
| ---------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| 端口的 8 个方法                                                                                | 订阅与告警、版本历史、验证、缓存（D22）                  |
| **就地改受众**：已有视图「设为共享／设为个人」（[D18](decisions.md) 第 10 条），端口加一个方法 | 图上的注释（[analysis-echarts.md](analysis-echarts.md)） |
| 服务端配置的系统视图（只读）                                                                   | 管理员维护系统视图的界面（由业务系统的管理入口负责）     |
| 租户、应用、所有者三维隔离                                                                     | 按 space 隔离（见 4.4）                                  |

**补偿控制台也迁移**：补偿服务引入 **wow-view-starter**，自动获得视图能力（第 3 节）；控制台不登录，由它自己插入一个 fetcher 请求拦截器，给租户、所有者、应用注入缺省值（fetcher-cosec 的拦截器只在请求没给时才填，控制台的缺省值因此生效）。它是视图后端的第一个真实宿主，与 `typescript/integration-test` 一起做验证（第 7 节）。

## 3. 模块

照补偿的样子在 Wow 仓加一组模块，放在 `view/` 下：

| 模块                                | 内容                                                                                                                                |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **wow-view-api**                    | 限界上下文、命令、事件、查询形状；生成 OpenAPI 的元数据                                                                             |
| **wow-view-domain**                 | 两个聚合                                                                                                                            |
| **wow-view-starter**                | Spring Boot 自动配置：业务服务引入它，就把视图的聚合、路由与系统视图接口装进自己（补偿服务、示例服务端都这样用）                    |
| **wow-view-server**                 | 独立的视图宿主服务：引入 starter 的一个 Spring Boot 应用（MongoDB 存事件与快照），给没有自己 Wow 服务、或想把视图集中存放的业务部署 |
| **@ahoo-wang/wow-view-store**（TS） | 由视图服务的 OpenAPI 生成的客户端，加手写的 **WowViewStore** 适配；随后端合同一起发布（management.md 末段）                         |

全部是新增模块。唯一的框架改动是前置的 **V0**（4.4）：没有开启 `spaced` 的聚合不写入、不过滤 spaceId。

## 4. 隔离与路径

### 4.1 四个维度

| 维度   | 来源                                                | 怎样隔离                                                        | 谁保证                         |
| ------ | --------------------------------------------------- | --------------------------------------------------------------- | ------------------------------ |
| 租户   | 路径 `tenant/{tenantId}`                            | Wow 的租户路由：聚合不设静态租户，路由都带租户前缀              | CoSec 按路径校验调用者的租户   |
| 所有者 | 路径 `owner/{ownerId}`                              | Wow 的所有者路由（聚合要求所有者）；**所有者段就是受众**（4.2） | CoSec 按路径校验               |
| 应用   | 请求头 `CoSec-App-Id`（fetcher-cosec 每个请求都带） | 创建时写进状态；查询按它过滤；命令的应用与状态不符读作不存在    | CoSec 认证应用；服务端按头隔离 |
| 空间   | —                                                   | **不隔离**（4.4）                                               | —                              |

服务端不做鉴权：谁能读写哪个路径，由 CoSec 安全网关按路径规则决定。服务端要做的是把路径规则定义清楚、把隔离维度落进数据与查询。

### 4.2 受众就是所有者段

| 受众 | 路径                                             | CoSec 的规则（示意）                          |
| ---- | ------------------------------------------------ | --------------------------------------------- |
| 个人 | `/view/tenant/{tenantId}/owner/{ownerId}/view/…` | 所有者段等于调用者本人（令牌的 `sub`）时放行  |
| 共享 | `/view/tenant/{tenantId}/owner/(shared)/view/…`  | 所有者段是保留值 `(shared)`：按角色或权限放行 |

- `(shared)` 是保留的所有者值：带括号，不会与任何用户 id 相同。
- **改受众 = 转移所有者**（Wow 的转移所有者事件），视图 id 不变，看板的引用不断。
- 不登录的宿主只用 `owner/(shared)`，没有个人视图。

### 4.3 路径一览

所有路径的前缀是 `/view/tenant/{tenantId}/owner/{ownerId}`，下表省略它。命令路由由 Wow 按聚合元数据生成；读的合并与偏好走自定义路由，同样带这个前缀。

| 方法与路径                                           | 端口方法                             | 说明                                                      |
| ---------------------------------------------------- | ------------------------------------ | --------------------------------------------------------- |
| `POST /view`（CreateView）                           | `create`                             | 聚合 id 由服务端生成                                      |
| `PUT /view/{id}/save`、`/rename`、`/audience`        | `save`、`rename`、**changeAudience** | 带期望版本                                                |
| `DELETE /view/{id}`                                  | `delete`                             | 带期望版本                                                |
| Wow 的快照查询路由（列表、按 id 读）                 | `list`、`get`                        | 摘要只投影不含 `config` 的字段；`kind` 取自 `config.kind` |
| `GET /system-views?definitionId=…`                   | `list` 的一部分                      | 服务端配置的系统视图（只读），只挂在 `owner/(shared)` 下  |
| `GET /view/requests/{requestId}`                     | 重放                                 | 这次写入落地时的实例；删除类答 204（第 6 节）             |
| `GET`、`PUT /definitions/{definitionId}/preferences` | `getPreferences`、`setPreferences`   | 偏好聚合的 id 由服务端按「所有者 × 定义」算出             |

每个写入都带 `Command-Request-Id`（端口的 `requestId`）与 `Command-Wait-Stage: SNAPSHOT`；修改类另带 `Command-Aggregate-Version`（端口的 `revision`）。**`Command-Request-Id` 必须显式给**：fetcher-cosec 每个请求都生成一个新的 `CoSec-Request-Id`，Wow 只在命令没有 requestId 时拿它来补，重试若不显式带同一个 requestId 就去不了重。

### 4.4 不按 space 隔离

视图是「怎么看」，不装数据；打开视图时的数据查询自带 space，数据的隔离在那里完成。视图若按 space 隔离，一个人管三家门店就要把同一个视图建三遍、总部的标准视图要每个 space 各一份、个人偏好换一个 space 就回到空白。以后真要「只在本团队内共享」，加一个受众即可：没带 space 的数据就在缺省的空 space，语义不变、不必迁移。

**V0（前置框架修复）**：今天 `@AggregateRoute(spaced)` 只影响 OpenAPI，运行时不看——命令提取一律把 space 头（`CoSec-Space-Id`、`Wow-Space-Id`）写进命令，查询范围一律按它过滤。按用户 2026-09-29 的裁定这是契约错误：没有开启 `spaced` 的聚合不写入、不过滤 spaceId。视图的两个聚合不开 `spaced`，便天然不按 space 隔离。

## 5. 聚合根

限界上下文 **view-service**（别名 **view**）。两个聚合根都要求所有者、不设静态租户、不开 `spaced`。

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

| 命令                                                   | 事件                                 | 规则                                                                                                                           |
| ------------------------------------------------------ | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| **CreateView** `{definitionId, title, config}`（创建） | **ViewCreated**                      | id 由服务端生成；应用取自 `CoSec-App-Id`（缺则拒绝）；受众由路径的所有者段推出                                                 |
| **SaveView** `{config}`                                | **ViewSaved**                        | 期望版本；应用与状态不符读作不存在                                                                                             |
| **RenameView** `{title}`                               | **ViewRenamed**                      | 同上；标题去空白后非空、不超过 120 字                                                                                          |
| **ChangeViewAudience** `{owner}`                       | **ViewAudienceChanged** + 转移所有者 | 同上；目标是 `(shared)` 或一个用户 id；**改成个人时，若被共享看板引用则拒绝**（**ViewInvalid**，带引用它的看板）；没变是空操作 |
| **DeleteView**（删除聚合）                             | **ViewDeleted**（聚合已删除）        | 同上；软删，之后读作不存在                                                                                                     |

- `config` 只做形状与大小的检查（是对象、`kind` 为记录／分析／仪表盘之一、不超过 256 KB）；语义由引擎打开时准入。
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

**SystemViewProvider**（配置文件或业务服务自己的实现）按租户、应用、定义给出只读的系统视图：id 不用 `system:` 前缀，revision 是内容的散列，`scope` 为 `system`。任何写入答「无权」。

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
  /** 不登录的宿主没有令牌，拦截器填不了租户，由这里给。 */
  tenantId?: string;
  /** 按钮是否可用。服务端不鉴权，宿主按自己在 CoSec 里的角色给；缺省全部允许。 */
  permissions?: (definitionId: string) => ViewPermissions;
}

export class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  // 端口的 8 个方法 + changeAudience + permissions
}
```

- **路径参数**：个人视图的 `{ownerId}` 留空，由 fetcher-cosec 按令牌的 `sub` 自动填成当前用户；共享视图显式填 `(shared)`（拦截器只填没给的参数）。没有令牌的宿主只用共享视图。
- **列表**：个人与共享各查一次，再合并系统视图。
- **按 id 读写要知道是个人还是共享**：端口只给 id。客户端记住列表、读取、创建时得到的「id → 受众」；没见过的 id 先按个人、再按共享各试一次。系统视图的 id 由系统视图接口给出，客户端认得。
- 写入：显式带 `Command-Request-Id` 与期望版本，等到 `SNAPSHOT`，成功后按 id 读回实例作答。
- **按 Wow 的错误码映射，不按 HTTP 状态**：

| Wow 错误码                                 | 端口                                                     |
| ------------------------------------------ | -------------------------------------------------------- |
| 期望版本冲突、事件版本冲突                 | `CONFLICT`（再读一次，填进 `instance` 或 `preferences`） |
| 重复的 `requestId`                         | 不是错误：**重放**                                       |
| 找不到、访问已删除的聚合、应用不符         | `NOT_FOUND`                                              |
| 未认证、所有者不符、CoSec 拒绝（401、403） | `FORBIDDEN`                                              |
| 校验失败、参数非法、领域的 **ViewInvalid** | `INVALID`                                                |
| 等待超时、限流、5xx、网络失败              | `UNAVAILABLE`（结局未知，重试复用同一个 `requestId`）    |

**重放**：端口要求重试答第一次的结果，Wow 对重复的 `requestId` 报错。聚合 id 由服务端生成，创建的重试会落到另一个新 id 上，所以视图上下文打开**全局的 `requestId` 唯一约束**（Wow 的 MongoDB 事件存储可选的唯一索引），重复的创建因此同样报「重复」。客户端收到「重复」时调 `GET /view/requests/{requestId}`：服务端按 `requestId` 在事件流里找到那次写入的聚合与版本，读回当时的实例作答（删除类答 204）。事件流查询不对外开放，因为事件里有别人个人视图的配置。

## 7. 验证

- **V0 的框架测试**：没开 `spaced` 的聚合带着 space 头（两种头都测）——命令不写入 spaceId、查询不加 space 过滤；开了的照旧。
- **端口一致性测试**：一套用例，`MemoryViewStore` 与 **WowViewStore** 都要通过。覆盖：列表只含所问定义、没有 `config`、没有保留前缀；个人与共享的可见；创建、保存、改名、删除、改受众；过期 revision 是 `CONFLICT`；系统视图不可写；重放答第一次的结果（包括别人在中间改过之后）；偏好从 `'0'` 起、按所有者分开；所有失败都是 `ViewStoreError`。revision 只比变与不变。
- **真服务端的端到端**：`typescript/integration-test` 连示例服务端（引入 **wow-view-starter**），MongoDB，在 CI 的同源契约任务里跑。示例服务端没有 CoSec 网关，端到端直接按路径扮演两个用户与共享，验证数据与行为；网关的鉴权规则不在本仓测试之内。
- **隔离测试**：两个租户、两个应用各存视图，互相读不到、改不到。
- **补偿控制台**：控制台换成 **WowViewStore**，由自己的拦截器注入缺省的租户、所有者、应用；它的端到端（`CI=1 pnpm test:browser`）走一遍保存、另存、共享与看板。
- **引擎端到端**：引擎配 **WowViewStore** 走一遍保存、另存、冲突后重载覆盖、结局未知后重试、设为共享。

## 8. 批次

| 批  | 内容                                                                                                                                                                     | 判据                                                                |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------- |
| V0  | 框架：没开 `spaced` 的聚合不写入、不过滤 spaceId（命令提取、查询范围、CoSec 的两处）                                                                                     | 框架测试两种头、两种聚合都覆盖；发布说明写明行为变化                |
| V1  | 后端：api、domain、**wow-view-starter**、**wow-view-server** 四个模块、两个聚合、系统视图接口、偏好与系统视图的自定义路由、重放端点、全局 `requestId` 唯一约束；领域测试 | 领域测试覆盖第 5 节每条规则；视图服务能起，OpenAPI 可生成           |
| V2  | 端口：**changeAudience** 与管理器入口；端口一致性测试套件（先让 `MemoryViewStore` 过）                                                                                   | 公开面快照更新；一致性套件在内存实现上全绿                          |
| V3  | TS：**@ahoo-wang/wow-view-store**；示例服务端与补偿服务引入 starter；补偿控制台迁移（自己的拦截器注入缺省租户、所有者、应用）；端到端与隔离测试                          | 一致性套件在 Wow 实现上全绿；端到端、隔离测试、引擎端到端在 CI 里过 |

V0 先行；V1、V2 文件不交叉，可以并行；V3 在三者之后。

## 9. 待定

- 一致性测试套件放在哪：`wow-view-engine` 的测试里导出给 `integration-test` 用，还是一个不发布的工作区包？不放进公开的 `/testing` 入口（会把测试框架带进发布的入口）。
- 删除一个被共享看板引用的视图：今天引擎允许（那块面板按 A 只坏自己）。本方案保持允许，只拒绝「改成个人」。

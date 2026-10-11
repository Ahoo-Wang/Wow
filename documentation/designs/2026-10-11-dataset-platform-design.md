# 数据集平台化设计

日期：2026-10-11。状态：方向已由用户确认（2026-10-11）：按 §3 的 R1～R5 推进，wow-bi 视图派生降级到 §8。§9 的决定按推荐采纳，各阶段实施前可再调整。R1a 已实施（Ahoo-Wang/Wow#4153）；R1b 已实施（Ahoo-Wang/Wow#4155），模块孵化中，随 R5 发布；R2 已实施（Ahoo-Wang/Wow#4156）；R3a 已实施（Ahoo-Wang/Wow#4159，`defineDataset`、`ViewEngine.register`，view-engine D84）；R3b 已实施（Ahoo-Wang/Wow#4160，`DatasetDirectoryClient`、`ViewEngine.unregister`、`DatasetResources`，view-engine D85；选择器即导航）；措辞覆盖的形状与套用已实施（Ahoo-Wang/Wow#4163，view-engine D86），存储留在 view-engine todo；注册表的端到端测试已补（Ahoo-Wang/Wow#4164，顺带修正 Mongo、Elasticsearch 自动配置的次序）；Skills 已补（Ahoo-Wang/Wow#4162）。R4 设计稿已写（Ahoo-Wang/Wow#4161）并经用户确认，§11 的 T1～T5 按推荐采纳（2026-10-11）；R4a-1（窗口的协议与核心）已实施（Ahoo-Wang/Wow#4169）；R4a-2（`PREVIOUS`）已实施（Ahoo-Wang/Wow#4170）；wow-client 的窗口已实施（Ahoo-Wang/Wow#4172）；R4b（ClickHouse 原生窗口）已实施（Ahoo-Wang/Wow#4173）；R4d 的算出的线已实施（view-engine D87，Ahoo-Wang/Wow#4175）。R4c（MongoDB 原生窗口，Elasticsearch 由查询服务算）已实施（Ahoo-Wang/Wow#4179）。R5 设计稿已写：[开箱即用的分析服务](2026-10-11-analytics-server-design.md)；R5 已实施：A1～A5 由用户确认（2026-10-11）；R5a（CoSec 的 ABAC 标签，Ahoo-Wang/Wow#4177）、R5b（分析服务，Ahoo-Wang/Wow#4182）、R5c（控制台，Ahoo-Wang/Wow#4183）、R5d（注册表模块发布、镜像与指南）。§11 的 T4、T6、T7 由用户按推荐确认（2026-10-11）：T4 不发布字段标题；T6、T7 见 view-engine D88、D89。

基线：`main` `ff0a57653`。本文接在[数据集查询与 ClickHouse 后端设计](2026-10-10-clickhouse-dataset-query-design.md)（下称“数据集设计”）之后，取代它 §10 中原定的 S4。view-engine 的原则与编号见 `typescript/wow-view-engine/docs/design/`。

## 1. 目标

支持数据集有三个目的：

1. 跨上下文、跨聚合分析，包括分析师在 ClickHouse 里加工的宽表；
2. 接入企业的 ClickHouse 数据仓库，充分利用已有数据；
3. 发挥 view-engine 的能力。

三者合起来的检验标准只有一条：**企业不需要再部署一套 BI 平台**。本文以此为准，逐项判断还缺什么。

## 2. 第一性原理：BI 平台提供了什么

| BI 平台的能力 | Wow 现状（S0～S3 与 Ahoo-Wang/Wow#4133 之后） | 缺口 |
|---|---|---|
| 连接数据仓库 | ClickHouse 后端 | 只能连一个 ClickHouse；数据集**必须有租户列**，而企业仓库的大多数表（ERP、CRM、日志）不分租户 |
| 把表注册为数据集 | 手写声明文件，随应用发布，重启生效 | **接一张表要一次研发发版**；在 BI 平台里，这是数据负责人几分钟的操作 |
| 浏览有哪些数据 | 无 | 没有数据集目录，前端必须事先知道 `context/dataset` |
| 在表上分析、建看板 | view-engine 的记录、分析、看板 | “定义是代码”：**每接一个数据集都要前端写定义并发版** |
| 分析函数 | 无服务端支持 | 趋势、累计在客户端对拿回的行计算，行被截断时会算错，所以置灰（view-engine Q53）；整段同比推迟（Q59） |
| 开箱即用 | 库与 starter | 企业要自己搭宿主应用，等于自己做一个 BI 平台 |

查询本身（数据集设计 S1～S3）已经具备；缺的是**数据集的生命周期**和**分析的表达力**。卡住目标的三处是：接表要发版、定义要发版、强制租户列。

由此得到两个推论：

- **数据集是数据，不是代码。** 企业有成百上千张表，由数据负责人维护，变更频率与应用发版无关。声明文件仍然保留，用于随代码发布的数据集（数据集设计 §4.2），但它不再是唯一的入口。
- **数据集的声明就是语义层。** 字段的标题、描述、枚举、日期语义、敏感等级都写在声明里，经能力描述发布。view-engine 不必再让人把它们写一遍：对数据集来说，“定义”可以从描述派生。

## 3. 路线

| 阶段 | 内容 | 对应目标 |
|---|---|---|
| **R1** | 数据集运行时注册与目录（§4） | 2 |
| **R2** | 企业数据的接入面：多个数据源、平台级数据集（§5） | 2 |
| **R3** | view-engine 免代码使用数据集（§6） | 3 |
| **R4** | 服务端分析函数（§7） | 1、3 |
| **R5** | 开箱即用的分析服务（§7） | 不另部署 BI |
| 之后 | wow-bi 视图派生、Map 标签列、删除标记、Skills（§8） | — |

R1 与 R3 决定整体形态，本文写到可实施的粒度；R2 写清约束；R4、R5 只定方向，各自另起设计。

## 4. R1：数据集运行时注册与目录

### 4.1 数据集注册表是一个 Wow 聚合

数据集的生命周期（起草、审核、发布、修改、下线）正是事件溯源擅长的事：谁在何时发布了哪个版本、把哪一列标为敏感，都是事件，天然就是审计记录。所以注册表做成一个 Wow 聚合，模块划分沿用 view-store：

| 模块 | 内容 |
|---|---|
| `wow-dataset-registry-api` | 限界上下文 `dataset-registry`、命令、事件、错误码 |
| `wow-dataset-registry-domain` | 聚合 `Dataset` 及其规则 |
| `wow-dataset-registry-starter` | 注册表的路由、把已发布数据集装进查询的装配 |

聚合 `Dataset` 的 id 是数据集名 `{context}.{dataset}`，状态是一份**草稿**加一份**已发布版本**：

| 命令 | 作用 | 规则 |
|---|---|---|
| `DraftDataset` | 从一张表起草：服务端读 `system.columns`，按适配器的类型族规则生成字段 | 名字不能与本服务的聚合或已有数据集冲突 |
| `ReviseDataset` | 修改草稿：字段、标题、范围、身份、事件时间、标签列、`FINAL` | 只改草稿，不影响已发布版本 |
| `ReviewSensitivity` | 逐列确认敏感等级（含“不敏感”） | 记录确认人 |
| `PublishDataset` | 把草稿发布为新版本 | 见 §4.2 的发布门槛 |
| `RetireDataset` | 下线 | 已发布版本停止服务；保留历史 |

**宿主**：注册表的宿主（承载 `Dataset` 聚合、接收命令的进程）与数据集查询的宿主（提供 `/dataset/...` 路由、订阅注册表事件维护本地投影的进程，§4.3）是两回事。注册表只是元数据的写入端，它的事件可以被任意服务订阅，所以放在哪里是部署组合问题，不是模块归属问题。`wow-dataset-registry-starter` 与 `wow-view-store-starter` 互相独立：只用 view-store 的项目不需要数据集，只用数据集的服务也不需要 view-store。

注册表**不放进 `wow-view-store-server`**：

| 考量 | view-store-server 现在 | 加入注册表之后 |
|---|---|---|
| 职责 | 保存视图与偏好，属于界面元数据 | 还要连接企业数据仓库、管理数据目录 |
| 依赖 | MongoDB | 还要 ClickHouse 与仓库的读权限凭据 |
| 安全面 | 不做认证、信任网关；泄露的是视图配置 | 同样信任网关，但进程握有仓库凭据；绕过网关的代价变成读到仓库数据 |
| 使用者 | 所有使用 view-engine 的项目 | 只分析聚合、不用 ClickHouse 的项目被迫带上用不到的能力与风险 |

即使用开关默认关闭，“view-store”的职责与安全假设也会被悄悄改变。注册表由 R5 的分析服务承载（§7），也可以嵌入任何需要它的业务服务。

### 4.2 发布门槛

发布前，草稿必须同时满足：

1. **范围已声明**：要么绑定租户列，要么显式声明为平台级数据集（§5.2）。没有默认值。
2. **敏感等级已逐列确认**：每个字符串列都经过 `ReviewSensitivity`，“不敏感”也必须是确认过的结论。未确认的列阻止发布，而不是默认当作不敏感。这一条落实了数据集设计 §4.2 的例外：敏感等级写在声明里，就必须有人对它负责。
3. **表结构一致**：草稿交给 ClickHouse 适配器做一致性检查（数据集设计 §5.2）并编译成功。发布动作本身就是一次编译，失败的草稿发布不了。

发布后，表结构若发生变化，Catalog 重新校验时会发现不一致：已发布版本暂停服务，并报告原因，直到发布一个修正后的版本。

### 4.3 查询端：动态数据集

数据集设计中，`QueryDatasets`、`QuerySchemaCatalog` 的数据集名单与 `DatasetQueryGateways` 都在启动时确定。R1 把它们改为可变：

- **`QueryDatasets` 成为注册表**：按名读写数据集。`add`、`replace`、`remove` 时通知监听者：Catalog 更新数据集名单并使对应的 schema 失效，网关随之增删。名字冲突的检查（数据集设计 Q5）在 `add` 时进行；监听者拒绝时，变更回滚。
- **`DatasetQueryGateways`**：在注册表变化时增删网关。HTTP 路由每次请求都按名查找网关（`DatasetQueryHandlerFunction`），所以不需要改；它按名缓存的 handler 要在数据集替换时一并失效。
- **来源**：声明文件中的数据集在启动时加入；注册表中的已发布版本由注册表的投影加入。每个查询实例在启动时、之后每隔 `wow.dataset-registry.refresh-interval`（默认 30 秒）读取已发布版本的快照，增删替换数据集。不订阅事件：事件处理器按消费组分摊，一个实例处理的事件其他实例收不到；读快照让每个实例都收敛到同一组已发布版本，代价是最长一个间隔的延迟。名字相同时，声明文件优先，注册表中的同名数据集无法发布（Q2）。

### 4.4 数据集目录

`GET /dataset` 返回本服务提供的数据集：名字、标题、描述、来源（文件或注册表）和描述版本。view-engine 的数据集选择器（§6）和 `wow-data-query`（§8）都读它。

**访问控制分三层，目录不新增权限模型**：

| 层 | 由谁负责 |
|---|---|
| 能否访问某个数据集 | 网关按路径授权（如 CoSec 对 `/dataset/{context}/{dataset}/**` 的规则），与 view-store 相同 |
| 能看哪些行 | 范围列、`QueryPolicy`、ABAC 标签列 |
| 能看哪些列的原值 | 敏感等级 |

目录本身会暴露数据集是否存在；需要隐藏的部署，用网关规则限制 `GET /dataset`。

### 4.5 租户与 ABAC

数据集的范围与 ABAC 和聚合走同一条准入管道、同一套 `QueryPolicy`，差别只在调用方范围从哪里读、资源标签存在哪一列：

| 层 | 聚合 | 数据集 |
|---|---|---|
| 调用方范围 | `QueryRequestScope` 加 `ScopeContributor` | `DatasetQueryRequestScope`：租户取 `/tenant/{tenantId}`，再取 `Command-Tenant-Id`；owner、space 取请求头，只在声明了对应列时生效 |
| 范围成为安全边界 | 覆盖 provenance 为 `AUTHENTICATED`，并开启 `wow.query.require-authenticated-scope` | 相同：可信网关（CoSec）校验租户后，注册返回 `AUTHENTICATED` 的 `DatasetQueryRequestScope` |
| 范围落到哪里 | 系统字段 | 声明的范围列；平台级数据集只属于平台租户（§5.2） |
| 主体标签 | 应用实现 `AbacQueryPolicy.getPrincipalTags` | 同一个策略 Bean（Ahoo-Wang/Wow#4133） |
| 资源标签 | Snapshot 的 `tags` 系统字段 | `scope.tags` 绑定的列，由宽表加工时填入；标签键必须与主体标签同一套词汇 |

两个缺口：

1. **`ScopeContributor` 不作用于数据集路由**。它的参数是 `AggregateMetadata`，数据集没有。用它追加范围的应用，到了数据集路由就失去这一维。R1a 给 `ScopeContributor` 增加数据集的入口（缺省不贡献），数据集路由像聚合路由一样组合它们：贡献者对数据集是否生效由它自己显式决定，因为数据集的列与聚合不同。
2. **没有开箱即用的主体标签来源**。每个应用都要自己实现 `getPrincipalTags`。R5 的分析服务提供基于 CoSec 的默认实现，从令牌或主体属性中取标签；CoSec 能提供哪些属性在 R5 设计时确认。

## 5. R2：企业数据的接入面

### 5.1 多个数据源

`wow.query.clickhouse` 增加命名数据源：

```yaml
wow:
  query:
    clickhouse:
      sources:
        warehouse: { endpoints: [ http://dw:8123 ], username: wow_reader, database: dw }
        bi: { endpoints: [ http://bi:8123 ], username: wow_reader, database: bi_db }
```

数据集的 `storage` 增加 `source` 键，省略时使用默认数据源，所以已有配置不受影响。每个数据源使用自己的连接；查询限额（数据集设计 §5.4）仍按入口统一配置，作用于所有数据源，需要时再按数据源细分。注册表起草时可以指定数据源（`DraftDataset.source`），草稿的 `storage.source` 随之写入。

### 5.2 平台级数据集

企业仓库里的表大多不分租户。数据集设计要求 `scope.tenant` 必填，这样的表就无法接入。为它们提供显式的平台级声明：

```json
"scope": { "platform": true }
```

语义：数据集只属于平台租户 `(0)`（`TenantId.DEFAULT_TENANT_ID`），而不是对所有租户开放。

- 调用方范围中的租户是平台租户时，租户条件恒真；是其他租户时恒假，查询返回空结果；
- 单租户部署本来就只有平台租户，因此平台级数据集对它直接可用；多租户 SaaS 中，租户用户看不到平台数据，不会因为一张表没有租户列而泄露；
- `wow.query.require-authenticated-scope` 照常要求已认证的租户；
- owner、space、tags 照常可以绑定，行级控制交给它们与 `QueryPolicy`。

`platform` 与 `tenant` 互斥，二者必须恰好声明一个。

## 6. R3：view-engine 免代码使用数据集

### 6.1 放宽“定义是代码”，只放宽到数据集

view-engine 的原则是“定义是代码”：字段、类型、操作符来自聚合的查询 schema，随应用部署。这对聚合成立：聚合本身就是代码，定义和聚合一起发版。对数据集不成立：数据集在运行时发布，再要求前端写定义、发版，R1 就失去了意义。

所以对数据集，**定义从描述派生**；对聚合，原则不变。派生的依据是数据集的声明本身（§2 的第二个推论），不在前端另立一份语义。

### 6.2 派生定义

`defineDataset(descriptor, { id, source, title })`（view-engine D84，实施时由 `deriveDatasetDefinition` 改名，与 `defineView` 同族）：

| 定义的内容 | 来自描述的 |
|---|---|
| 定义 id | 数据集名 `{context}.{dataset}`（定义 id 不能含 `:`，它是系统视图 id 的分隔符） |
| 字段与它们的词 | 每个字段的 `description`、枚举描述；没有描述时用字段名（描述符不发布字段标题） |
| 字段的 kind | 类型、日期语义、枚举与数组 |
| 记录视图 | `rowKey` 取 `identity`；没有 `identity` 时不提供游标分页 |
| 默认列 | 全部可投影的字段；敏感字段照常遮挡 |
| 分析能力 | 描述的 `analysis` 原样作为上界 |

派生的结果是一份普通的定义，准入（`admit`）照常检查它；之后的视图、看板与聚合上的视图没有区别。

需要调整措辞或默认列时，可以在**存储里**为数据集保存一份覆盖（标题、字段顺序、默认列、默认视图），存法与系统视图相同（view-engine management.md）。覆盖只能在派生结果的范围内收窄、改词，不能增加描述里没有的字段或能力。覆盖不在 R3a，记在 view-engine 的 todo.md。

### 6.3 运行时资源

当前 `ViewEngineOptions.resources` 是启动时给定的只读数组。R3 增加运行时登记（实施时由「打开时按需解析」改定，view-engine D84）：

- `engine.register({ definition, source })`：加入一份资源，同 id 的整份替换，与构造时一样准入、一样报问题。宿主读目录（§4.4）、取每个数据集的描述符、逐个登记，`source` 是 `DatasetQueryClient`；
- 已保存的视图与看板按定义 id 引用数据集；宿主还没登记时打开，按“定义不在”的现有规则报告（`view.definition.not-found`），与拼错的 id 同一条路；
- 不在打开路径上异步解析：打开路径保持同步，读目录的时机由宿主决定。

### 6.4 数据集选择器

宿主新增一个入口：从目录（§4.4）中选一个数据集，新建记录视图或分析视图。这是“建一个新报表”的起点，取代了“先写定义再发版”。

## 7. R4、R5：方向

**R4 服务端分析函数**（设计稿：[聚合查询的窗口计算设计](2026-10-11-aggregation-window-design.md)）：同比、环比、占比、累计、组内 TopN 进入聚合查询协议。规范只定义一次，各后端经 `StorageSupport` 声明 NATIVE、RESIDUAL 或 NONE；ClickHouse 用窗口函数原生执行。view-engine 改用服务端结果：累计与趋势不再受行截断影响（解除 Q53 的置灰），整段对比一次查询完成（落实 Q59）。另起设计。

**R5 开箱即用的分析服务**（含 §4.5 缺口 2 的 CoSec 主体标签）：新增一个独立的部署物（名字待定，如 `wow-analytics-server`），组合 `wow-view-store-starter`、`wow-dataset-registry-starter` 与 ClickHouse 数据集查询，并提供一个控制台界面（宿主，含 §6.4 的选择器）。企业部署这一个服务，接上自己的 ClickHouse 和 CoSec，就有完整的分析能力。它与 `wow-view-store-server` 共享 starter、不共享部署物：

| 部署物 | 组成 | 给谁用 |
|---|---|---|
| `wow-view-store-server`（不变） | view-store | 只需要保存视图的项目 |
| 分析服务（R5） | view-store、数据集注册表、ClickHouse 数据集查询、控制台 | 不再另部署 BI 平台的企业 |
| 任意业务服务 | 按需嵌入上述 starter | 需要在自己服务里提供数据集的团队 |

另起设计。

## 8. 降级与后续

- **wow-bi 视图派生数据集**：单聚合的数据，Snapshot 查询已能分析，对目标 1 没有直接贡献；它们的价值在于给宽表当原料，而宽表加工发生在 ClickHouse 里。R1 之后，wow-bi 视图可以像任何表一样起草、发布；需要时再做自动派生。
- **Map 标签列、删除标记**：只服务于 wow-bi 视图，随上一项一起做。
- **Skills**（已实施：`wow-data-query` 覆盖数据集，`wow-client`、`wow-view-host` 补充数据集的用法）：按职责划分，最有价值的是把 **`wow-data-query` 扩展到数据集**：Agent 读目录（§4.4）与数据集描述，回答业务问题，即“AI 分析师”。这直接对应 BI 平台的即席分析，而且 Skill 已经存在，改动小，可以在 R1 之后随时做。帮人写声明的 Skill 在 R1 之后不再需要。

## 9. 决定

| # | 问题 | 采纳 |
|---|---|---|
| Q1 | 数据集注册表的形态 | Wow 聚合 `Dataset`，事件即审计；模块划分沿用 view-store（§4.1） |
| Q2 | 文件与注册表同名 | 声明文件优先，注册表中的同名数据集不能发布（§4.3） |
| Q3 | 敏感等级 | 每个字符串列都必须经确认才能发布，“不敏感”也要确认（§4.2） |
| Q4 | 数据集访问控制 | 网关按路径授权；行由范围与策略控制，列由敏感等级控制；目录不新增权限模型（§4.4） |
| Q5 | 无租户的表 | 显式的平台级数据集，只属于平台租户 `(0)`，与 `tenant` 互斥（§5.2） |
| Q6 | view-engine 与“定义是代码” | 只对数据集放宽：定义从描述派生，存储中的覆盖只能收窄与改词（§6） |
| Q7 | 运行时资源 | `resolveResource(id)` 按需解析，失败按“定义不在”报告（§6.3） |
| Q8 | 注册表的宿主 | 不放进 `wow-view-store-server`；由 R5 新增的分析服务组合 view-store 与注册表的 starter，也可嵌入任意业务服务（§4.1、§7） |
| Q9 | 数据集路由上的范围贡献者 | `ScopeContributor` 增加数据集入口，缺省不贡献，由贡献者显式决定（§4.5），随 R1a 实施 |
| Q10 | 主体标签来源 | R5 提供基于 CoSec 的 `AbacQueryPolicy` 默认实现（§4.5） |

## 10. 实施阶段

每个阶段一个或数个 PR。

| 阶段 | 内容 | 证明 |
|---|---|---|
| **R1a 动态数据集** | `QueryDatasets` 可变；Catalog、网关、路由缓存随它变化；`GET /dataset` 目录；数据集路由组合 `ScopeContributor`（§4.5） | wow-query 单测；starter 测试：运行时加入、替换、移除数据集，路由立即生效 |
| **R1b 注册表** | `dataset-registry` 的 api、domain、starter；起草（读 `system.columns`）、审核、发布、下线；发布门槛；投影进查询。不依赖分析服务：以 starter 测试验证，需要真实运行时时嵌入示例应用 | 聚合规格测试（发布门槛的每一条）；ClickHouse 集成测试：起草到发布到查询 |
| **R2** | 命名数据源；平台级数据集 | 单测与集成测试：两个数据源；平台租户与其他租户的结果 |
| **R3a 派生定义** | `deriveDatasetDefinition`；存储中的覆盖 | view-engine 单测：派生结果过 `admit`；覆盖只能收窄 |
| **R3b 运行时资源** | `resolveResource`；数据集选择器 | view-engine 单测与 Storybook 故事；控制台端到端 |
| **R4、R5** | 各自另起设计 | — |

## 11. 待用户讨论

用户 2026-10-11 授权自主推进，需要用户决策的事项记在这里，回来后讨论。每项给出推荐与理由；未决前按推荐的默认推进或暂缓，已注明。T1～T5 已由用户按推荐采纳（2026-10-11，「按你推荐，开始 R4a」）；T4 在实施前复核时发现与 9.2 的决定相悖，改推荐为不发布，待用户确认。

| # | 问题 | 推荐 | 未决前 |
|---|---|---|---|
| T1 | R4 服务端分析函数的范围与协议形状（设计稿：[聚合查询的窗口计算设计](2026-10-11-aggregation-window-design.md)，其 §8 列出 W1～W5）：同比、环比、占比、累计、组内 TopN 是做成聚合查询里的“派生指标”（指标引用另一个指标加窗口），还是独立的“后处理”阶段 | 派生指标：与现有 `metrics`、`having`、`sort` 同一层，能力描述按函数逐个声明 NATIVE/RESIDUAL/NONE，view-engine 的累计、趋势、整段对比直接改用；后处理阶段会让排序与截断的语义再分一层 | R4 设计记录已写，不动代码 |
| T2 | R5 分析服务的形态：新的独立部署物（工作名 `wow-analytics-server`）是否带控制台界面；控制台复用补偿控制台的宿主骨架，还是新建 | 独立部署物，带控制台；控制台新建在 `typescript/` 下，复用 view-engine 与 view-store 的现成宿主接线（与补偿控制台同样的 `ViewHost` 写法），不复用补偿控制台的业务页面 | 暂缓，先完成 R4 设计 |
| T3 | 主体标签的 CoSec 默认实现（§4.5 缺口 2）：从 CoSec 令牌的哪些 claim 或主体属性取标签 | 读 CoSec 主体的 `attributes` 中约定前缀（如 `abac.`）的键，值按逗号拆成列表；需要确认 CoSec 现有令牌里有没有这类属性 | 暂缓，随 R5 |
| T4 | 能力描述是否发布字段标题（`title`）：派生定义目前用 `description` 作口径 | **改推荐为不发布**（2026-10-11 实施前复核）：9.2 的声明迁移有意删掉了 `title`，理由是「显示名属于视图定义」（查询模型 schema 文档的迁移表）；读者的词已由 view-engine D86 的措辞覆盖承担，存进视图存储、由管理员编辑是它的下一步。发布 `title` 等于推翻 9.2 的决定，需用户确认 | 用户确认不发布（2026-10-11） |
| T5 | 数据集注册表模块的发布时机：现为孵化（不发布） | 随 R5 一起发布（与设计一致）；若有企业要先在自己的服务里嵌入注册表，可提前到下一个次版本 | 保持孵化 |
| T6 | 与上一期、去年同期的整段对比（view-engine Q59）用 `PREVIOUS` 后的形态 | 时间序列图的选项「对比：上一期／去年同期」：画一条同色虚线（上一期的值），提示框写较之变化的百分比；指标卡的「较上一期」在能力列出 `PREVIOUS` 时改读这一项，不再发第二条查询。能力没有 `PREVIOUS` 时不给这个选项 | 用户确认（2026-10-11），view-engine D88 |
| T7 | 组内 TopN（「每个省 GMV 前三的城市」）在分析编辑器里的形态 | 有两个及以上维度时，「只保留」旁多一个「每个〈外层维度〉只留前 N 个〈内层维度〉（按〈指标〉）」：编译成 `RANK(指标, within 外层维度)` 的隐藏派生指标加 `having rank ≤ N`；能力没有 `RANK` 时不给 | 用户确认（2026-10-11），view-engine D89 |


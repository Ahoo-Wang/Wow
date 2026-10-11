# 开箱即用的分析服务（数据集平台 R5）

日期：2026-10-11。状态：设计稿。§8 的决定待用户确认；未决前按推荐推进 R5a，其余阶段等确认。

基线：`main` `8a447298e9`。本文是[数据集平台化设计](2026-10-11-dataset-platform-design.md)的 R5（其 §7 与 §11 的 T2、T3、T5），建立在 R1～R4 之上：数据集注册表（`wow-dataset-registry-*`）、ClickHouse 数据集查询与命名数据源、view-engine 的数据集派生与登记（D84～D86），以及聚合窗口（R4，view-engine D87）。

## 1. 问题

R1～R4 做完以后，企业要拿到 BI 能力，仍要自己组装一个服务：

- 选 starter：`wow-view-store-starter`、`wow-dataset-registry-starter`，加上 ClickHouse 数据集查询，以及事件存储、总线和 CoSid；
- 自己实现 `AbacQueryPolicy.getPrincipalTags`，否则 ABAC 形同虚设（平台设计 §4.5 缺口 2）；
- 自己写一个宿主页面，接 view-engine、view-store、数据集目录与注册表命令。

目标「企业不需要额外再部署一套 BI 平台」要求的是**一个部署物**：接上自己的 ClickHouse 和 CoSec，就有数据集目录、分析视图、看板、数据集的发布与审核。

## 2. 原则

1. **组合，不新造**：服务只组合已有的 starter 与 view-engine，不加新的领域概念。新代码只有部署物的接线、CoSec 的 ABAC 默认实现和控制台。
2. **与 `wow-view-store-server` 共享 starter，不共享部署物**：只要视图存储的团队照旧部署 view-store-server；要 BI 的团队部署分析服务（平台设计 §7 的表）。
3. **安全默认收紧**：分析服务面向人读数据，HTTP 查询默认要求主体带 ABAC 标签（`requirePrincipalTags = true`），注册表命令只给管理员角色。
4. **控制台是宿主，不是第二个引擎**：页面全部由 view-engine 与 view-store 的现成组件画，控制台只做导航、登录与数据集管理。

## 3. 部署物

新增 Gradle 模块 `wow-analytics-server`（目录 `analytics/wow-analytics-server`），依赖：

| 依赖 | 作用 |
|---|---|
| `wow-view-store-starter` | 视图、看板的存储与路由 |
| `wow-dataset-registry-starter` | 数据集的草稿、审核、发布与目录投影 |
| `wow-clickhouse` | ClickHouse 数据集查询与命名数据源 `wow.query.clickhouse.sources.*` |
| `wow-mongo` | view-store 与注册表两个聚合的事件存储与快照 |
| `wow-kafka` | 命令、事件、状态总线（多实例）；单实例可改内存总线 |
| `wow-cosec` 与 CoSec 的 `cosec-spring-boot-starter`、`cosec-webflux` | 认证、授权与 ABAC 标签（§4） |
| CoSid | 聚合 id |

发行包与 `wow-compensation-server` 相同：`application` 插件、`config/application.yaml`、控制台的构建产物打进 `ui/`，由 WebFlux 作静态资源服务。Docker 镜像与现有的服务镜像同一套工作流。

## 4. 身份与 ABAC

### 4.1 CoSec 的主体标签（T3）

CoSec 的主体（`me.ahoo.cosec.api.principal.CoSecPrincipal`）带 `attributes: Map<String, Any>`，WebFlux 下由 `ReactiveSecurityContexts` 放在 Reactor 上下文里。`wow-cosec` 新增 `CoSecAbacQueryPolicy`：

- 读主体属性里**约定前缀**的键，缺省前缀 `abac.`：`abac.region = "east,north"` 成为标签 `region → [east, north]`；值是字符串时按逗号拆分，是集合时逐项取字符串；
- 前缀可配：`wow.cosec.abac.attribute-prefix`；
- 匿名主体没有标签；
- 只在应用引入 CoSec 时装配（`@ConditionalOnClass`），应用自己声明了 `AbacQueryPolicy` 时让位。

这是 `wow-cosec` 第一次依赖 CoSec 本身（今天它只读 CoSec 的请求头名），所以依赖为 `compileOnly`，由宿主带入 CoSec。

### 4.2 授权

| 路由 | 默认策略 |
|---|---|
| 数据集查询、目录 | 已认证；ABAC 标签与租户由查询网关收窄 |
| 视图存储 | 已认证；个人视图只给作者，共享视图按 view-store 现有规则 |
| 注册表命令（起草、修订、审核、发布、下线） | 角色 `analytics-admin`（可配） |
| 控制台静态资源、健康检查 | 匿名 |

默认策略写在发行包的 CoSec 策略文件里，企业按自己的角色名改。

## 5. 控制台

新建 Vite + React 应用（目录 `analytics/console`，加入 pnpm workspace），依赖 `wow-client`、`wow-react`、`wow-view-engine`、`wow-view-store` 与 `@ahoo-wang/fetcher-cosec`：

| 页面 | 内容 |
|---|---|
| 登录 | CoSec 令牌；`fetcher-cosec` 拦截器带令牌、续期 |
| 导航 | `DatasetResources` 按目录登记的数据集，加上视图存储里的视图与看板（D85：选数据集就是打开它） |
| 视图与看板 | view-engine 的工作台与看板，存进 view-store |
| 数据集管理（管理员） | 注册表的草稿列表、从 ClickHouse 表起草（`ClickHouseDatasetDrafts`）、审核敏感字段、发布与下线 |

宿主接线照补偿控制台的 `ConsoleHost`：一个 `ViewHost`，一个 `ViewEngine`，`DatasetResources.refresh` 在启动和打开导航时调用。

## 6. 发布

- `wow-dataset-registry-api`、`-domain`、`-starter` 随 R5 结束孵化，加入发布列表与 BOM（T5）；
- `wow-analytics-server` 与控制台不发布到 Maven、npm，只发行包与 Docker 镜像，与补偿服务相同。

## 7. 实施阶段

| 阶段 | 内容 | 证明 |
|---|---|---|
| **R5a** | `wow-cosec` 的 `CoSecAbacQueryPolicy` 与配置 | 单测：属性前缀、拆分、匿名、让位 |
| **R5b** | `wow-analytics-server` 部署物：依赖、配置、CoSec 策略、静态资源 | 启动测试；Testcontainers 端到端（Mongo、ClickHouse）：发布一个数据集、按 ABAC 查询 |
| **R5c** | 控制台：登录、导航、视图与看板、数据集管理 | 单测与 Playwright 冒烟 |
| **R5d** | 注册表模块发布、Docker 镜像、部署文档与 skill | CI 发布演练 |

## 8. 决定（待用户确认）

| # | 问题 | 推荐 |
|---|---|---|
| A1 | 部署物与控制台放在哪里 | `analytics/wow-analytics-server` 与 `analytics/console`，与 `compensation/` 同构。T2 曾写「控制台新建在 `typescript/` 下」，但 `typescript/` 今天只放发布的包（API 报告、surface 清单、发布工作流都按目录遍历），应用放进去要为它开例外 |
| A2 | 总线的缺省 | Kafka，与 view-store-server 一致；发行包的配置注释写明单实例可改内存总线 |
| A3 | HTTP 查询是否默认要求 ABAC 标签 | 要求（`requirePrincipalTags = true`）：分析服务面向人读数据，没有标签的主体能读全部数据是一个静默的越权；不需要 ABAC 的企业在配置里关掉 |
| A4 | `wow-cosec` 依赖 CoSec 的方式 | `compileOnly` 加条件装配：不用 CoSec 的应用不被带入 CoSec |
| A5 | 管理员角色名 | `analytics-admin`，可配 |

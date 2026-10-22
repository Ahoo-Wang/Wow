# V10 文档重写：信息架构方案

日期：2026-10-20。基线：`db3155777d`。状态：**已完成**（2026-10-22：W0、W1 A～K、补缺 L～O 已合并，站点已上线；GA 前的缺页已由 L～O 组补齐）。下文是当时的方案与实施记录，其中的 `guide/v10/`、`v10` 分支等名称是方案时的状态。范围：`documentation/docs/{en,zh}`（VitePress 站）、`README(.zh-CN).md`、`CONTRIBUTING.md`、`skills/**`。本方案只读仓库，没有写任何页面。

依据：`documentation/designs/**`（00～09、decisions D1～D266、roadmap 9.1 / SK-1 / H-1 / CI-2 / CI-3 / N-1、reviews）、现有 `guide/v10/` 九页草稿、`example/quickstart` 与 eShop、`documentation/test/*`、`.github/scripts/docs-layout.mjs`、`skills/README.md`，以及 R5 残留审查（R21～R25、R28）。

---

## 0. 结论先行

1. **网站按读者的“工作”分七个区**：入门 `/start/`、概念 `/concepts/`、开发指南 `/guide/`、TypeScript `/typescript/`、运维 `/operate/`、数据 `/data/`、从 9.x 迁移 `/migrate/`，另有参考 `/reference/` 和贡献 `/contribute/`。Diátaxis 只在三处严格使用：入门是教程，概念是解释，参考是查表。其余各区都是 how-to，按读者的工作组织，不按 Diátaxis 类型再切一层。
2. **手写约 100 页（每种语言）**：P0（rc 前必须有）54 页，P1（GA 前）37 页，P2（GA 后可补）9 页。另有 **6 类生成页**：配置项、错误码、注解、模块与坐标（P0），Manifest schema、TS 符号索引（每个包一页，P1）；API 稳定性页里的实验性 API 清单也是生成的。生成器与检查是代码，交给代码会话，见 §6 的 DI-1～DI-6。
3. **样例全部来自已编译的源码**：Kotlin 用已有的 `<!-- sample: <path> -->` 逐字相等机制（`QuickstartPageTest`），推广成一条覆盖全站、README 和 Skills 的 Node 测试；源码来自 `example/quickstart`、eShop 和新增的 `#region`。TS 沿用 `typescript-samples.mjs`。新增四项检查：样例一致、中英结构对齐、YAML 里的 `wow.*` 键必须存在、Skills 里的 Kotlin import 必须在 ABI 转储里。
4. **Skills 保留六个**：wow-develop、wow-client、wow-view-definition、wow-view-host、wow-data-query、wow-migrate。前五个按 D266 ③ 改写为只教 V10；wow-migrate 收窄为 9.x→V10（**需要用户确认**，因为 D266 ③ 写的是“不受影响”），并接手 TS 侧的 9.x→V10 迁移。GA 不新增 Skill（D255 冻结范围），`wow-operate` 列为 10.x 候选。
5. **9.x 页面全部删除**，不留存档目录。旧链接的处理：一张有上限的跳转表，构建时生成静态跳转页；没有映射的路径落到 404 页，引导读者去 9.x 文档。9.x 文档改由 `v9` 分支构建并部署到 `/9.x/`。**这一条改的是 CI/CD，需要用户批准**（见 §8）。
6. **冻结前就能开写的部分**：概念区全部、术语表、协议参考、迁移的总览 / 数据 / 能力对照、视图引擎八页、运维的部署模型 / 安全 / 控制台，以及全部生成器和检查（DI-*）。

---

## 1. 读者与他们的工作

| # | 读者 | 带着的问题 | 最重要的工作 | 主要落点 |
|---|---|---|---|---|
| R1 | **评估者**（新人、技术负责人） | Wow 解决什么？代价是什么？跟 Axon 或自己写 ES 比有什么不同？能不能上生产？要哪些基础设施？ | 30 分钟内判断“值得试”；跑通一个服务；看懂架构和取舍 | 首页、`start/why-wow`、`start/quickstart`、`concepts/overview`、README |
| R2 | **应用开发者**（Kotlin/Java 服务） | 聚合怎么写？拒绝和错误码怎么写？Saga 怎么调用另一个上下文？投影失败了怎么办？怎么查询？怎么测试？Spring Boot 要配什么？生成了哪些代码？ | 建模 → 处理流程 → 读 → 跨上下文 → 测试 → 接到宿主 | `/guide/`、`/concepts/`、`/reference/`（注解、错误码、配置） |
| R3 | **TS 前端开发者** | 客户端怎么生成？命令发出后怎么等到可读？查询 DSL 怎么用？React hooks 有哪些？视图引擎怎么定义视图、怎么接入宿主？ | 生成客户端 → 命令与等待 → 查询 → React → 视图引擎 | `/typescript/`、TS 参考 |
| R4 | **运维** | 需要哪些组件？怎么部署到 K8s？健康和就绪看什么？指标有哪些？怎么扩容、怎么设分区数？怎么滚动升级？聚合被停放了怎么处理？怎么重建读模型？ | 部署 → 配置 → 观测 → 扩容 → 故障处置 → 升级与恢复 | `/operate/`、配置与错误码参考、ops 控制台 |
| R5 | **数据分析师** | 有哪些数据可以查？宽表怎么定义、怎么发布？为什么查询返回 409 或 429？工作台怎么用？能不能让 Agent 帮忙回答？ | 发现数据 → 定义并发布数据集 → 查询与看板 | `/data/`、查询语言参考、wow-data-query |
| R6 | **9.x 迁移者** | 哪些能力变了、哪些没了？源码怎么改？数据怎么搬？怎么切换、怎么回滚？TS 客户端怎么改？配置键怎么对应？ | 评估差距 → 源码迁移（Skill）→ 数据导入与校验 → 蓝绿切换 | `/migrate/`、wow-migrate |
| R7 | **贡献者**（包括后端或扩展作者） | 仓库怎么组织？怎么构建和测试？设计决定记在哪？什么算破坏性变更？怎么写一个后端？文档和 Skill 改在哪？ | 上手构建 → 改代码时守住规则 → 提 PR | `CONTRIBUTING.md`、`/contribute/` |

工作台和 ops 控制台的业务用户分别并入 R5（工作台）和 R4（控制台），不单独设区。

---

## 2. 站点结构

### 2.1 URL 与导航

- 英文在根路径（沿用 `rewrites: en/ → /`），中文在 `/zh/`。两种语言的页面集合与结构必须一致（§5.3 的检查）。
- `guide/v10/` 提升为正式路径（D265 ②），站内不再出现 `v10` 字样。
- 顶部导航：入门 · 指南（下拉：开发 / TypeScript / 数据）· 运维 · 迁移 · 参考 · 概念。“贡献”放在页脚和 README，不进顶部导航。
- 每个区有自己的侧栏，每个区的 `index.md` 是该区的地图：按工作列出页面，每页一句话说明。
- Dokka（`/dokka/`，documentation-deploy 已经生成）在“参考”下拉里作为外链，范围限定为已发布的 V10 模块。

图例：**读者** R1～R7；**大小**按英文字数估，S ≤ 600，M 600～1500，L 1500～3000；**时机** S = 冻结前即可写，F = 冻结后写，L = 等在途工作合并后再写（括号里写等哪一项）；**优先级** P0 / P1 / P2。“代码”一列写页面描述或引用的源码。

### 2.2 入门 `/start/`（教程）

| 页 | 标题 | 读者 | 工作 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|---|---|
| start/index | Start here | R1 | 选一条路 | 按读者分的六条路径 | — | — | S | S | P0 |
| start/why-wow | Why Wow | R1 | 判断值不值得用 | 价值（写、传、用）、11 条公理的白话版、部署前提、什么时候不适合用、与 9.x 的差别一段 | 00 §1～3、§5、§8；D35、D226 | — | M | S | P0 |
| start/quickstart | Quickstart | R1、R2 | 30 分钟跑起一个服务 | Gradle 插件与 BOM、命令、事件、状态、聚合、测试、运行、curl 发命令、查快照 | guide/v10/quickstart；D29、D83、D87、D217 | `example/quickstart` 全部 | L | F（DX-1 改名字） | P0 |
| start/existing-project | Add Wow to an existing service | R2 | 在已有的 Spring Boot 应用里引入 | 插件或手动接 KSP、api/domain/server 三件套布局、上下文命名、宿主按类路径装配、选后端 | guide/v10/index；D44、D83、D87、D110 | `compiler/gradle-plugin`、`host/spring-boot`、eShop catalog 的 build 文件 | M | F | P0 |
| start/tutorial | Tutorial: two contexts end to end | R2、R3 | 从一个服务走到一个系统 | 基于 eShop 的 basket → ordering：聚合、跨上下文 Saga、投影、查询、TS 客户端、Web 界面。一页四节，不拆页 | eShop README；D158、D159、D204 | `example/eshop/{basket,ordering}`、`example/eshop/web` | L | F | P1 |
| start/skills | Agent Skills | R1～R6 | 安装插件、知道该用哪个 Skill | 安装方法、六个 Skill 的选择规则、9.x 用户去 `v9` 的插件 | skills/README | `skills/plugins.json` | S | L（SK-1） | P1 |

### 2.3 概念 `/concepts/`（解释；设计已经采纳，冻结前就能写）

| 页 | 标题 | 读者 | 范围 | 来源 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|
| concepts/index | Architecture overview | R1、R2、R4 | 五个平面一张图，写、传、用三条链路，每个平面链到下面的一页 | 00 §4 | M | S | P0 |
| concepts/programming-model | Deciding and evolving | R2 | 五类函数；decide 与 evolve 分离；可变与不可变状态；两种写法同一个模型；调用形态与快速路径；`@Blocking` | 01 §1～6；D7、D8、D9、D36、D37 | M | S | P0 |
| concepts/event-log | The log is the bus | R1、R2、R4 | 每个上下文一份日志、分区与位置、提交可见顺序、格式版本、为什么没有事件总线 | 03 §1～2、08 §4、§9；D10、D11、D59、D240、D250 | M | S | P0 |
| concepts/write-path | Writing: admission to append | R2、R4 | 命令生命周期、准入、亲和路由只是性能手段、靠版本唯一键保证正确、集群通道、去重（D261）、追加之后不可撤销 | 02；D46、D223、D261 | M | S | P0 |
| concepts/waits | Waiting for outcomes | R2、R3 | 等待阶段与链、默认 `SNAPSHOT`、写后即可查、跨上下文等待、信号发回发起节点 | 04 §6；D34、D35、D65、D69、D212 | M | S | P0 |
| concepts/subscriptions | Subscriptions, failures and replay | R2、R4 | 共享尾随、租约、检查点、按聚合停放失败、重放与回填、Sinks、背压不算系统性故障 | 04 §1～4；D243、D263 | M | S | P0 |
| concepts/read-models | Read models | R2、R5 | 快照就是默认读模型；三个查询模型（state、events、dataset）；可见性；读写隔离与舱壁 | 03 §3～4、05 §1；D231、D235、D247、D252、D254 | M | S | P0 |
| concepts/contract | One contract: the Manifest | R2、R3 | Manifest 是唯一来源；REST、OpenAPI、TS、视图、Skills 都是它的投影；基线与兼容；对端契约随 api 发布 | 05 §2～4；A9；D16、D83、D234、D256 | M | S | P0 |
| concepts/identity | Identity, tenancy and access | R2、R4 | `AggregateRef`、租户、owner、space、调用方、`app_id`、CoSec 授权、内部端口的信任边界 | A6；D226、D232、D236；P.10 | M | S | P0 |
| concepts/cross-context | Between contexts | R2 | 发布的事件流、Kafka、对端、同步调用 `Wow-Call`、跨上下文命令投递 | 04 §5；D33、D193、D234、D257 | M | S | P1 |

### 2.4 开发指南 `/guide/`（how-to，R2）

| 页 | 标题 | 工作 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| guide/index | Developer guide | 地图 | 按工作分组：建模、流程、读、跨上下文、契约、测试、宿主 | — | — | S | F | P0 |
| guide/aggregates | Write an aggregate | 建模 | 命令、状态、`@OnCommand`、创建、`DecideScope`、after 钩子、生命周期命令、Decider DSL 写法（同页一节） | guide/v10/programming-model；01 §3；D203 | `api/api` 注解；eShop catalog、basket 的 domain | L | F（DX-1） | P0 |
| guide/rejections | Rejections and error codes | 建模 | `@ErrorCode`、拒绝、violations、映射到 problem+json、错误码前缀推导 | D60、D121、D211、D256 | eShop ordering 的 domain | M | F（DX-1） | P0 |
| guide/event-evolution | Evolve events | 建模 | `@Revision`、`@Upgrade`、`@Alias`、退役事件 | 01 §8；D219 | `api/api` | M | S | P1 |
| guide/ownership | Owners, spaces and tags | 建模 | owner、space、租户、作用域继承、`@Tags` | P.7、P.10；D227 | eShop | M | F | P1 |
| guide/unique-keys | Unique keys across aggregates | 建模 | 预留键（取代 9.x 的 PrepareKey）、确认与释放 | 01 §7；D38、D225 | — | S | S | P1 |
| guide/sagas | Write a saga | 流程 | `@Saga`、派生命令、`@OnRejected`、同步调用、按事件类型的处理函数 | 04；D193、D264 | eShop ordering ↔ payment | L | F（DX-1d） | P0 |
| guide/projections | Projections and reactors | 流程 | `@Projection`、`@Reactor`、订阅起点、幂等、`StateTypedEvent` | 04 §1；D264 | eShop | M | F | P0 |
| guide/scheduling | Scheduled commands | 流程 | `@OnSchedule`、定时器、到期 | D197；6.8b | eShop | M | S | P1 |
| guide/failures | Failures and retries | 流程 | 失败台账、停放、`@Retry` 的可恢复分类、背压 | 04 §2；D243、D263；P.16 | `api/api` `Retry` | M | L（P.16） | P0 |
| guide/reading-state | Read state | 读 | `GET /state`、历史与轨迹、ETag、最小版本，Kotlin `QueryApi`、`trusted()` | D231、D246、D254；R4-Q1、R4-Q3 | `edge/http/read`、`api/api/read` | L | F | P0 |
| guide/queries | Query snapshots | 读 | 用 Kotlin DSL 写查询、`<State>Properties`、`@QueryIndex`、`@Sensitive` 脱敏 | guide/v10/query 的后半；D125 | `api/query-api` | M | F | P0 |
| guide/event-queries | Query event streams | 读 | events 模型、时间线、ABAC 下推 | D235、D254 ①；R4-Q2 | — | M | L（R4-Q2） | P1 |
| guide/datasets | Publish datasets from a service | 读 | 服务侧声明数据集（`META-INF/wow/dataset`）、行引用、由 catalog 发布 | D238、D251、D259 | eShop ordering 的 `sales.json` | M | L（7.2b/7.2c） | P1 |
| guide/published-streams | Publish and consume streams | 跨上下文 | 发布的事件流、类型化的对端事件（DX-1c） | D234、D259 | eShop | M | F | P0 |
| guide/calling-services | Call other services | 跨上下文 | 类型化的命令与查询客户端、原始命令对象、`Wow-Call` | D257、D258 | `edge/client` | M | F（CL1） | P0 |
| guide/http | Serve and call the HTTP API | 契约 | 服务暴露的路由、`Wow-Wait`、SSE、可续读的流；任务导向，细节表格放在 reference/http | guide/v10/http-api；D16、D30、D253 | `edge/http` | L | F | P0 |
| guide/manifest | The Manifest and compatibility | 契约 | 生成与合并、`manifest/baseline.json`、兼容检查、哪些改动会破坏客户端 | 05 §2；D83 | `example/eshop/wow-manifest-compatibility.gradle.kts` | M | F | P1 |
| guide/openapi | OpenAPI and springdoc | 契约 | Manifest → OpenAPI、springdoc 桥接、securitySchemes | D233 | `edge/openapi` | S | S | P1 |
| guide/generated-code | What the compiler generates | 契约 | 模型工厂、绑定、Properties、`ModelProvider`、Manifest 片段 | guide/v10/generated-code；D37 | `compiler/compiler` | M | F（DX-1） | P1 |
| guide/testing | Test domain code | 测试 | `aggregateSpec`、`sagaSpec`、`projectionSpec`、`reactorSpec`、fork、定时 | guide/v10/testing | `test/test-dsl`；quickstart 与 eShop 的 Spec | L | S（T1 已合并） | P0 |
| guide/testing-services | Test a service | 测试 | 绑定索引与片段的测试、服务端测试、`integrationTest` 源集、Testcontainers | DX-2（G17） | eShop 的 server 测试、`:eshop-e2e` | M | F | P1 |
| guide/spring-boot | Host on Spring Boot | 宿主 | 按类路径装配、后端选择、`HostStoreProvider`、未知配置键检查 | D44、D110、D237；R3-A | `host/spring-boot` | M | L（R3-A） | P0 |
| guide/java | Write the domain in Java | 宿主 | 注解在 Java 里怎么写、Builder 退路、目前的限制 | D8；P.17 | P.17 的 Java 冒烟测试 | M | L（P.17） | P1 |
| guide/extensions | Reactor, ids and models | 宿主 | `:reactor` 适配、`wow-cosid`、`wow-models` | D9、D149、D171、D187 | `core/reactor`、`extensions/*` | M | S | P2 |
| guide/without-spring | Host without Spring | 宿主 | `ReadFacade`、`QueryEngine.Builder`、手工组合 | A10；D237 | `query/query`、`core/runtime` | S | L（R3-A） | P2 |

### 2.5 TypeScript `/typescript/`（R3）

| 页 | 标题 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|
| typescript/index | TypeScript overview | 五个包分别做什么、该选哪个、版本与 peer 依赖 | 05 §4；D266 ① ② | `typescript/*/package.json` | S | F | P0 |
| typescript/generate | Generate a client | wow-generator 从 Manifest 生成、`.wow-generator.json`、在 CI 里逐字节比对；通用 OpenAPI → TS 一节 | D266 ② | `typescript/wow-generator`、eShop web 的 `src/generated` | M | F | P0 |
| typescript/commands | Send commands and wait | 命令客户端、等待阶段、problem+json 错误 | 08 §7；D60 | `typescript/wow-client` | M | F | P0 |
| typescript/queries | Read and query | 状态读、查询 DSL、流与续读 | D253；R3-B4 | `typescript/wow-client` | M | F | P0 |
| typescript/react | React hooks | wow-react 的 hooks（基于 V10 QueryApi） | D266 ① | `typescript/wow-react` | M | L（wow-react 移植） | P1 |
| typescript/authentication | Authentication | CoSec 令牌、`CoSec-App-Id` | D232 | — | S | S | P1 |
| typescript/view-engine/index | View engine concepts | 引擎、资源、视图、定义与宿主的分工 | wow-view-engine 设计 | `typescript/wow-view-engine` | M | S | P0 |
| typescript/view-engine/definitions | Define views | `defineView`、系统视图、看板、`admit` | 同上 | 同上 | L | S | P0 |
| typescript/view-engine/host | Host the engine | `ViewHost`、`bind`、路由 | 同上 | 同上 | M | S | P0 |
| typescript/view-engine/actions | Actions | 把命令声明成操作 | 同上 | 同上 | M | S | P1 |
| typescript/view-engine/storage | Saved views | `MemoryViewStore`、`WowViewStore`、view-store 服务 | 7.2a | `platform/view-store`、`typescript/wow-view-store` | M | S | P1 |
| typescript/view-engine/theming | Theming | 主题 | 同上 | 同上 | M | S | P1 |
| typescript/view-engine/accessibility | Accessibility | 无障碍 | 同上 | 同上 | S | S | P2 |
| typescript/view-engine/csp | Content Security Policy | CSP | 同上 | 同上 | S | S | P2 |
| typescript/troubleshooting | Troubleshooting | 常见错误与原因 | — | — | S | F | P2 |

### 2.6 数据 `/data/`（R5）

| 页 | 标题 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|
| data/index | Data in a Wow system | 三个查询模型、能力描述、权限从哪里来 | 05 §1；D235 | — | S | S | P1 |
| data/querying | Query language for analysts | 以分析为任务：过滤、分组、窗口、相对时间、cost 与限制；完整语法链到参考 | guide/v10/query | `api/query-api` | M | F | P1 |
| data/datasets | Datasets and the analytics catalog | 来源目录、定义宽表、审批、发布与版本、`NEEDS_REVISION` 与 409、舱壁与 429 | D238、D241、D247、D251、D259 | `platform/analytics/catalog` | L | L（7.2b、7.2c） | P1 |
| data/workbench | The workbench | 记录、视图、分析、看板（把 9.x 的 workbench 五页合成一页，按 V10 重写） | workbench/* 只当素材 | `typescript/wow-view-engine` | M | S | P1 |
| data/agents | Ask with an agent | wow-data-query 的用法与边界、生产数据的同意 | skills/wow-data-query | — | S | L（SK-1） | P2 |

### 2.7 运维 `/operate/`（R4）

| 页 | 标题 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|
| operate/index | Running Wow | 地图，一页上线检查清单 | — | — | S | F | P0 |
| operate/topology | Deployment model | 同一集群、节点、内部端口、Kafka 用在哪里、一个服务需要哪些组件 | 00 §1；D35、D226、D89 | `deploy` | M | S | P0 |
| operate/postgresql | PostgreSQL | 表与布局、按聚合配置存储、只读 DataSource、连接池 | 03 §2；D10、D27；R3-B | `backends/postgres` | M | L（R3-A） | P0 |
| operate/mongodb | MongoDB | 同上 | D11 | `backends/mongo` | M | L（R3-A） | P0 |
| operate/kafka | Kafka | Kafka Router、主题管理、跨上下文传输、信封 | D31、D33、D132；6.3g、6.5 | `backends/kafka` | M | S | P0 |
| operate/clickhouse | ClickHouse | 数仓 Sink、`wow_catalog`、数据集的数据面 | D18、D142、D241 | `backends/clickhouse` | M | L（7.2b） | P1 |
| operate/elasticsearch | Elasticsearch | 快照、查询、事件检索 Sink | D13、D131；6.7 | `backends/elasticsearch` | M | L（6.7） | P1 |
| operate/kubernetes | Kubernetes | 清单、探针、优雅关闭、伸缩 | guide/v10/deployment；R3-H | `deploy/*` | M | L（R3-H） | P0 |
| operate/configuration | Configure a service | 配置怎么生效、严格前缀与未知键、profile；所有键见参考 | `ConfigurationKeys` | `host/spring-boot` | S | F | P0 |
| operate/observability | Health, metrics and tracing | Actuator 的 `wow` 健康组、readiness 与 liveness、OpenTelemetry 指标与 span、要报警的指标 | 6.9；D174；R3-H | `edge/opentelemetry` | M | L（R3-H） | P0 |
| operate/scaling | Scaling and capacity | 节点数、分区数 P 与槽位、亲和路由、查询舱壁与动态上限、背压 | D240、D246、D247、D252、D263；PS-1 | — | M | L（R3-B3） | P1 |
| operate/security | Secure a deployment | 内部端口认证、集群令牌、CoSec 策略、事件里的个人数据、单一租户空间 | D226、D232；guide/v10/deployment | `edge/cosec` | M | S | P0 |
| operate/ops-console | The ops service and console | 部署 ops 服务与控制台、停放条目的队列、恢复与跳过、webhook、角色 | 7.1a～c | `platform/ops`、`deploy/ops` | M | S | P0 |
| operate/replay | Rebuild and replay | 重置检查点、重建读模型与快照（D172）、回填、backfill-tags | 04 §3；D54、D172 | `migration/migrate-data`（backfill） | M | F | P1 |
| operate/upgrades | Upgrade a deployment | 同一主版本内滚动升级（D48）、格式版本（D59）、状态 schema 变化后的重建、Manifest 基线 | D48、D59、D172 | — | M | F | P1 |
| operate/backup | Backup and recovery | 日志是唯一事实、其余都能重建；备份什么、怎么恢复 | 00 §8 | — | S | F | P2 |
| operate/troubleshooting | Troubleshooting | 症状 → 原因 → 处置的表：等待超时、409/412、429、停放、分区无人接管、未知配置键 | reviews、soak | — | M | L（rc 前） | P1 |

### 2.8 从 9.x 迁移 `/migrate/`（R6）

| 页 | 标题 | 范围 | 来源 | 代码 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|---|
| migrate/index | Migrating from 9.x | 变了什么（00 §5 的表）、路径（源码 → 数据 → 切换）、支持期（D21）、9.x 线 `v9` | 00 §5、07；D21、D22、D32 | — | M | S | P0 |
| migrate/capabilities | 9.x capabilities in V10 | 每项 9.x 能力在 V10 里的去向（保留、替代、放弃），含 Redis、CoCache、apiclient | 09 全文；D187、D229 | — | L | S（09 持续维护） | P0 |
| migrate/source | Migrate the source | 用 wow-migrate 迁移：安装、运行、审阅；注解和类型的对照；Skill 做不到的事 | 07 §2；D49；8a.1 | `skills/wow-migrate` | L | F（Skill 冻结后） | P0 |
| migrate/configuration | Configuration keys | 9.x 键 → V10 键的对照表 | 09 §14 | `spring-configuration-metadata.json` | M | F | P0 |
| migrate/http-and-clients | HTTP and TypeScript clients | 路由与请求头的变化、`fetcher-wow` 和 9.x wow-client → V10 包根、生成器配置 | D266 ① ②；09 §9、§15；G-13 | `typescript/MIGRATION.md` 的内容并入后删除该文件 | M | F | P0 |
| migrate/data | Migrate the data | migrate-data：映射文件、全量导入、增量追尾、compare、快照、预留、补偿、backfill-tags、限制 | 07 §3；D239；8b.1～8b.4 | `migration/migrate-data`（README 的正文搬到这里，README 只留链接） | L | S | P0 |
| migrate/cutover | Cut over | 蓝绿切换、演练、回滚、证据清单 | D32；8c.1 | `skills/wow-migrate/references/cutover-evidence.md` | M | L（8c.1） | P1 |
| migrate/platform | Platform services | compensation → ops、9.x view-store → V10 view-store、dataset-registry → analytics catalog | D146、D229、D251 | `platform/*` | M | F | P1 |

### 2.9 参考 `/reference/`

GEN = 由真相源生成，提交生成结果，测试比对；HAND = 手写并有检查。

| 页 | 标题 | 类型 | 真相源与生成方式 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|
| reference/configuration | Configuration keys | GEN | `host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json`（约 290 个键，包括 Kafka、ClickHouse、query、observability）。按前缀分组，列出键、类型、默认值、说明。中文说明放在旁边的 `config-descriptions.zh.json` 里，每个键都必须有中文说明（测试检查） | 生成 | F | P0 |
| reference/error-codes | Error codes | GEN | `api/manifest` 的 `FrameworkErrors`（32 个常量）。已有快照 `api/manifest/src/test/resources/framework-errors.json`，把它提升成提交在仓库里的目录文件，再加上 `QueryErrorCodes`。列出 code、HTTP 状态、含义、是否带 Retry-After、谁会遇到 | 生成 | F | P0 |
| reference/annotations | Annotations | GEN | 扫描 `api/api/.../annotation/*.kt`（AggregateRoot … Upgrade，约 30 个）：目标、属性、KDoc 第一句，链接到 Dokka 与指南页。做法同 `symbol-index.mjs` | 生成 | F | P0 |
| reference/http | HTTP API | HAND＋检查 | 路由模板、请求头（`Wow-Wait` 语法、`If-Match`、`Wow-Call`、`CoSec-App-Id`）、SSE 事件、problem+json 字段、状态码。示例文档链接到 `edge/openapi/src/test/resources/openapi/eshop-catalog-service.json`。检查：页面里的请求头名必须出现在 `edge/http` 源码里 | L | F | P0 |
| reference/query-language | Query language | HAND＋检查 | guide/v10/query 的语法部分。JSON 示例改为 `<!-- sample: … -->`，样本放在 `query/query` 的测试资源里，由内存后端的测试执行 | L | F | P0 |
| reference/protocols | Wire protocols | HAND | `CommandResult`、`StateEventRecord`、快照、等待信号、Kafka 信封、格式版本 | 08 全文（已采纳） | M | S | P1 |
| reference/manifest | Manifest schema | GEN | `:manifest` 目前没有提交的 JSON Schema。新增：由 `:manifest-tools` 的测试用 victools 导出 `api/manifest/schema/manifest.schema.json` 并做快照比对，页面由它生成（DI-4） | 生成 | F | P1 |
| reference/modules | Modules and artifacts | GEN | 各模块 `build.gradle.kts` 里的 `description`，加上 `v10PublishedProjects` 和 BOM 约束。由 `:architecture` 的测试输出 `test/architecture/modules.json` 快照，页面由它生成：artifactId、目录、内容、依赖方向 | 生成 | F | P0 |
| reference/api-stability | API levels and compatibility | HAND＋GEN | 手写部分：`@InternalWowApi`、`@ExperimentalWowApi`、`@WowSpi` 的承诺，以及 GA 之后的兼容规则（D216、D48）。生成部分：扫描源码中 `@ExperimentalWowApi` 的声明，列出实验性 API 清单 | M | F | P1 |
| reference/observability | Metrics, spans and health | HAND＋检查 | 指标名、span 属性（`WowAttributes`）、健康组。检查：页面里的每个名字都必须出现在 `edge/opentelemetry` 或 `host/spring-boot` 的源码里 | M | L（R3-H） | P1 |
| reference/migrate-data | migrate-data CLI | HAND | 命令行参数、配置、`migration-map.json` format 1、检查点 | migrate-data README | M | S | P1 |
| reference/typescript/* | TypeScript API | GEN | 每个包的 API 报告（`typescript/*/test/api/*.api.md`）加符号索引。把 view-engine 的 `symbol-index.mjs` 推广到 wow-client、wow-react、wow-view-store。wow-generator 的 CLI 与配置页手写，示例由 `typescript-samples` 编译 | 生成 | F | P1 |
| reference/glossary | Glossary | HAND | 术语，中英对照（中文写作组的依据），每条一句定义并链接到概念页 | M | **S（最先写）** | P0 |
| （外链）Kotlin API | Dokka | GEN | `dokkaGenerateHtml`，范围限定为已发布的 V10 模块 | — | — | P0 |

### 2.10 贡献 `/contribute/`（R7；CONTRIBUTING.md 是入口，站内只放深入内容）

| 页 | 标题 | 范围 | 来源 | 大小 | 时机 | 优先级 |
|---|---|---|---|---|---|---|
| contribute/index | Codebase tour | 模块地图与依赖方向、`:architecture` 守的规则、API 级别 | 06；AGENTS | M | L（N-1、CI-2 改名之后） | P1 |
| contribute/testing | Test layers | 单元测试与容器测试、TCK、`test-dsl`、CI 的门禁 | AGENTS Testing（取代 `guide/test-runtime.md`，AGENTS 里的链接随之改） | M | L（CI-2） | P1 |
| contribute/documentation | Documentation governance | 每类文档归哪里、样例机制、生成页的再生成命令、中英对齐 | 由 `guide/advanced/documentation-governance.md` 搬来并重写；`docs-layout.mjs` 的 `GOVERNANCE` 常量随之改 | M | S | P0（DI-1 要用） |
| contribute/design-records | Design records and decisions | designs、决策台账、审查台账怎么用 | designs/README | S | S | P2 |
| contribute/backends | Write a backend | SPI 端口、`HostStoreProvider`、TCK、`QueryBackendSpec` | D237；R3-A | M | L（R3-A） | P2 |

### 2.11 页数小计（每种语言）

| 区 | P0 | P1 | P2 | 合计 | 英文字数估计 |
|---|---|---|---|---|---|
| 首页 | 1 | 0 | 0 | 1 | ~0.5k |
| 入门 | 4 | 2 | 0 | 6 | ~7k |
| 概念 | 9 | 1 | 0 | 10 | ~10k |
| 开发指南 | 13 | 11 | 2 | 26 | ~33k |
| TypeScript | 7 | 5 | 3 | 15 | ~13k |
| 数据 | 0 | 4 | 1 | 5 | ~5k |
| 运维 | 10 | 6 | 1 | 17 | ~18k |
| 迁移 | 6 | 2 | 0 | 8 | ~12k |
| 参考（手写） | 3 | 4 | 0 | 7 | ~11k |
| 贡献 | 1 | 2 | 2 | 5 | ~4k |
| **手写合计** | **54** | **37** | **9** | **100** | **~113k** |
| 参考（生成） | 4 | 2 | 0 | 6 类 | 生成 |

---

## 3. 首页、README、CONTRIBUTING

### 3.1 站点首页（`index.md`，中英各一份）

依次：

1. **Hero**：名字 Wow；口号 “Domain Model as a Service”；一句话：只写领域模型，就得到一个能上线的系统。三个按钮：Quickstart、Why Wow、Migrate from 9.x。
2. **六个特性卡**，每个都能从“写、传、用”推出来：只写领域模型（A3/A4）；日志就是总线（A1/A2）；一份契约推出 REST、OpenAPI、TS 客户端和视图（A9）；读模型可以重放（A7/A11）；失败是数据（A8）；PostgreSQL 与 MongoDB 都是一等后端。每卡一句话，链到对应概念页。
3. **按读者选路径**：六张卡（评估、开发服务、前端、运维、分析、迁移），对应 §1 的 R1～R6，链到各区的 index。
4. **代码一瞥**：约 15 行聚合加 5 行测试，`<!-- sample -->` 取自 quickstart；旁边列出它得到的东西：路由、OpenAPI、TS 客户端、查询。
5. 页脚：KaiCode’26 获奖、GitHub、贡献、许可证、9.x 文档链接（`/9.x/`）。

### 3.2 README.md / README.zh-CN.md（链接到站点，不复制站点内容）

依次：

1. Logo、名字、一句话价值；徽章改正（R21）：Maven 徽章换成 `me.ahoo.wow/wow-bom`；CI 徽章 CI-2 前指 `v10.yml`，CI-2 后指 `ci.yml`；Codecov 徽章不变。获奖信息保留。
2. **What you write / what you get**：一个聚合（`<!-- sample -->`，取自 quickstart）→ 列出得到的东西。
3. **Why**：写、传、用三条，加五条最能区分 Wow 的公理（A1、A3、A8、A9、A11）。
4. **Quickstart**：三步（插件与 BOM、写聚合、`run` 后 curl）→ 链到 `start/quickstart`。
5. **架构图**：mermaid，00 §4 的简化版。
6. **模块**：只列入口坐标（`wow-bom`、`wow-api`、`wow-spring-boot`、`wow-test`、后端），完整列表链到 `reference/modules`。
7. **TypeScript 包**：npm 表（五个包）。
8. **平台服务与示例**：ops 控制台、view-store、analytics catalog、eShop。
9. **Requirements 与版本表**：JDK 21、Kotlin、Spring Boot 4（AGENTS 的发布流程要更新这张表；pre-release 期间表格保持在最后一个稳定版）。
10. **From 9.x**：链到 `/migrate/`；9.x 维护线 `v9`；9.x 文档在 `/9.x/`。
11. **Agent Skills**：安装命令，链到 `start/skills`。
12. 文档、社区、贡献、安全、许可证。

### 3.3 CONTRIBUTING.md

依次：

1. 欢迎，以及在哪里提问（Issues、Discussions）。
2. **前置条件**：JDK 21、pnpm（仓库根目录 `pnpm install`）、Docker（容器测试需要）。
3. **仓库地图**：六行，链到 `contribute/index`。
4. **构建与测试**：用 CI-2 改名之后的任务名（`check`、`integrationTest`）。写作排在 CI-2 之后；如果 CI-2 晚于 rc，就先写现名，由 CI-2 的 PR 一并改。
5. **改动规则**：破坏性变更与 `!`、ABI 转储与 `updateKotlinAbi`、API 级别、不留兼容代码（D1）、prettier。
6. **文档、设计记录、Skills 放在哪里**：一段，链到 `contribute/documentation`；Skills 的校验命令。
7. **PR 流程**：Conventional Commits、PR 模板、标签、合并队列（CI-2 之后）。
8. 行为准则、安全报告。

AGENTS.md 里所有指向旧文档页的链接，由 DI-1 一并改掉。

---

## 4. Skills

### 4.1 从读者的工作重新推导

| 工作（读者） | 交付物 | Skill | 结论 |
|---|---|---|---|
| 开发、审查、诊断一个 V10 服务（R2） | 代码、审查结论、根因与修复 | **wow-develop** | 保留。改写为只教 V10（SK-1）；遇到 9.x 目标时，说明要用 `v9` 的插件，或转给 wow-migrate |
| 写调用 Wow 的 TS 代码（R3） | 生成的或手写的客户端代码 | **wow-client** | 保留。删掉 OpenAPI → Wow 模型那条路径（D266 ②）；fetcher-wow 和 9.x wow-client 的迁移移给 wow-migrate |
| 写视图定义（R3、R5） | `defineView` 定义 | **wow-view-definition** | 保留，不与 host 合并：交付物不同，eval 已经能分清两者的边界。仓库内的例外从 compensation 控制台改为 ops 控制台（`platform/ops/console/src/views/`） |
| 把视图引擎接到宿主（R3） | 宿主接线 | **wow-view-host** | 保留，同上；从 `fetcher-viewer` 迁移这一项保留（它与 9.x 无关） |
| 用实时数据回答业务问题（R5） | 数字、所用的查询、注意事项 | **wow-data-query** | 保留。改为 V10 的能力描述和三个模型，加上数据集 409 和舱壁 429 |
| 把 9.x 服务迁到 V10（R6） | 改写后的源码、数据导入、切换证据 | **wow-migrate** | 保留，**收窄为 9.x → V10**：Kotlin 源码、配置、HTTP、TS 客户端、数据、切换。v8→v9、Spring Boot 3→4、9.x 内部的 SPI 迁移留在 `v9`。**需要用户确认**（D266 ③ 原文是“不受影响”） |
| 运维集群、处置故障（R4） | 清单、配置审查、停放处置 | （无） | GA 不新增（D255）。暂时由 wow-develop 的 diagnose 部分覆盖（只读 ops 路由）。`wow-operate` 列为 10.x 候选，交给协调者登记 |
| 贡献者 | — | 无 | 所有 Skill 在 Wow 仓库内都不激活（视图的两个例外除外），规则不变 |

插件：`plugins.json` 的版本在 GA 时定为 `1.0.0`（`v9` 上留 0.x）；description 和 keywords 去掉 OpenAPI 生成与 v8 相关的词，加上 Manifest；README 的 “V9 是当前维护基线” 一段改为 V10（R28）。N-1 把 `v10-service.md` 这类带 v10 的名字去掉。

### 4.2 每个 Skill 的 references 与 evals

原则不变：references 写决策、不变量、源码发现方法和完成证据，不复制 API 文档；具体符号必须到目标 checkout 里重新确认。Kotlin 示例里的 import 由 §5.4 的 ABI 检查守住。

**wow-develop**

- references（全部新写；删掉 9.x 的 10 篇：aggregate-sourcing、command-delivery、handler-discovery、pipeline-map、prepare-key、query-read-model、review-rubric、runtime-lifecycle、saga-processors、starter-storage；`v10-service.md` 拆进下面各篇）：
  - `model.md`：聚合、状态、命令、拒绝与错误码、演进与退役、预留键、owner / space / tags。
  - `processes.md`：Saga、投影、reactor、按事件类型的处理函数、`@OnRejected`、定时、`@Retry` 与失败台账。
  - `cross-context.md`：发布的事件流、对端 api jar、类型化客户端、`Wow-Call`。
  - `reads.md`：状态读、`QueryApi`、字段与索引、脱敏、策略、数据集声明、events。
  - `testing.md`：test DSL，以及“先写测试”的流程。
  - `hosting.md`：Spring Boot 宿主、后端选择、配置键、Manifest 基线与兼容、OpenAPI。
  - `diagnose.md`：症状地图（等待超时、409/412、停放、429、未知配置键、派生命令被拒）；ops 路由只读。
  - `review-rubric.md`：V10 不变量（evolve 里不做 I/O、decide 是确定的、阻塞调用必须标 `@Blocking`、唯一键都含租户、不手写 Manifest 能推导的东西）。
- evals：
  - 保留全部 activation 的反例（Axon、Spring Data、通用 DDD、框架仓库本身、checkout 范围的标记）。
  - 重写行为用例：从零写服务（b34）、带拒绝的聚合、带 `@OnRejected` 的 Saga、投影幂等、类型化的对端事件、带策略的查询、先写测试、审查发现 evolve 里做了 I/O、诊断等待超时、诊断停放的聚合。
  - 新增反例：9.x 服务要加一个 Saga（不应套用 V10 API）。
  - 删除（留在 main）：v9-cursor（a69～a71、b23～b25）、9.x 快照聚合与查询策略 SPI（a64～a66、b12～b14、b17、b19、b21、b30～b32）、v8 相关（a14、a24、a25）。

**wow-client**

- references：
  - `generate.md`：从 Manifest 生成、`.wow-generator.json`、CI 里比对、通用 OpenAPI → TS。
  - `commands.md`：发命令、等待阶段、错误处理。
  - `queries.md`：查询 DSL、状态读、流与续读。
  - `react.md`：hooks。
  - `auth.md`：CoSec。
  - 现有的 `api.md`、`generator.md` 拆进上面五篇。
- evals：
  - 保留：命令后查询、过滤 DSL、React hooks、鉴权、错误处理、在 CI 里重新生成（b44、b45、b47～b49、a86～a88、a93），以及反例 n01、n02、a91、a92、a104、a114、a96。
  - 改写：a80～a83、b40～b42 改为从 Manifest 生成。
  - 移给 wow-migrate：b43、b46、b51、a89；b50 删除。

**wow-view-definition / wow-view-host**

- references 基本保留，改为 V10 的能力描述端点和 `WowViewStore` 对接 V10 view-store 服务。
- evals：a102、a111（“wow console”）改为 ops 控制台；其余保留。
- 两个 Skill 的 TS 示例已经由 `typescript-samples.mjs` 编译，不需要改。

**wow-data-query**

- references：`descriptor.md` 改为 V10 能力描述，补上 events 与数据集模型、409 `dataset-unavailable`、429 舱壁与重试、生产数据的同意。
- evals：保留现有全部；新增数据集需要修订（409）、事件时间线、429 重试三个用例。

**wow-migrate（9.x → V10）**

- references：
  - `v9-to-v10.md` 拆成 `source-model.md`、`source-processes.md`、`config-map.md`、`http-and-clients.md`（含 TS：fetcher-wow、9.x wow-client → V10 包根、生成器配置）。
  - 新增 `data.md`（migrate-data 的演练流程）。
  - 保留 `cutover-evidence.md`、`migration-risk-map.md`（改为 9.x → V10），新增 `platform-ports.md`。
  - 删除（留在 main）：`spring-boot-3-to-4.md`、`runtime-rest-validation.md`、`scripts/audit-v6-usage.sh`。
- evals：
  - 核心是 H5 的 5 个 9.x 领域（8a.2，`h5-inputs`）。
  - 保留：b75、b121～b123、a121、a44、a47、a52、b08、b09。
  - 改写：b10 改为 migrate-data 演练，b11 改为 V10 的路由。
  - 新增：TS 客户端迁移（接手 wow-client 的 b43、b46、b51、a89）、配置键对照、切换回滚。
  - 删除（留在 main）：a14、a18、a24、a25、a26、a68、b22、a67、a72、b15、b16、b20、b26、b33。

### 4.3 Skills README

按读者的工作重写：每个 Skill 写清交付物、边界、选择规则（保留现有的“按交付物选，不按组件名选”）。V9 那一段删除，改为一句“9.x 用户使用 `v9` 上的插件”。Validation 与 Distribution 两节保留。

---

## 5. 验证

### 5.1 哪些页面带什么样例

| 页面组 | Kotlin 样例 | TS 样例 | 样例来源 |
|---|---|---|---|
| start/quickstart、首页、README | 是，逐字取自源码 | — | `example/quickstart`（已有，由 `QuickstartPageTest` 检查） |
| start/existing-project、start/tutorial、guide/* | 是 | tutorial 有 | eShop 的 `*-api`、`*-domain`、`*-server`、`web`（加 `// region <name>` 标记）；quickstart |
| guide/testing、guide/testing-services | 是 | — | quickstart 的 `AccountSpec`；eShop 的 Spec |
| reference/query-language、data/querying | JSON 查询 | 是 | `query/query` 测试资源里的查询样本，由内存后端执行 |
| typescript/*、typescript/view-engine/* | — | 是 | eShop web（`<!-- typecheck-generated: example/eshop/web/src/generated/... -->`），以及页内示例（由 `typescript-samples.mjs` 编译） |
| operate/*、guide/spring-boot | YAML | — | quickstart 与 eShop 的 `application.yaml`（逐字取）；手写 YAML 由 §5.3 的配置键检查守住 |
| migrate/source | 9.x → V10 对照 | — | wow-migrate 的 H5 输入与参照迁移（左边 9.x 只当文本；右边 V10 取自 eShop 或 quickstart） |
| skills/** | 片段 | 是 | 片段不编译；import 由 §5.4 检查，TS 沿用现有机制 |
| concepts/* | 不放可运行代码 | — | 只放 mermaid 图与伪代码，伪代码块标 `<!-- sample: illustration -->` |

### 5.2 现有检查（保留）

- `documentation/test/typescript-samples.test.mjs`：TS 示例与 `typecheck-generated`。扫描目录从 `guide/typescript`、`reference/typescript` 改为 `typescript/`、`reference/typescript/`、`data/`，以及含 TS 的 skills。
- `source-links.test.mjs`：`blob/main`、`blob/v10` 链接的目标存在。CI-3 时 `tree/v10/` 改为 `main`。
- `markdown-pages.test.mjs`、`storybook-links.test.mjs`、`mermaid-zoom.test.mjs`：随页面路径更新。
- VitePress 构建时的死链检查（`ignoreDeadLinks` 只留必要的项）。
- `docs-layout.mjs`、`repo-hygiene.mjs`、`validate_wow_skills.py`。
- `QuickstartPageTest`：由 §5.3 的通用检查取代，quickstart 模块的 build 文件去掉对文档页面的引用。

### 5.3 新增检查（交给代码会话，见 §6 的 DI-*）

| 检查 | 做法 | 位置 |
|---|---|---|
| **样例一致** | 把 `QuickstartPageTest` 推广成 Node 测试。全站页面、README、skills 中，每个 `<!-- sample: <repo-path>[#region] -->` 后面的代码块，必须与该文件（或 region，去掉许可证头）逐字相等。区块用 `// region x` … `// endregion`，YAML 用 `# region x`。规则：带 `import`、`package` 或顶层声明的 ```kotlin``` 块，必须是 sample，或者显式标 `<!-- sample: fragment -->`。源码本身由各自的 Gradle 模块编译和测试 | `documentation/test/samples.test.mjs` |
| **中英对齐** | en 与 zh 的页面集合相同（只有 TS 符号索引允许只有英文，白名单写在测试里）；每页的标题层级序列相同；代码块数量相同，内容逐字相同 | `documentation/test/locale-parity.test.mjs` |
| **配置键存在** | 页面、README、skills 里 YAML 或 properties 块中的每个 `wow.*` 键，都必须是 `spring-configuration-metadata.json` 里的键或它的前缀 | `documentation/test/config-keys.test.mjs` |
| **生成页是最新的** | `pnpm --filter documentation reference:generate` 重新生成，与提交的结果比对；`-u` 更新。做法同 view-engine 的 `test:api -u` | `documentation/scripts/reference/*.mjs` 加测试 |
| **公共资源都有引用** | `docs/public/images/**` 里每个文件至少被一个页面引用 | 加进 `docs-layout.mjs` |
| **跳转表有效** | 每个来源不是现存页面，每个目标是现存页面 | `documentation/test/redirects.test.mjs` |

### 5.4 Skills 的新增检查

- **Kotlin import 必须真实存在**：skills 的 Markdown（`evals/` 除外）里每个 `import me.ahoo.wow.…`，都必须在某个 `*/api/*.api` ABI 转储里有对应的类。用 ABI 转储做依据，是因为 Skill 安装后不能依赖仓库文件，而这项检查只在仓库 CI 里跑。放进 `scripts/validate_wow_skills.py`（只用标准库，读 `.api` 文件即可）。
- 文档页里标为 fragment 的 Kotlin 块也跑同一个 import 检查。
- eval 不进 CI（会计费）。按 D255 的节奏，rc 前用 `scripts/eval-skills.mjs` 每个 Skill 跑一轮，H5 按 8a.2 的门槛跑（≥ 80% 且无 UNSAFE）。

### 5.5 CI 范围

`ci-scope.mjs` 的文档范围加上 `example/quickstart/**` 和 `example/eshop/**/src/**`：样例源码变了，样例一致检查要重跑。`ci-scope.test.mjs` 里写死的旧页面路径同步更新。

---

## 6. 写作计划

### 6.1 支撑代码（代码会话做，协调者分派；同时最多三路）

| ID | 内容 | 依赖 | 大小 | 时机 |
|---|---|---|---|---|
| DI-1 | **骨架 PR**：删除 §7 的全部 9.x 页面与图片；把 `guide/v10/*` 移到新路径作为起点（内容之后重写）；新的 navbar 与 sidebar（只含已存在的页面）；`contribute/documentation` 搬过来，改 `docs-layout.mjs` 的 `GOVERNANCE`；改掉所有外部入链（§8.3）；跳转页生成机制与 `redirects.test.mjs`；404 页 | 无 | M | 立即 |
| DI-2 | 样例一致检查（`samples.test.mjs`）加 fragment 规则；取代 `QuickstartPageTest` 的页面耦合；ci-scope 更新；eShop 源码加 region | DI-1 | M | 立即 |
| DI-3 | 生成参考：配置（含中文说明旁表）、错误码（`framework-errors.json` 提升为仓库里的目录文件）、注解、模块（`:architecture` 输出 `modules.json`）、实验性 API 清单、TS 符号索引推广到其余包；`reference:generate` 与比对测试 | DI-1 | M～L | 冻结前开工，冻结后再生成一次 |
| DI-4 | Manifest JSON Schema 的导出（`:manifest-tools` 测试做快照）与渲染 | DI-3 | S～M | 冻结后 |
| DI-5 | `locale-parity`、`config-keys`、公共资源引用检查；skills 的 ABI import 检查 | DI-1 | M | 冻结前 |
| DI-6 | 9.x 文档部署到 `/9.x/`（documentation-deploy 同时构建 `v9`，base 为 `/9.x/`），随 CI-3 一起做 | 用户批准、CI-3 | S～M | GA 切换时 |

### 6.2 写作组（三条线：en、zh、skills）

- **en 线先写，zh 线跟在同一分支上**：每个页面组一个 PR，en 与 zh 同时合入（对齐检查要求如此），zh 落后数小时。这样不需要“待翻译白名单”之类的特例。
- **zh 线以术语表为准**；代码块不翻译，因为对齐检查要求代码块逐字相同，注释一律用英文。
- **skills 线独立**，只依赖冻结（SK-1 在 DX-1d 之后）。

| 波次 | 时间 | en（并由 zh 跟随） | skills | 支撑代码 |
|---|---|---|---|---|
| W0 冻结前（D-2～D0） | 现在 | 术语表；概念 10 页；首页草稿；migrate/index、capabilities、data；reference/protocols、migrate-data；operate/topology、security、ops-console、kafka；typescript/view-engine/* 八页（不受冻结影响）；data/workbench；guide/testing（T1 已合并） | wow-view-definition、wow-view-host 改向 V10（不受冻结影响）；wow-migrate 的 data.md 与 cutover；Skills README 骨架 | DI-1、DI-2、DI-5；DI-3 开工 |
| W1 冻结后 +0～3 天（P0 主体） | 冻结 → +3d | quickstart、existing-project、guide/{index, aggregates, rejections, sagas, projections, reading-state, queries, published-streams, calling-services, http}；typescript/{index, generate, commands, queries}；reference/{http, query-language, glossary 定稿}；migrate/{source, configuration, http-and-clients}；operate/{index, configuration}；README | wow-develop、wow-client 改写；wow-migrate 的 source 与 TS 部分 | DI-3 定稿、DI-4 |
| W2 +3 天 → rc（P0 收尾与 P1） | 等在途工作 | guide/{failures（P.16）, spring-boot（R3-A）, ownership, unique-keys, event-evolution, scheduling, manifest, openapi, generated-code, testing-services, java（P.17）, event-queries（R4-Q2）, datasets（7.2b/c）}；operate/{postgresql, mongodb, kubernetes, observability（R3-H）, scaling（R3-B3）, replay, upgrades, clickhouse, troubleshooting}；data/{index, querying, datasets}；typescript/{react, authentication}、view-engine 余下页；migrate/platform；start/{tutorial, skills}；CONTRIBUTING 与 contribute/*（CI-2 之后） | wow-data-query；全部 eval 跑一轮；H5 | — |
| W3 rc → GA | — | migrate/cutover（8c.1）、operate/elasticsearch（6.7）、P2 各页、截图与图示收尾、跳转表定稿 | 插件 1.0.0 | DI-6（随 CI-3） |

### 6.3 工作量

英文约 113k 字，中文相当。按每个写作会话每天约 15k 字（含读设计、对代码、样例加 region），en 线约 8 个会话日，zh 线约 6 个会话日，Skills 线约 4 个会话日（不含 eval 运行）。在“同时最多三路”的约束下，W0～W2 大约 6～7 个日历日，能赶上 rc。P2 页面留到 GA 前后。

### 6.4 冻结前就能写的组（API 不会变）

- 概念全部（依据的是已采纳的设计，不是签名）。
- 术语表。
- reference/protocols（08 已采纳）。
- migrate 的 index、capabilities、data，以及 reference/migrate-data（migrate-data 稳定，backfill-tags 已合并）。
- 视图引擎八页与 data/workbench（V10 不改视图引擎 API，只改数据源）。
- operate 的 topology、security、ops-console、kafka。
- guide/testing（T1 已合并）。
- 两个视图 Skill。

依赖在途工作的页（L 标记），在对应 PR 合并后再写，不提前写占位页（不留死页）。

---

## 7. 删除清单

`documentation/docs/{en,zh}/` 下下列全部删除（每种语言 145 页左右；只有英文的 5 个 TS 符号页另计）：

- `index.md`：重写，不保留。
- `articles/*`：4 页（index、command-success-is-not-complete、traditional-vs-wow-architecture、why-ddd-fits-ai-era）。
- `onboarding/*`：5 页（index、contributor、executive、product-manager、staff-engineer）。
- `guide/` 下除 `v10/` 以外的全部：
  - `index`、`introduction`、`getting-started`、`existing-project`、`core-concepts`、`best-practices`、`configuration`、`data-access`、`projection`、`query`、`recovery`、`troubleshooting`、`migration`、`open-api`、`bi`、`bi-operations`、`skills`、`test-runtime`、`test-suite`、`application-testing`。
  - `advanced/*` 13 页；`documentation-governance` 改写后搬到 `contribute/documentation`。
  - `command/*` 9 页、`domain/*` 6 页、`event/*` 5 页、`extensions/*` 13 页、`migration/*` 3 页、`query/*` 16 页、`typescript/*` 17 页。
- `guide/v10/*` 9 页：移到新路径，按第一性原理重写；它们从未部署到站点，不需要跳转。
- `reference/config/*` 4 页、`reference/example/*` 3 页、`reference/ecosystem.md`、`reference/typescript/**`：wow-generator 7 页（其中描述 OpenAPI → Wow 路径的部分按 D266 ② 删除）、wow-view-engine 15 页（其中 5 个 symbols 页由 DI-3 重新生成）、wow-view-store 1 页、index 1 页。
- `workbench/*` 5 页：合并成 `data/workbench`。
- `public/images/`：`Architecture.svg`、`Features.png`、`bi/`、`compensation/`、`eventstore/`、`observability/`、`wait/`、`articles/`。保留 `logo.svg`、`kaicode-2026-wow.svg`、`award-confetti/`，以及 favicon、manifest、站点验证文件。新的引用检查会发现漏删或误删。
- 仓库其他地方：
  - `typescript/MIGRATION.md`：内容并入 `migrate/http-and-clients` 后删除，同时从 `docs-layout.mjs` 的白名单里去掉。
  - `migration/migrate-data/README.md`：正文搬到 `migrate/data` 与 `reference/migrate-data`，README 只留几行加链接。
  - `QuickstartPageTest` 与 quickstart build 文件里引用文档页的那部分，由 DI-2 取代。
- skills：§4.2 列出的 references、evals、scripts。它们留在 `v9`（9.x 维护线），不在 `main` 上存档。

---

## 8. 跳转与链接

### 8.1 现实约束

- 站点从 `main` 部署（`documentation-deploy.yml`，GitHub Pages）：根目录是 Wow 10 的页面，`/9.x/` 是 `v9` 分支构建的 9.x 页面与 Storybook（DI-6 #4699、#4786，2026-10-22 上线）。
- GitHub Pages 没有服务端跳转。VitePress 没有开 `cleanUrls`，所以 `/guide/x` 对应的文件是 `guide/x.html`。

### 8.2 方案（需要用户批准 DI-6）

1. **9.x 文档部署到 `/9.x/`**：由 `v9` 分支构建（base 为 `/9.x/`），同一次 Pages 部署上传。仓库内不放存档（符合 v10-keep-clean）。9.x 线仍在维护，迁移中的用户需要它。
2. **有上限的跳转表**：`documentation/docs/.vitepress/redirects.ts` 是数据（约 40 条，en 与 zh 各一份路径）。`buildEnd` 钩子为每条写一个静态 `<old>.html`：meta refresh 加 `location.replace`（保留 hash）加 canonical。只映射有明确 V10 对应页的高流量旧 URL。
3. **其余旧 URL 落到自定义 404**：提示“这可能是 9.x 文档”，链接到 `/9.x/<同路径>` 和 `/migrate/`。
4. **寿命**：跳转表在 10.1.0 删除，由协调者登记到台账。这是站点 URL 层面对外部入链的处理，不是代码别名。如果用户认为它违反“no aliases”，就只保留第 1 和第 3 条。

建议的映射（en；zh 加 `/zh` 前缀）：

| 旧路径 | 新路径 |
|---|---|
| /guide/getting-started | /start/quickstart |
| /guide/introduction | /start/why-wow |
| /guide/existing-project | /start/existing-project |
| /guide/core-concepts、/guide/advanced/architecture | /concepts/ |
| /guide/domain/aggregate、/guide/domain/index | /guide/aggregates |
| /guide/domain/event-evolution | /guide/event-evolution |
| /guide/domain/event-sourcing | /concepts/event-log |
| /guide/domain/snapshot | /concepts/read-models |
| /guide/command/index、/guide/command/sending | /guide/http |
| /guide/command/completion | /concepts/waits |
| /guide/event/saga | /guide/sagas |
| /guide/event/processor、/guide/projection | /guide/projections |
| /guide/event/compensation | /operate/ops-console |
| /guide/query、/guide/query/filter-expression | /reference/query-language |
| /guide/query/snapshot-query | /guide/queries |
| /guide/query/dataset-query、/guide/bi | /data/datasets |
| /guide/test-suite | /guide/testing |
| /guide/configuration、/reference/config/core | /reference/configuration |
| /guide/advanced/observability、/guide/advanced/metrics | /operate/observability |
| /guide/troubleshooting | /operate/troubleshooting |
| /guide/migration | /migrate/ |
| /guide/extensions/mongo | /operate/mongodb |
| /guide/extensions/kafka | /operate/kafka |
| /guide/extensions/elasticsearch | /operate/elasticsearch |
| /guide/extensions/cosec | /concepts/identity |
| /guide/typescript/、/guide/typescript/quick-start | /typescript/ |
| /guide/typescript/generated-client | /typescript/generate |
| /guide/typescript/view-engine* | /typescript/view-engine/<对应页> |
| /workbench/* | /data/workbench |
| /guide/skills | /start/skills |
| /onboarding/、/articles/ | /start/why-wow |

没有对应页的（redis、cocache、apiclient、webflux、tck、`command/internals/*`、`advanced/*` 的其余页、v6-to-v8 等）不映射，落到 404，再引导到 `/9.x/`。

### 8.3 仓库内的入链（DI-1 一并改）

- `AGENTS.md`：`guide/test-runtime.md` → `contribute/testing`；documentation governance 的链接 → `contribute/documentation`；Version Management 里的 `existing-project.md`、`getting-started.md` → `start/existing-project`、`start/quickstart`。
- `.github/scripts/docs-layout.mjs`（`GOVERNANCE`）、`docs-layout.test.mjs`、`ci-scope.test.mjs`、`package-check.test.mjs`（`guide/typescript/compatibility.md`）。
- `documentation/test/{markdown-pages,typescript-samples,storybook-links}.test.mjs`、`docs/.vitepress/storybook-links.mjs`。
- `typescript/wow-view-engine/test/referenceDocs.test.ts`、`themeGuide.test.ts`、`fixtures/themeDocs.ts`、`scripts/symbol-index.mjs`（引用文档路径）。
- 各包 README（wow-client、wow-react、wow-view-store、wow-view-engine、wow-generator，中英文）、`typescript/storybook/README.md`、`typescript/RELEASE_NOTES_TEMPLATE.md`。
- `example/eshop/README.md`、`DX-GAPS.md`、`deploy/eshop-ordering/README.md`、`skills/wow-view-host/references/wiring.md`、`build.gradle.kts`（含站点 URL 的那一处）。
- `documentation/designs/{README,progress}.md` 里提到 `guide/v10` 的地方改成新路径。

---

## 9. 决定（协调者 2026-10-20）

1. **9.x 文档部署到 `/9.x/`（DI-6）**：**用户已批准**。由 `v9` 分支构建，与 rc 的分支切换（CI-3）一起做；DI-6 是最后一个 DI 项，切换前准备好 PR，但不合并。404 页和跳转表可以指向 `/9.x/`。9.x 维护线的分支名是 **`v9`**（D265 ④，用户决定），不是 release-9.x，本方案已全部改正。
2. **跳转表**：做，约 40 条；到期版本 10.1 写在表文件里，并有一条测试，在项目版本到 10.1 之后失败。
3. **wow-migrate 收窄为 9.x → V10，并接手 TS 侧的迁移**：做。**这偏离了 D266 ③**（原文：wow-migrate 不受影响）和路线图 8a.2（原文：wow-client 的 v9-to-v10 参考）。SK-1 的 PR 要在决策台账里登记这次修订。
4. **articles 全部删除**：做。
5. **en 先写，zh 在同一 PR 里跟上**：做。
6. **`wow-operate`**：登记为 10.x 候选。
7. **样例用 `<!-- sample -->` 逐字相等**：做。

## 10. 实施记录与 TODO

- DI-1 **不移动 `guide/v10/*`**：在途的 API PR（R4-Q4、CL-2、7.2c、DX-2）还在改这些页面里的代码片段。API 冻结后，由一个单独的 PR 把它们移到 §2 的新路径；这条 TODO 只记在本方案里，不写进仓库。
- 顺序：DI-1 → DI-2、DI-5 → DI-3（冻结前开工）。DI-1 合并后，W0 写作线开始。
- **DI-1** 为 #4690（2026-10-20）。TS 页面（`guide/typescript`、`reference/typescript`、`workbench`）与 `guide/v10` 一样暂留原路径：它们描述的是现有的包，并且被 view-engine 包自己的测试读取；由 TS 线在新路径重写。
- **DI-2** 为 #4691，叠在 #4690 上。样例检查放在每个 PR 都跑的 docs-layout 作业里（`.github/scripts/docs-samples.mjs`），不放在只在文档改动时运行的 documentation/test，这样样例的源码一改就会被检查。
- **DI-5** 叠在 #4691 上。**修订 §5.3 的中英对齐**：只比较代码块的语言序列，不再要求内容逐字相同。现有 23 页的 zh 代码块有意使用中文注释和中文界面文案（视图定义的标签），逐字相同不合理。Skills 的 import 检查并入 docs-samples.mjs，不放进 validate_wow_skills.py：同一套页面扫描，覆盖全站、README 与 Skills；`<!-- sample: 9.x -->` 豁免迁移材料里的 9.x 代码。行内的 `wow.*` 记号不检查：它们与错误码、span 属性同名，只检查 YAML 与 properties 代码块。
- **DI-3（第一部分）** 叠在 #4693 上：生成配置项、错误码与注解三类参考页（中英各一份）。生成器放在 `.github/scripts/docs-reference.mjs`，检查在每个 PR 的 docs-layout 作业里跑，`pnpm docs:reference` 重新生成。**修订**：中文页的说明保持源码里的英文（页首注明），不设中文说明的旁表；需要时由 zh 写作线再加。模块与坐标、API 稳定性清单、TS 符号索引放进 DI-3 的第二部分；Manifest schema 仍是 DI-4。
- **DI-3（第二部分）** 为 #4696：生成模块与制品、API 级别两页。各 TS 包的符号索引交给 TS 写作线（协调者同意）。
- **DI-4 改了做法**：不用 victools 从 Kotlin 类型生成 JSON Schema。原因是 Manifest 的线上形状由 `ManifestTree.write` 手写决定（`defs` 写成 `$defs`、默认值省略、枚举写名字、计算属性 `schemaRefs` 不写出），从类型生成的 schema 会描述另一种形状，而且会悄悄漂移。改为解析 `ManifestTree.write`，生成每个对象及其键的提纲（`reference/manifest`）：不需要跑 Gradle，也不新增快照。
- **W1/W2 收口（用户 2026-10-22）**：A～K 共 13 组中英文已合并（#4755、#4763、#4764、#4771、#4774～#4781），`guide/v10/`、`guide/typescript/` 与 `typescript/MIGRATION.md` 已删除，DI-6 随后上线。用户决定其余页面留到 GA 前补，rc 不等：P0 `start/index`、`start/why-wow`；P1 `start/tutorial`、`start/skills`、`operate/elasticsearch`、`reference/observability`、`reference/api-stability`（先核对 `reference/api-levels` 是否已覆盖）、`contribute/index`、`contribute/testing`；P2 `guide/extensions`、`guide/without-spring`、`operate/backup`、`data/agents`、`contribute/backends`、`contribute/design-records`；另有 CONTRIBUTING.md 的 V10 重写，以及 concepts/*、reference/typescript/*、skills/** 各行的逐条核对。

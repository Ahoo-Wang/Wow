# 查询文档信息架构设计

## 背景

当前中英文查询指南各由一个 `guide/query.md` 页面承担。页面同时解释过滤表达式、Kotlin DSL、数据查询、快照聚合、HTTP、兼容迁移、Query Model Schema、Spring 查询后端注册与查询执行边界，导致不同层次的内容被压缩在一起：

- `FilterExpression` 的四十余个操作符只有一张汇总表，缺少按任务组织的 JSON 与 Kotlin 示例；
- 数据查询的公共模型、快照模型和事件流模型没有清晰分层；
- 聚合查询的公共 AST、快照聚合与事件流聚合没有形成完整能力矩阵；
- 查询后端 `QueryService`、Spring `QueryServiceProxy`、`QueryGateway` 与原始 Factory 的责任容易混淆；
- 运行时 Query Model Schema、静态 OpenAPI 字段扩展与通用 JSON Schema 混在同一页面；
- 快照聚合已有场景示例，事件流聚合与更多分析模式尚未进入主查询文档；
- `wow-apiclient.query` 提供的响应式、同步、类型化、state-only、dynamic 与聚合 API 没有在查询分区中成为一等入口。

本设计在保留现有查询入口 URL 的前提下，把查询文档拆成任务清晰、双语镜像、事实单一归属的页面体系。

## 已验证的查询模块模型

### 查询语言

公共查询语言包含三组合同：

1. `FilterExpression`：逻辑字段、元数据过滤、逻辑组合、比较、字符串、集合、存在性、Element、全文搜索与相对时间；
2. 数据查询：`SingleQuery`、`ListQuery`、`PagedQuery`，以及直接使用 `FilterExpression` 的 count；
3. 聚合查询：`AggregationQuery`、Elements、groups、metrics、数值表达式、sort 与 limit。

数据查询与聚合查询共享 `FilterExpression` 和逻辑字段，但返回模型不同。数据查询支持 typed 与 dynamic 文档、projection、sort 和 pagination；聚合查询始终返回动态表格行。

### 查询模型

查询语言可作用于两个模型：

| 查询模型 | 数据根 | JVM 数据查询 | JVM 聚合查询 |
|---|---|---|---|
| SNAPSHOT | `MaterializedSnapshot<S>`，业务状态位于 `state` | single/list/paged/count | 支持 |
| EVENT_STREAM | `DomainEventStream`，事件数组位于 `body` | single/list/paged/count | 支持 |

快照查询默认追加 `DELETION = ACTIVE`；事件流查询保留完整历史，不追加删除过滤。事件流聚合展开 `body` 后，Element filter、group 和 metric 字段相对单个事件；payload 位于 `body.body`。

### JVM 入口与执行链

Spring 应用的常用 JVM 入口不是原始 Factory，而是聚合级查询后端 Bean：

```text
查询 DTO / DSL
  -> SnapshotQueryService<STATE> / EventStreamQueryService
  -> Spring QueryServiceProxy
  -> SnapshotQueryGateway / EventStreamQueryGateway
  -> QueryFilter chain
  -> Tail Filter
  -> SnapshotQueryServiceFactory / EventStreamQueryServiceFactory
  -> MongoDB / Elasticsearch QueryService
```

`SnapshotQueryServiceRegistrar` 根据聚合状态类型注册 `SnapshotQueryService<STATE>` 的 `ResolvableType` Bean。Bean 名为 `{contextAlias.}{aggregateName}.SnapshotQueryService`。`EventStreamQueryServiceRegistrar` 也按聚合注册 Bean，但 EventStream service 没有状态泛型，因此多个候选时需要按 Bean 名区分。

Proxy 保留后端服务的 `name` 与 `namedAggregate`，并把 single/list/paged/count/aggregation 转交 Gateway。若同名 Bean 已存在，Registrar 保留该 Bean，不再包装；若 Gateway 不可用，则直接返回 Factory 创建的原始 service。两种情况都不能声称已执行 Gateway 策略链。

### Gateway 与策略边界

`QueryGateway` 为每次订阅创建 `QueryContext`，设置 `QueryType`，执行过滤链，并由 Tail Filter 选择原始后端 service。直接调用 Factory 会绕过 Gateway、查询重写、ABAC 与结果脱敏。

内置边界并不完全对称：

- WebFlux `RewriteRequestFilter` 在 Gateway 前向快照和事件流查询追加 tenant、owner、space；
- `HttpQueryGuardFilter` 同时属于两个 Gateway，但只有 Reactor Context 中存在 `ServerRequest` 时才生效；
- `AbacQueryFilter` 只属于 `SnapshotQueryGateway`；
- 快照 masking 跳过 count 与 aggregation；
- 事件流 masking 只处理 dynamic single/list/paged 结果，不处理 typed 结果或 aggregation。

详细认证与授权仍由数据权限指南负责，查询文档只说明执行位置和绕过条件。

### Query Model Schema

SNAPSHOT 与 EVENT_STREAM 都通过 Query Model Schema 解析：

```text
system declaration
  + JsonQuerySchemaSource
  + classpath declarations
  + Bean registrations
  + working-directory declarations
  -> QuerySchemaMerger
  -> MongoDB / Elasticsearch backend adapter
  -> QueryModelSchema
  -> QuerySchemaResolver
```

Schema 将逻辑字段绑定为具体 capability 与物理路径。解析结果为 `EXACT`、`COMPATIBLE` 或 `INCOMPATIBLE`；`COMPATIBLE` 模式接受前两者，`STRICT` 只接受 `EXACT`。

Schema 不可用时，`COMPATIBLE` 只允许不引用系统 tags 的查询沿用原路径回退；涉及内置 ABAC tags 的查询仍失败关闭。

MongoDB 根据索引与可选 validator 证明能力；Elasticsearch 根据 mapping、multi-field、nested、doc values 与 runtime field 证明能力。自定义 filter converter 会使内置 Query Model Schema 不可用，除非调用方提供相应能力实现。

快照与事件流分别发布 `snapshot/schema`、`event/schema` 及各自的 `schema/refresh` HTTP 路由。这些模型级路由不生成 tenant、owner 或 aggregate-ID 变体。

Provider 暴露仍不完全对称：Spring `SnapshotQueryServiceProxy` 本身不实现 `QueryModelSchemaProvider`，`EventStreamQueryServiceProxy` 会委托 provider；但两类 Schema HTTP handler 都直接从对应原始 Factory 取得 provider，因此不能由 Proxy 是否实现 Provider 推断 HTTP 暴露能力。

### HTTP 与 API Client 能力矩阵

| 数据模型 | HTTP 数据查询 | HTTP 聚合 | HTTP Schema | API Client |
|---|---|---|---|---|
| SNAPSHOT | single/list/paged/count，并提供 state-only 变体 | `snapshot/aggregation`，JSON 或 SSE | `snapshot/schema` 与 `snapshot/schema/refresh` | 响应式与同步接口 |
| EVENT_STREAM | list/paged/count，以及按 ID/version load；没有 single 查询路由 | `event/aggregation`，JSON 或 SSE | `event/schema` 与 `event/schema/refresh` | 无内置事件流查询客户端 |

`wow-apiclient.query` 的 `ReactiveSnapshotQueryApi<S>` 与 `SynchronousSnapshotQueryApi<S>`组合 single/list/paged/count。Aggregation 刻意使用独立的 `ReactiveSnapshotAggregationQueryApi` 与 `SynchronousSnapshotAggregationQueryApi`，没有合并进普通 SnapshotQueryApi。

客户端支持 typed、state-only 与 dynamic 结果。响应式 single 把 HTTP 404 转为 `Mono.empty()`，同步 single 转为 `null`；其他错误继续传播。客户端不读取运行时 Query Model Schema，也不替代服务端 Gateway、Schema resolver 或 HTTP guard。

## 目标

- 顶层查询入口统一命名为“查询” / “Query”。
- 保留现有 `guide/query.md` URL，将其改为能力总览和选择入口。
- 将原“读模型与查询” / “Read Models and Queries”导航分组融合为可点击的“查询” / “Query”父级；现有投影与数据权限页面作为该父级下的独立专题保留。
- 为查询后端 QueryService、Query API Client、QueryGateway、FilterExpression 与 Query Model Schema 建立独立权威页面。
- 先定义数据查询与聚合查询的公共合同，再分别解释快照和事件流模型差异。
- 为快照聚合与事件流聚合增加任务导向的分析场景和可执行示例。
- 中文作为内容基线，中英文页面的结构、代码、限制与技术语义保持一致。
- 与数据权限、OpenAPI、JSON Schema、WebFlux、API Client 和后端扩展页维持单一事实来源。

## 非目标

- 不修改查询 API、后端行为、路由、Schema、配置或客户端实现。
- 不修改已由当前 `main` 提供的 EventStream HTTP/OpenAPI/Schema 路由，也不新增 EventStream API Client。
- 不把所有操作符、single/list/paged/count 各拆成一个薄页面。
- 不复制数据权限安全闭环、完整 WebFlux 配置、OpenAPI 生成流程或后端 mapping 手册。
- 不移动或删除现有 `guide/query.md`。
- 不承诺未被当前源码、测试或后端能力证明的跨后端一致性。

## 信息架构

查询核心文档包含十二个页面；导航同时纳入已有的投影与数据权限页面：

```text
查询                                              guide/query.md
├── 查询网关                                      guide/query/query-gateway.md
├── 查询后端                                      guide/query/query-backend.md
├── 查询 API 客户端                               guide/query/query-api-client.md
├── 过滤条件                                      guide/query/filter-expression.md
├── 数据查询                                      guide/query/data-query.md
│   ├── 快照查询                                  guide/query/snapshot-query.md
│   └── 事件流查询                                guide/query/event-stream-query.md
├── 聚合查询                                      guide/query/aggregation-query.md
│   ├── 快照聚合查询                              guide/query/snapshot-aggregation.md
│   └── 事件流聚合查询                            guide/query/event-stream-aggregation.md
├── 查询模型 Schema                               guide/query/query-model-schema.md
├── 投影                                          guide/projection.md
└── 数据权限                                      guide/data-access.md
```

英文镜像使用相同路径与结构：

- Query
- Query Gateway
- Query Backend
- Query API Client
- Filter Expressions
- Data Queries
- Snapshot Queries
- Event Stream Queries
- Aggregation Queries
- Snapshot Aggregation
- Event Stream Aggregation
- Query Model Schema
- Projection
- Data Access Control

不新增 `query/index.md`。VitePress 使用现有 `query.md` 作为“查询”导航父级入口，`query/` 目录只承载十一篇新增子页面；投影与数据权限保持原路径和独立内容职责。仓库已有 `migration.md` 与 `migration/` 子目录的同类结构。

## 页面内容合同

### 查询总览

总览回答“查询什么、返回什么、从哪里调用”：

- 展示 SNAPSHOT/EVENT_STREAM × 数据查询/聚合查询的二维能力矩阵；
- 区分本地 QueryService、远程 Query API Client 与服务端 QueryGateway；
- 展示公共执行链，但不展开过滤器实现；
- 给出按任务选择的最短阅读路径；
- 明确 JVM 能力与 HTTP/OpenAPI/API Client 能力不对称。

总览不保留完整操作符表、聚合案例或配置详情。旧页面的重要标题可保留简短摘要与子页面链接，避免有意义的历史锚点直接失去上下文。

### 查询网关

该页是策略执行边界的权威页面，也是总览后的第一篇：

- QueryServiceProxy、QueryGateway、QueryContext、QueryType、FilterChain 与 Tail Filter；
- 每次订阅创建独立 context 的响应式语义；
- Snapshot/EventStream Gateway 与 model-specific filters；
- WebFlux RewriteRequestFilter 在 Gateway 前追加请求作用域；
- HttpQueryGuardFilter 只在 ServerRequest context 中生效；
- ABAC 与 masking 的实际适用范围；
- 直接 Factory、自定义同名 Bean 和 Gateway 缺失的绕过边界；
- QueryHandler 到 QueryGateway 的类型、Bean 名与 FilterType 迁移。

认证、Principal 绑定和失败关闭策略只摘要并链接数据权限页。

### 查询后端

该页是应用内 JVM 查询入口与后端执行合同的权威页面：

- `QueryService<R>`、`SnapshotQueryService<S>` 与 `EventStreamQueryService` 的关系；
- `SnapshotQueryService<STATE>` 类型化 Bean 的构造器注入示例；
- Registrar、Bean 名、`ResolvableType` 与多候选区分方式；
- QueryServiceProxy 对 single/list/paged/count/aggregation 的转发；
- Snapshot/EventStream QueryServiceFactory 的聚合路由、缓存与后端选择；
- typed、dynamic 与聚合结果的返回形态；
- 同名自定义 Bean、Gateway 缺失和直接 Factory 的绕过边界。

该页不展开 MongoDB/Elasticsearch 的物理编译细节，而是链接对应扩展页；Gateway 的过滤链也只摘要并回链“查询网关”。“查询后端”是文档概念名，源码中的公开类型仍使用 `QueryService`。

### 查询 API 客户端

该页是 `wow-apiclient.query` 的查询侧权威页面：

- SnapshotSingle/List/Paged/Count/AggregationQueryApi 的接口层次；
- Reactive 与 Synchronous 组合接口；
- typed、state-only 与 dynamic 方法和 DSL 执行扩展；
- single 404 的 empty/null 语义；
- 普通 SnapshotQueryApi 与独立 AggregationQueryApi 的能力边界；
- 资源路径常量与 `@PostExchange` 只作为已实现合同说明，不复制完整 OpenAPI；
- 当前没有 EventStream 查询客户端；
- 客户端不执行字段发现、Schema 校验、授权或 HTTP 成本保护。

通用 API Client 扩展页继续负责依赖安装、CoApi、服务发现、命令客户端与错误类型，并将查询章节缩成摘要后链接本页。

### 过滤条件

该页完整说明 `FilterExpression`：

- `LogicalField` 语法与系统元数据字段；
- constants、metadata、logical、comparison、string、collection、range、presence、deletion、Element、search 与 relative-time 操作符；
- 每类操作符的 JSON 形状与 Kotlin DSL 对照；
- 一个 DSL block 的隐式 AND，以及显式 AND/OR/NOR；
- `path` 的词法作用域与 `elementMatch` 的独立元素作用域；
- root-only filters，以及不能进入 Element predicate 的过滤器；
- 快照删除默认值和事件流完整历史差异；
- Search 与时间语义的后端能力边界；
- `Condition`、`Operator`、`ConditionDsl`、`op`/`operator` 的兼容迁移。

### 数据查询

该页只定义数据查询公共合同：

- SingleQuery、ListQuery、PagedQuery 与 count；
- filter、projection、sort、limit、pagination 的请求形状；
- 页码从 1 开始，JVM `ListQuery.limit = 0` 表示无限；
- typed、state-only 与 dynamic 结果的区别；
- count 请求体直接是 FilterExpression；
- Kotlin DSL 与等价 JSON；
- DTO 构造约束和 HTTP guard 是不同边界。

该页不指定 `state.*` 或 `body.*`，数据模型路径分别由两个子页面负责。

### 快照查询

该页说明 SNAPSHOT 模型：

- MaterializedSnapshot 的系统字段与 `state` 业务根；
- `pathState` 与完整逻辑路径；
- 默认 `DELETION = ACTIVE` 及显式覆盖规则；
- SnapshotQueryService typed/dynamic 调用；
- single/list/paged/count 与 state-only HTTP 路由矩阵；
- JSON 与 SSE 返回差异；
- Query Model Schema route、OpenAPI 与 API Client 的后续链接；
- MongoDB/Elasticsearch 只做差异摘要并链接扩展页。

### 事件流查询

该页说明 EVENT_STREAM 模型：

- DomainEventStream envelope 与 `body` 事件数组；
- id、aggregateId、tenantId、ownerId、spaceId、version、createTime 等系统字段；
- 事件元数据与 `body.body` payload 路径；
- 不追加快照删除 guard；
- JVM single/list/paged/count 与 typed/dynamic 调用；
- HTTP 数据查询只发布 list/paged/count/load，没有 single 查询路由；
- aggregation 与 Schema 使用独立的 EventStream HTTP/OpenAPI 合同；当前没有 EventStream API Client；
- payload 字段能力依赖声明与后端 mapping。

### 聚合查询

该页定义公共 AggregationQuery AST 与结果合同：

- root filter、ordered Elements、groupBy、metrics、sort 与 limit；
- 第一个 Element 路径绝对，后续 Element 路径相对当前元素；
- Element filter 相对自身元素，group/metric 相对最内层元素；
- TERMS、HISTOGRAM、DATE_HISTOGRAM；
- COUNT、ANY、SUM、AVG、MIN、MAX 与数值表达式；
- alias、effective sort 与结构上限；
- 无 group 的单行汇总、空输入、null、有限 Double 与 ANY 不稳定语义；
- `COUNT` 统计当前最内层作用域，是否 expand 会改变统计单位；
- 分析目标、数据来源与统计单位的选择矩阵。

### 快照聚合查询

该页把公共 AST 应用于当前状态和集合元素，并提供以下场景：

1. 当前状态分类统计；
2. 带条件的整体 KPI 汇总；
3. 数值区间分布；
4. 按业务时间观察趋势；
5. 展开集合后的 Top-N；
6. `价格 × 数量 - 优惠` 等派生指标；
7. 多维分组，例如状态 × 渠道；
8. 使用 ANY 补充展示字段并说明不稳定语义。

每个场景包含业务问题、统计单位、Kotlin DSL、等价 HTTP JSON、示例结果与路径/Schema/后端边界。页面还说明 `snapshot/aggregation`、JSON/SSE、API Client、HTTP guard、结果 masking 跳过，以及自定义 QueryService 默认 aggregate 可能不支持。

### 事件流聚合查询

该页把公共 AST 应用于历史事件流，并提供以下场景：

1. 展开 `body` 后按事件名称统计频率；
2. 按 revision 或 bodyType 分析事件版本；
3. 在根作用域按首个事件的 createTime 统计 EventStream 创建趋势；
4. 按 tenant 或 owner 分析历史活动量；
5. 对比根级 EventStream 数量与展开后的领域事件数量；
6. 按事件 payload 字段分析业务变化。

每个场景必须声明统计单位，并提供 Kotlin/JVM 与等价 HTTP JSON；页面说明 `event/aggregation` 的基础、tenant、owner 路由变体以及 JSON/SSE 响应。payload 场景必须说明 Query Schema 声明、MongoDB 可查询存储、Elasticsearch 外层 `body` nested 关系以及 `body.body` payload 字段的可聚合 mapping 限制。页面明确当前仍没有内置 EventStream API Client。

### 查询模型 Schema

该页是运行时查询能力的权威页面：

- QueryModel.SNAPSHOT 与 QueryModel.EVENT_STREAM；
- system、JSON Schema、classpath、Bean、working-directory sources 及优先级；
- extension root、冲突检测与系统字段不可覆盖；
- backend adapter、logical field、physical binding 与 capabilities；
- EXACT、COMPATIBLE、INCOMPATIBLE 和两种 validation mode；
- 动态字段、Element scope、时间 semantic type 与值类型校验；
- Schema unavailable 与 custom converter 边界；
- 仅用于聚合专用快照 request body 的静态 OpenAPI `x-wow-query-fields`、通用 JSON Schema 与运行时 Query Model Schema 的区别；
- COMPATIBLE 的 Schema unavailable 回退不会放宽系统 tags 查询；
- snapshot 与 event 的 schema/refresh HTTP 路由及其无 tenant/owner 变体；
- 两类 handler 从各自原始 Factory 读取 provider，以及 Snapshot/EventStream proxy 的实现差异；
- refresh 只更新当前进程缓存，不修改 mapping 或历史数据。

## 分析案例模板

聚合案例统一使用以下顺序，减少读者在示例间切换成本：

1. **业务问题**：案例回答什么决策；
2. **统计单位**：快照、EventStream 文档还是展开后的元素/事件；
3. **查询**：Kotlin DSL；实际暴露聚合 HTTP 的快照与事件流场景再给等价 JSON；
4. **结果**：展示一到两行代表性动态表格结果；
5. **边界**：路径相对性、Schema capability、后端和传输限制。

示例只使用当前公开 DSL 与 wire shape，不创建新的 builder、helper 或后端专用语法。

## 与相邻文档的责任边界

| 主题 | 权威页面 | 查询分区只保留 |
|---|---|---|
| 身份认证、tenant/owner/space、ABAC 安全闭环 | `guide/data-access.md` | 执行位置与链接 |
| OpenAPI 生成、RouteCatalog、operation/component | `guide/open-api.md` | 实际查询路由矩阵与链接 |
| 通用 JSON Schema 与 OpenAPI Schema builder | `guide/advanced/schema.md` | 与 Query Model Schema 的区别 |
| WebFlux 配置、HTTP guard 默认值与错误映射 | `guide/extensions/webflux.md` | 查询侧影响摘要 |
| CoApi 安装、服务发现、命令客户端 | `guide/extensions/apiclient.md` | 查询 API 的入口链接 |
| MongoDB filter/pipeline/index/validator | `guide/extensions/mongo.md` | 公共语义与差异链接 |
| Elasticsearch mapping/multi-field/nested/PIT | `guide/extensions/elasticsearch.md` | 公共语义与差异链接 |

一个事实只在一个页面完整解释，其他页面使用一到两句摘要和稳定链接。

## 导航与链接兼容

- `documentation/docs/{zh,en}/guide/query.md` 保留原路径并成为可点击父级；
- `sidebar.zh.ts` 与 `sidebar.en.ts` 把原“读模型与查询” / “Read Models and Queries”分组整体改为“查询” / “Query”可折叠父级，不再保留同名子项；
- 查询总览后的第一项固定为查询网关，随后是查询后端与查询 API 客户端；
- 数据查询与聚合查询自身可点击，并各包含两个模型子页面；
- 投影与数据权限排在查询核心子页面之后，保留现有 URL 与独立权威内容，不把正文并入查询总览；
- 新增十一对中英文子页面，不创建只包含链接的空壳页；
- 现有指向 `query.md` 的链接保持可用；
- 原来以“查询服务”/“Query Service”指代整个查询分区的链接文字改为“查询”/“Query”；
- 真正讨论 Spring QueryService 或 Factory 后端的上下文改链到 `query/query-backend.md`；
- 通用 API Client 页的查询章节改为摘要并链到 `query/query-api-client.md`；
- 旧 `query.md` 的关键主题标题保留简短摘要或明确迁移链接，避免历史锚点失去语义。

## 错误与边界的写法

错误放在最接近责任所有者的页面，不创建集中式错误大全：

- DTO 构造、严格 JSON 解码和兼容输入错误放在过滤/数据/聚合合同页；
- Schema conflict、unavailable 和 validation rejection 放在 Query Model Schema 页；
- Gateway filter、HTTP guard、策略绕过与 masking 边界放在查询网关页；
- 自定义 QueryService 默认 aggregation unsupported 放在对应聚合页与查询后端页；
- 后端 mapping、index、PIT、Mongo pipeline 与结果规范化错误链接各后端扩展页；
- 认证与授权失败关闭要求链接数据权限页。

文档不得把 404 empty/null、空聚合摘要、Schema compatible fallback 或后端空结果混为同一种“没有数据”。

## 验证策略

实现文档拆分后执行：

```bash
git diff --check

./gradlew \
  :wow-query:check \
  :wow-schema:check \
  :wow-apiclient:check \
  :wow-webflux:check \
  :wow-openapi:check

cd documentation
pnpm docs:build
```

另外完成以下静态核对：

- 十一个新增中文页面与十一个英文镜像一一对应；
- 两个 sidebar 的“查询”父级、核心主题、投影与数据权限层级、顺序和链接一致；
- 中英文页面的章节职责、代码、限制、默认值和能力矩阵一致；
- 所有原 `query.md` 入链仍可解析，更新后的深链目标存在；
- 搜索旧标题“查询服务”/“Query Service”，逐项区分它是在指查询分区还是具体 QueryService；
- 搜索 EventStream aggregation HTTP、OpenAPI、Schema route 和 API Client 表述，确保准确表达已新增的 HTTP/Schema 能力与仍缺失的 EventStream Client；
- 搜索 Factory 示例，确保都标记为受信原始入口；
- 搜索聚合案例中的每个 `expand`，确认统计单位与路径相对性一致。

MongoDB 与 Elasticsearch EventStream aggregation 的现有共享 TCK 是后端合同证据。当前本地环境没有可用 Docker，定向 integrationTest 未进入查询逻辑；若实现阶段仍无 Docker，必须记录 `MISSING EVIDENCE`，不得声称本地完成双后端运行验证。

## 完成条件

- 查询侧栏以“查询” / “Query”为可点击父级，呈现十二篇查询核心文档及已有的投影、数据权限专题；
- 查询总览后的首项是查询网关，投影与数据权限保留独立页面并归入同一导航父级；
- 原 `guide/query.md` URL 保持可用并成为有效总览，不是跳转空壳；
- QueryGateway、查询后端 QueryService 与 Query API Client 三种入口责任明确；
- 数据查询和聚合查询的公共合同只维护一份；
- 快照/事件流四个能力页面准确表达 JVM、HTTP、OpenAPI、Schema 与 Client 差异；
- 快照与事件流聚合案例覆盖已确认的分析场景，并明确统计单位；
- FilterExpression 操作符、组合与作用域不再只依赖一张粗略总表；
- Query Model Schema 与 JSON Schema/OpenAPI 静态字段目录清晰分离；
- 相邻权威页面没有被复制，所有摘要链接可用；
- 中英文镜像一致，VitePress 构建和相关模块检查通过；
- 没有查询源码、协议、路由、配置、依赖、生成文件或发布流程变更。

# 数据集查询与 ClickHouse 后端设计

日期：2026-10-10。状态：§9 的决定已由用户确认（2026-10-10），Q5 于 S1 修订；S0 原型已完成（§11）；S1 已实施（Ahoo-Wang/Wow#4116，后续 Ahoo-Wang/Wow#4120）；S2 实施中。

基线：`main` `d48d915e5`。本文建立在[查询子系统目标架构](2026-09-24-query-target-architecture-design.md)（下称“查询架构”）之上，沿用它的原理编号 P1～P9 与设计名；wow-bi 的布局见 [wow-bi 重构设计](2026-10-09-wow-bi-refactor-design.md)。

## 1. 问题

wow-bi 已经把每个聚合的命令、状态同步到 ClickHouse，数据分析师在 ClickHouse 里把多个聚合加工成宽表。宽表能回答跨聚合的问题（订单 × 客户 × 商品），但 Wow 的查询与 view-engine 用不到它：

- 查询子系统的单位是**一个聚合的一个读模型**：Catalog 以 `(NamedAggregate, QueryModel)` 为键编译 schema，`QueryStorageLocator.storage(namedAggregate, model)` 按这个键找存储，REST 路由是 `/{context}/{aggregate}/snapshot/…`；
- 读模型只有 Snapshot 与 EventStream 两个，存储只有 MongoDB 与 Elasticsearch。

目标：让宽表成为 Wow 的查询目标，经同一套准入、能力描述与治理交给 view-engine，使 Record、Analysis、Dashboard 能观察跨聚合的数据。

## 2. 第一性原理：关联放在哪里

跨聚合分析的本质是“查询目标横跨多个聚合”。关联只有两个去处：查询时在协议里 JOIN，或写入时在存储里物化。

| | 协议加 JOIN | 宽表物化（选定） |
|---|---|---|
| 协议 | 引入关联键、基数、扇出，AST 与准入大幅膨胀 | 不变 |
| 语义唯一（P1） | MongoDB、Elasticsearch 做不了通用 JOIN，只能报能力缺失或做残余 | 宽表是单表，现有语义直接适用 |
| 安全（P4） | 每张表的范围、ABAC、脱敏都要在 JOIN 中逐表注入 | 每个目标一份范围与敏感声明 |
| 成本 | 请求时 JOIN 大表 | 分析师用物化视图、字典、ReplacingMergeTree 预先算好 |
| 与现状 | 不符 | 分析师已经在做 |

由此得出本文的两条主张：

1. **查询协议不引入 JOIN。** Wow 的一次查询始终针对一个目标；跨聚合的关联属于宽表。
2. **新增的是查询目标，不是查询语言。** 需要做的事分成两件：一个不属于任何聚合的查询目标**数据集（Dataset）**，这是架构工作；一个 **ClickHouse 查询后端**，这是翻译工作。查询 AST、准入、能力表、预算、脱敏、能力描述、错误目录全部复用。

## 3. 复用与缺口

| 构件 | 现状 | 数据集需要 |
|---|---|---|
| 查询 AST（`AggregationQuery` 等） | TERMS、HISTOGRAM、DATE_HISTOGRAM、DATE_PART、dense、having、表达式、DERIVED、FIRST/LAST、PERCENTILE、DISTINCT_COUNT | 不变 |
| `QueryModel` | 开放的值类型，代码已预留“没有 profile 的自定义模型” | 新增 `DATASET` |
| `QueryModelProfile` | Snapshot 与 EventStream 两份，封闭 | 数据集的身份、时间与范围字段来自声明（§4.3） |
| Catalog 的键 | `(NamedAggregate, QueryModel)` | 不变：数据集占用命名槽位，以 `DATASET` 模型区分（§4.1）；侵入核心的是 profile 随 schema 而定 |
| `QueryBackend` 四个原语 | `stream`、`page`、`count`、`aggregate`，与存储无关 | ClickHouse 实现 |
| `QueryStorageFacts` | `bindings`、`capabilities`、`approximateMetrics`、`StorageSupport` | ClickHouse 适配器从 `system.columns` 与声明推导 |
| 准入、预算、脱敏、描述、错误目录 | 已有 | 不变 |
| view-engine `ViewSource` | 即 wow-client `QueryApi`（`paged`、`cursor`、`aggregate`、`describe`）；定义的 `source` 只是一个键 | 形状相同的 `DatasetQueryApi`，内核不改 |
| wow-bi | ClickHouse client v2（`clickhouse-client-v2` 已在版本目录）、类型映射、状态展开计划与敏感处理 | 复用驱动；§7 复用展开计划生成数据集 |

能力表（P2）让后端之间的能力不对称不违反 P1：语义只定义一次，ClickHouse 不授予的能力（例如全文检索）在描述里就不存在，view-engine 据此隐藏操作。

## 4. 数据集

### 4.1 查询目标

数据集与聚合一样**在限界上下文内命名**，并占用聚合的命名槽位：一个数据集由（上下文名, 数据集名）标识，复用 `NamedAggregate`，以新的 `QueryModel.DATASET` 与聚合的两个读模型区分（Q5，S1 修订）。

- 网关、`QueryPolicy` 与 `QueryFilter` 读到的 `QueryContext.namedAggregate`、观测指标的标签、审计、游标令牌、Catalog 的键 `(NamedAggregate, QueryModel)` 都不变。这些是应用实现策略与观测时写的面，换成新的目标类型会让它们在源码上破坏。
- **命名不冲突**：同一上下文里数据集不能与聚合同名，Catalog 构造时拒绝这一对。因此按聚合名生效的策略（例如 `ViewStoreQueryPolicy`）天然不作用于数据集。
- `QueryGateway` 的十个冻结方法不变；新增 `DatasetQueryGateway`，记录是 `ObjectNode`，类型化方法与动态方法返回同样的记录。
- `QueryBackendProvider` 新增 `dataset: DatasetQueryBackendFactory?`，工厂接收整个 `QueryDataset`，从它的 `storage` 读取存储绑定。
- Catalog 的定期重校验（查询架构 §5.2）同样覆盖数据集：宽表由分析师在部署之外修改，正是需要重校验的典型。

### 4.2 声明：契约是代码，存储事实只做校验

宽表由分析师掌控，对外契约必须随应用发布，与 view-engine“定义是代码”一致。声明文件放在 `META-INF/wow/dataset/{context}.{dataset}.json`，工作目录的 `config/wow/dataset/{context}.{dataset}.json` 整体替换它，与查询 schema 声明文件的位置约定相同。资源只能按名查找、不能扫描，所以服务提供哪些数据集由配置列出（S3）：

```json
{
  "title": "订单宽表",
  "identity": "order_id",
  "eventTime": "updated_at",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "bi_db.order_customer_wide", "deduplicate": "FINAL" },
  "fields": {
    "order_id": { "title": "订单号" },
    "status": { "enum": [{ "value": "PAID", "description": "已支付" }] },
    "amount": { "semantic": { "type": "MONEY", "currency": "CNY" } },
    "customer_phone": { "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
  }
}
```

- `fields` 复用 `QueryFieldDeclaration` 的词汇，另加只有数据集能写的 `sensitive`；字段名就是列名，第一期只支持平铺列。
- `storage` 是查询后端自己的绑定（ClickHouse 的表与去重方式），核心不解释，原样交给后端。
- **存储事实**由 `system.columns` 与 `system.tables` 读取：列类型、是否 `Nullable`、是否 `LowCardinality`、表引擎。
- **一致性检查在编译时进行，失败即关闭**：声明的列不存在、类型族不符、`scope` 列不存在或可为 null、`identity` 可为 null、`deduplicate: FINAL` 用在不支持 `FINAL` 的引擎上，都会让编译失败；已发布的版本保留并告警（查询架构 §5.2）。
- 没有在 `fields` 中出现的列**不暴露**。宽表新增列不会悄悄变成可查询字段。

### 4.3 数据集的 profile

数据集没有内置 profile，它的事实来自声明：

| 事实 | 来源 | 缺省时 |
|---|---|---|
| 身份字段 | `identity` | 不支持游标查询（`PagingSupport.keyset = NONE`），也不支持 FIRST/LAST 的默认 `orderBy` 决胜 |
| 时间字段 | `eventTime` | FIRST/LAST 必须显式写 `orderBy`；view-engine 的 `timeField` 由定义自行声明 |
| 范围字段 | `scope.tenant`（必填）、`owner`、`space` | — |
| 默认范围 | 无 | 删除语义属于宽表，不由 Wow 追加 |

### 4.4 治理

- **范围**：`scope.tenant` 必填，它就是 `requiredScope`。调用方范围（`QueryRequestScope`、CoSec）在准入第 2 步强制追加到这些列上，与 Snapshot 相同。“范围缺失时拒绝”开关对数据集同样生效。
- **ABAC**：资源标签只存在于 Snapshot 的 `tags` 系统字段，`AbacQueryPolicy` 只作用于 Snapshot，数据集与 EventStream 一样不受它限制（S1 的现状）。宽表要参与 ABAC，需要在声明里把标签列绑定到 `scope.tags`（`Map(String, Array(String))`），留待后续阶段。
- **敏感等级**：这是对查询架构 §6.4“敏感等级只能以字段注解声明”的**显式例外**。宽表的列没有类型，也就没有注解可以继承，声明是唯一的来源，所以数据集声明可以写 `sensitive`（等级与遮挡方式）。由 wow-bi 展开计划生成的数据集（§7）自动继承源字段的等级，声明只能更严、不能更宽。
- **注入**：标识符只来自编译后的绑定，加反引号引用；取值一律用 ClickHouse 服务端参数（`{p0:String}`）传入，不拼接。
- **纵深防御**：使用只读账号（`readonly=2`，只允许改设置）。行策略可选，不作为安全边界，安全边界在准入。

## 5. ClickHouse 后端

### 5.1 模块与装配

- 新模块 `wow-clickhouse`：ClickHouse 存储适配器与 `DatasetQueryBackend` 实现（`ClickHouseDatasetQueryBackendFactory`）。依赖 `wow-query` 与 `clickhouse-client-v2`（已在版本目录，不新增依赖）。不放进 wow-bi，因为 wow-bi 只生成脚本、不执行查询，二者共享的只有驱动。S3 接入 starter 之前它是 incubating 模块，构建与测试照常，但不发布。
- starter 新增 feature `clickhouse-support`。
- 驱动返回 `CompletableFuture`，以 `Mono.fromFuture` 接入 Reactor；`JSONEachRow` 响应体在一个有界调度器上逐行解码，不占用事件循环线程。

### 5.2 存储事实

| ClickHouse 类型 | `QueryValueType` | 授予的能力 |
|---|---|---|
| `String`、`FixedString`、`LowCardinality(String)`、`Enum8/16` | STRING | EXACT_MATCH、LITERAL_MATCH、RANGE、SORT、AGGREGATE_TERMS |
| `Int*`、`UInt8～32` | INTEGER | EXACT_MATCH、RANGE、SORT、AGGREGATE_TERMS、AGGREGATE_NUMERIC；声明为 `TEMPORAL_EPOCH` 时另有 AGGREGATE_TEMPORAL |
| `UInt64`、`Int128` 及以上 | INTEGER | 同上；以 JSON 整数交付（`output_format_json_quote_64bit_integers = 0`） |
| `Float*`、`Decimal(P, S)` | DECIMAL | EXACT_MATCH、RANGE、SORT、AGGREGATE_NUMERIC |
| `Bool` | BOOLEAN | EXACT_MATCH、SORT、AGGREGATE_TERMS |
| `Date`、`Date32`、`DateTime`、`DateTime64` | STRING + `TEMPORAL_DATE` | EXACT_MATCH、RANGE、SORT、AGGREGATE_TEMPORAL |
| `Nullable(T)` | 同 T | 另授予 PRESENCE |
| `Array(T)`、`Map`、`Tuple` 等 | — | S2 不支持：声明这样的列会让编译失败 |

- **null 与缺省值**：非 `Nullable` 的列用类型缺省值代替 null，无法区分“缺失”与“空值”，所以不授予 PRESENCE，EQ/NE 取 null 时由准入拒绝（查询架构 §5.2“随取值变化的能力”），绝不把 `0` 与 `''` 当作缺失。`Nullable` 列上 NULL 是表里唯一的“缺失”，所以声明 `AbsentValues.AS_MISSING`：EXISTS 即 `isNotNull`，NOT_EXISTS 即 `isNull`。
- **CURSOR_SORT**：只授予非 `Nullable` 的单值列。
- **全文检索**：不授予 FULL_TEXT_*，描述里没有 search 字段。
- `approximateMetrics = {PERCENTILE}`，DISTINCT_COUNT 精确计算（§5.3）。
- **声明一致性**：声明的每个字段必须是表的根列，类型族与声明的类型一致（日期列必须声明 `TEMPORAL_DATE`），声明为非空的字段不能落在 `Nullable` 列上，范围列与身份列不能是 `Nullable`，`FINAL` 需要会合并行的引擎（`Replacing`、`Collapsing` 等 MergeTree）。任何不一致都让编译失败，Catalog 保留上一版本。
- `StorageSupport`：keyset 游标、HAVING（外层查询的条件）、按指标的 top-N 都是 NATIVE；dense 补空为 RESIDUAL，由核心与其他存储共用一份实现；`arrayEquality = NONE`，`parallelArraySort = NONE`，`absentValues = AS_MISSING`。

### 5.3 翻译

| AST | ClickHouse |
|---|---|
| 过滤 | `WHERE`；EQ、IN、范围直接翻译；CONTAINS 用 `position` / `positionCaseInsensitive`；STARTS_WITH、ENDS_WITH 用 `startsWith`、`endsWith`，忽略大小写时先 `lowerUTF8` |
| 相对时间 | 准入已统一换算为绝对时刻（查询架构 §5.4 第 6 步），按列的时区参数化传入 |
| TERMS / HISTOGRAM | `GROUP BY col`、`floor(x / i) * i`；`missingKey` 用 `ifNull` |
| DATE_HISTOGRAM | `toStartOfInterval(toTimeZone(t, tz), INTERVAL 1 unit)`；WEEK 按 ISO 周一 |
| DATE_PART | `toDayOfWeek`、`toDayOfMonth`、`toHour`、`toMonth`（先 `toTimeZone`） |
| dense | `ORDER BY … WITH FILL FROM … TO … STEP …`，窗口为首个实际桶到末个实际桶，与规范一致 |
| COUNT / NUMERIC | `count()`；`sum`、`avg`、`min`、`max`、`stddevPop`、`varPop`，空集返回 null 时用 `*OrNull` 组合子。单个标量 FIELD 按原生类型聚合（`Decimal` 求和不丢精度，规范要求），其他表达式按 `Float64` 计算 |
| DISTINCT_COUNT | `uniqExact`，空集为 0 |
| PERCENTILE | `quantileTDigest(p)`，列入 `approximateMetrics` |
| ANY | `any` |
| FIRST / LAST | `argMin` / `argMax(v, orderBy)`，决胜按身份字段 |
| 指标过滤 | `-If` 组合子（`sumIf`、`uniqExactIf`…） |
| having / sort / limit | `HAVING`、`ORDER BY`、`LIMIT` |
| 分页 | `LIMIT n OFFSET m`；`withTotal` 时并行执行一次 `count()` |
| 游标 | 元组比较 `(a, b, id) > ({p1}, {p2}, {p3})`，按有效排序方向逐项翻转 |
| 去重 | `deduplicate: FINAL` 时加 `FINAL`，描述中标明最终一致 |

规范与 ClickHouse 不一致的地方，由翻译负责对齐，不改规范：除零为 null（`nullIf`）、派生指标的 null 传播、`having` 的 null 判假、IEEE double 比较。

**别名隔离**：ClickHouse 先解析别名、再解析列名，指标别名与列同名（`sum(amount) AS amount`）时会劫持 `WHERE` 与其他指标中的同名列。聚合因此分两层：内层用内部别名（`__g0`、`__m0`）分组与聚合，外层改名为调用方别名、计算派生指标、排序与 limit。

**NE 与 NOT_IN 遇到 NULL**：规范把 null 语义交给后端原生行为（`filter-expression.md`）。ClickHouse 后端与 MongoDB 一致，NE、NOT_IN 也匹配 NULL；S2 的 TCK 把这一选择固定下来。

### 5.4 执行合同

- **按入口的查询限额**：`ClickHouseQueryLimits` 按入口配置（缺省只限制 HTTP：30 秒、4 GiB 内存），以 `max_execution_time`、`max_rows_to_read`、`max_result_rows`、`max_memory_usage` 及对应的 `*_overflow_mode = throw` 下发给服务端；超限作为存储故障返回，不截断结果。不复用 `QueryBudget`：它约束查询的形状（大小、节点数），没有时间、内存或扫描行数的概念。
- **取消**（P9）：每条语句带自己的 `query_id`；订阅取消时放弃请求，并以 `KILL QUERY … ASYNC` 停止服务端查询，同时开启 `cancel_http_readonly_queries_on_client_close`。集成测试断言查询确实在服务端运行过、并在取消后停止。
- **先检查，后 I/O**：准入与翻译都在第一次查询之前完成。
- **并发**：响应体在有界调度器上解码，线程数与排队数都有上限，超出时拒绝而不是无限排队。
- **缓存**：S2 未实现。可选开启 `use_query_cache`，只用于不带调用方范围的查询，与现有缓存规则一致。

### 5.5 正确性

- 共享 TCK（`contractTest`）的语义矩阵增加 ClickHouse 维度，以 Testcontainers ClickHouse 在 `integrationTest` 中执行。
- 重点覆盖：非 `Nullable` 的缺省值、时区与夏令时边界、`Decimal` 精度、`dense` 窗口、`FINAL` 去重、取消后服务端不再执行。

## 6. 交付与接入

- **部署**：数据集查询是 starter 的可选 feature。参考部署是 view-store-server：它已经在 CoSec 网关后面、为 view-engine 服务。领域服务不需要连接 ClickHouse。
- **路由**：`POST /dataset/{name}/{list|paged|cursor|count|aggregate}`，`GET /dataset/{name}/describe`；请求体与响应体与现有查询路由相同。这些是新增的 OpenAPI 契约。
- **wow-client**：新增 `DatasetQueryApi`，与 `QueryApi` 同形，可以直接交给 view-engine 的 `resources`；`defineView` 从 describe 建定义。
- **view-engine**：内核不改。Analysis 与 Dashboard 直接得到跨聚合能力；看板全局筛选跨数据集的字段映射沿用现有机制。

## 7. 与 wow-bi 联动

wow-bi 的状态展开计划已经知道每个展开列的来源路径、类型与敏感等级（`StateExpansionSensitivity`）。据此为每个聚合的 `state_last` 展开视图自动生成数据集声明：

- 单聚合分析可以落到 ClickHouse，而不用迁移 Snapshot；
- 敏感等级自动继承，分析师在这些表上加工宽表时有一份可参照的基线。

不把 Snapshot 读模型本身迁到 ClickHouse：BI 中的状态是 JSON 字符串列（`state`），按嵌套路径查询要靠 `JSONExtract`，性能与语义都不如展开列。

## 8. 不做的事

- 协议里不加 JOIN；Wow 不管理宽表的 DDL，宽表归分析师，Wow 只消费声明。
- 不支持运行时登记数据集：数据集的能力表是部署时的事实；运行时登记会让数据集失去代码评审与发布这道关口。
- 第一期不做全文检索、元素作用域、`Map` 动态键与参数化视图。
- 不在本设计中改变 Elasticsearch 的 DISTINCT_COUNT 实现（`cardinality`，`precision_threshold` 为 40 000）。

## 9. 决定

| # | 问题 | 决定 |
|---|---|---|
| Q1 | 数据集由谁提供 | starter 的可选 feature，参考部署为 view-store-server；领域服务不连 ClickHouse（用户 2026-10-10 确认） |
| Q2 | 数据集声明放在哪里 | 随应用发布的声明文件；不支持运行时登记（用户 2026-10-10 确认） |
| Q3 | 数据集声明能否写敏感等级 | 能，作为查询架构 §6.4 的显式例外；由 BI 生成的数据集自动继承，且只能更严（用户 2026-10-10 确认） |
| Q4 | DISTINCT_COUNT 与 PERCENTILE 用精确还是近似 | 规范不变：DISTINCT_COUNT 是精确的去重个数，用 `uniqExact`；PERCENTILE 近似，用 `quantileTDigest`，经已有的 `approximateMetrics` 写进描述，与 MongoDB（`$percentile` 的 approximate 方法）和 Elasticsearch（TDigest）一致 |
| Q5 | 核心改动方式 | **修订（用户 2026-10-10 确认）**：数据集按（上下文名, 数据集名）复用 `NamedAggregate` 槽位，以 `QueryModel.DATASET` 与每个数据集自己的 profile 区分，与聚合同名时启动失败（§4.1）。原决定是引入 `QueryTarget` 取代 `NamedAggregate`；S1 发现 `QueryContext.namedAggregate` 是应用策略读取的面，替换会造成源码破坏、改动面翻倍。原先反对“伪聚合”的理由（默认 aggregate_id 范围、profile 查找、路由）由独立的模型与 profile 解决 |

## 10. 实施阶段

每个阶段一个 PR，S0 除外。

| 阶段 | 内容 | 证明 |
|---|---|---|
| **S0 原型**（已完成） | 用伪聚合（`contextName = "bi"`、`aggregateName = <dataset>`，`QueryModel("DATASET")`）在一张宽表上翻译过滤、聚合与分页，并生成能力描述 | §11；不合并 |
| **S1 核心**（已实施，#4116、#4120） | `QueryModel.DATASET` 与数据集 profile（内置模型的 profile 为 `BuiltInQueryModelProfile`）；`QueryModelSchema.profile`；`DatasetQueryGateway`；`QueryBackendProvider.dataset`；声明格式与加载；敏感等级例外 | wow-query 单测；ABI dump 的 `-` 行进 `x.Y.0` 的 `## Breaking` |
| **S2 后端**（实施中） | `wow-clickhouse`（incubating）：存储适配器与声明一致性检查、四个原语、keyset 游标、`FINAL`、按入口的限额、取消；TCK `DatasetQueryBackendSpec` | TCK 语义矩阵与规格 + Testcontainers `integrationTest` |
| **S3 接入** | starter `clickhouse-support`；HTTP 路由与 describe；OpenAPI；wow-client `DatasetQueryApi`；view-engine 示例；中英文用户文档 | 路由契约测试（入口为 `HTTP`）、OpenAPI 快照、typescript-contract |
| **S4 BI 联动** | 由 wow-bi 展开计划生成数据集声明，继承敏感等级 | wow-bi golden + 集成测试 |
| 后续 | 协议演进：同比、环比、占比、累计、组内 TopN | 规范定义一次；各后端经 `StorageSupport` 声明 NATIVE、RESIDUAL 或 NONE，ClickHouse 先做 NATIVE |

## 11. S0 原型结论

代码在分支 `claude/clickhouse-dataset-spike`（`6891d8f22`），不合并：`wow-clickhouse` 模块与集成测试 `ClickHouseDatasetQueryIntegrationTest`（Testcontainers `clickhouse-server:24.8.14.39-alpine`），用一张订单 × 客户宽表（13 列，`MergeTree`）经未改动的准入（`QueryAdmission.Trusted`）、残余算子与能力描述执行查询，并与手写 SQL 逐项比对。

**能做到的**，7 项测试全部通过：

- 聚合：TERMS、DATE_HISTOGRAM（`Asia/Shanghai` 按当地月初取键）、DATE_PART、COUNT、SUM、AVG、DISTINCT_COUNT、PERCENTILE、DERIVED、ANY、FIRST、指标过滤，结果与手写 SQL 一致；
- HAVING 与 dense 声明为 RESIDUAL，由核心计算，后端不需要实现；
- 空输入的汇总行符合规范的空集语义（COUNT、DISTINCT_COUNT 为 0，其余为 null）；
- 分页：过滤、忽略大小写的 STARTS_WITH、投影、排序、总数；
- 非 `Nullable` 列上的 IS_NULL 在任何 I/O 之前被准入以 `does not support [PRESENCE]` 拒绝；
- 能力描述可以生成，字段、算子、分组与指标能力随列类型变化，PERCENTILE 列为 `approximate`。

**延迟**：本地 Docker 单节点，每项取 10 次的中位数，只作量级参考，不是基准结论。

| 查询 | 100 万行：Wow / 手写 SQL | 1000 万行：Wow / 手写 SQL |
|---|---|---|
| TERMS + COUNT/SUM/DISTINCT/P95/DERIVED，top 3 | 23 ms / 23 ms | 259 ms / 369 ms |
| 按月 DATE_HISTOGRAM + 指标过滤 | 15 ms / 16 ms | 176 ms / 191 ms |

Wow 路径（准入、翻译、JSON 解码、核心校验）的额外开销落在测量噪声内，成本由 ClickHouse 的扫描决定。

**对后续阶段的影响**：

1. **核心只有两处假设挡住自定义模型**，都在 S1 的范围内：`SystemQuerySchemaSource` 对没有 profile 的模型直接拒绝；`QuerySchemaMerger` 要求扩展字段位于 `state.` 或 `body.` 之下。原型各用一行绕过，`:wow-query:test` 的 7408 项测试中只有断言“拒绝自定义模型”的那一项失败。S1 让数据集 profile 提供系统字段与字段根。
2. **能力描述的 `record.identity` 为空字符串**：view-engine 的 Record 以 `rowKey` 为每行身份，所以数据集要用于明细视图就必须声明 `identity`（§4.3），describe 也要输出它。
3. 翻译规则补进 §5.2、§5.3：数值字段原生聚合、别名隔离、`Nullable` 列的 `AS_MISSING`、NE/NOT_IN 遇到 NULL 的行为。
4. **未覆盖**：HTTP 路由与 view-engine 的端到端（S3）、游标分页（需要 `identity`）、取消与预算设置的验证（S2）、`FINAL` 去重（S2）。

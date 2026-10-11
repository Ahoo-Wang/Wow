# 数据集查询与 ClickHouse 后端设计

日期：2026-10-10。状态：提议，§9 的决定已由用户确认（2026-10-10），未实施。

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
| Catalog 的键 | `(NamedAggregate, QueryModel)` | **泛化为 `QueryTarget`**，唯一侵入核心的改动（§4.1） |
| `QueryBackend` 四个原语 | `stream`、`page`、`count`、`aggregate`，与存储无关 | ClickHouse 实现 |
| `QueryStorageFacts` | `bindings`、`capabilities`、`approximateMetrics`、`StorageSupport` | ClickHouse 适配器从 `system.columns` 与声明推导 |
| 准入、预算、脱敏、描述、错误目录 | 已有 | 不变 |
| view-engine `ViewSource` | 即 wow-client `QueryApi`（`paged`、`cursor`、`aggregate`、`describe`）；定义的 `source` 只是一个键 | 形状相同的 `DatasetQueryApi`，内核不改 |
| wow-bi | ClickHouse client v2（`clickhouse-client-v2` 已在版本目录）、类型映射、状态展开计划与敏感处理 | 复用驱动；§7 复用展开计划生成数据集 |

能力表（P2）让后端之间的能力不对称不违反 P1：语义只定义一次，ClickHouse 不授予的能力（例如全文检索）在描述里就不存在，view-engine 据此隐藏操作。

## 4. 数据集

### 4.1 查询目标

```kotlin
sealed interface QueryTarget {
    data class Aggregate(val namedAggregate: NamedAggregate, val model: QueryModel) : QueryTarget
    data class Dataset(val name: String) : QueryTarget
}
```

- `QuerySchemaCatalog`、`QueryStorageLocator`、`QuerySchemaContext`、`AdmittedQuery` 改以 `QueryTarget` 为键。它们是 `@WowSpi` 或内部 API，改动在 `x.Y.0` 发布，不保留旧签名（`AGENTS.md`「Binary Compatibility」）。
- `QueryGateway` 的十个冻结方法不变；新增 `DatasetQueryGateway`，方法与它对齐，以数据集名定位目标。
- `QueryBackendProvider` 新增 `dataset: DatasetQueryBackendFactory?`，与 `snapshot`、`eventStream` 并列，按数据集名创建 `QueryBackendBinding`。
- Catalog 的定期重校验（查询架构 §5.2）同样覆盖数据集：宽表由分析师在部署之外修改，正是需要重校验的典型。

### 4.2 声明：契约是代码，存储事实只做校验

宽表由分析师掌控，对外契约必须随应用发布，与 view-engine“定义是代码”一致。声明文件放在 `META-INF/wow/dataset/{name}.json` 或 `config/wow/dataset/{name}.json`，与查询 schema 声明文件的位置约定相同：

```json
{
  "title": "订单宽表",
  "table": "bi_db.order_customer_wide",
  "deduplicate": "FINAL",
  "identity": "order_id",
  "eventTime": "updated_at",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "fields": {
    "order_id": { "title": "订单号" },
    "status": { "enum": [{ "value": "PAID", "description": "已支付" }] },
    "amount": { "semantic": { "type": "MONEY", "currency": "CNY" } },
    "customer_phone": { "sensitive": { "level": "CONFIDENTIAL" } }
  }
}
```

- `fields` 复用 `QueryFieldDeclaration` 的词汇；字段名就是列名，第一期只支持平铺列。
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
- **ABAC**：资源标签只存在于 Snapshot 的 `tags` 系统字段。宽表要参与 ABAC，必须在声明里把标签列绑定到 `scope.tags`（`Map(String, Array(String))`）；没有绑定、而主体又有标签时，按“资源缺少标签键”的现有语义处理，并由同一个开关收紧。
- **敏感等级**：这是对查询架构 §6.4“敏感等级只能以字段注解声明”的**显式例外**。宽表的列没有类型，也就没有注解可以继承，声明是唯一的来源，所以数据集声明可以写 `sensitive`（等级与遮挡方式）。由 wow-bi 展开计划生成的数据集（§7）自动继承源字段的等级，声明只能更严、不能更宽。
- **注入**：标识符只来自编译后的绑定，加反引号引用；取值一律用 ClickHouse 服务端参数（`{p0:String}`）传入，不拼接。
- **纵深防御**：使用只读账号（`readonly=2`，只允许改设置）。行策略可选，不作为安全边界，安全边界在准入。

## 5. ClickHouse 后端

### 5.1 模块与装配

- 新模块 `wow-clickhouse`：ClickHouse 存储适配器、`QueryBackend` 实现、数据集声明加载。依赖 `wow-query` 与 `clickhouse-client-v2`（已在版本目录，不新增依赖）。不放进 wow-bi，因为 wow-bi 只生成脚本、不执行查询，二者共享的只有驱动。
- starter 新增 feature `clickhouse-support`。
- 驱动返回 `CompletableFuture`，以 `Mono.fromFuture` 接入 Reactor，不阻塞。

### 5.2 存储事实

| ClickHouse 类型 | `QueryValueType` | 授予的能力 |
|---|---|---|
| `String`、`FixedString`、`LowCardinality(String)`、`Enum8/16` | STRING | EXACT_MATCH、LITERAL_MATCH、RANGE、SORT、AGGREGATE_TERMS |
| `Int*`、`UInt8～32` | INTEGER | EXACT_MATCH、RANGE、SORT、AGGREGATE_TERMS、AGGREGATE_NUMERIC |
| `UInt64`、`Int128` 及以上 | INTEGER | 同上；超出 JSON 安全整数的值以字符串交付，与 Long 的现有约定一致 |
| `Float*`、`Decimal(P, S)` | DECIMAL | EXACT_MATCH、RANGE、SORT、AGGREGATE_NUMERIC |
| `Bool` | BOOLEAN | EXACT_MATCH、SORT、AGGREGATE_TERMS |
| `Date`、`Date32`、`DateTime`、`DateTime64` | STRING + `TEMPORAL_DATE` | EXACT_MATCH、RANGE、SORT、AGGREGATE_TEMPORAL |
| `Nullable(T)` | 同 T | 另授予 PRESENCE |
| `Array(T)` | T，`MANY` | 第一期只授予 EXACT_MATCH（`has`）；ELEMENT_SCOPE 不授予 |

- **null 与缺省值**：非 `Nullable` 的列用类型缺省值代替 null，无法区分“缺失”与“空值”，所以不授予 PRESENCE，EQ/NE 取 null 时由准入拒绝（查询架构 §5.2“随取值变化的能力”）。不采用 `AbsentValues.AS_MISSING`：那会把 `0` 与 `''` 当作缺失，结果是错的。
- **CURSOR_SORT**：只授予非 `Nullable` 的单值列。
- **全文检索**：不授予 FULL_TEXT_*，描述里没有 search 字段。
- `approximateMetrics = {PERCENTILE}`，DISTINCT_COUNT 精确计算（§5.3）。
- `StorageSupport`：分页与聚合各项全部 NATIVE，`maxOffsetWindow = null`，`arrayEquality = NATIVE`，`parallelArraySort = NONE`。

### 5.3 翻译

| AST | ClickHouse |
|---|---|
| 过滤 | `WHERE`；EQ、IN、范围直接翻译；CONTAINS 用 `position` / `positionCaseInsensitive`；STARTS_WITH、ENDS_WITH 用 `startsWith`、`endsWith`，忽略大小写时先 `lowerUTF8` |
| 相对时间 | 准入已统一换算为绝对时刻（查询架构 §5.4 第 6 步），按列的时区参数化传入 |
| TERMS / HISTOGRAM | `GROUP BY col`、`floor(x / i) * i`；`missingKey` 用 `ifNull` |
| DATE_HISTOGRAM | `toStartOfInterval(toTimeZone(t, tz), INTERVAL 1 unit)`；WEEK 按 ISO 周一 |
| DATE_PART | `toDayOfWeek`、`toDayOfMonth`、`toHour`、`toMonth`（先 `toTimeZone`） |
| dense | `ORDER BY … WITH FILL FROM … TO … STEP …`，窗口为首个实际桶到末个实际桶，与规范一致 |
| COUNT / NUMERIC | `count()`；`sum`、`avg`、`min`、`max`、`stddevPop`、`varPop`，空集返回 null 时用 `*OrNull` 组合子 |
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

### 5.4 执行合同

- **预算映射为查询设置**：入口的 `QueryBudget` 映射为 `max_execution_time`、`max_rows_to_read`、`max_result_rows`、`max_memory_usage`，`timeout_overflow_mode = 'throw'`；超限作为 `QueryExecutionException` 返回，不截断结果。
- **取消**（P9）：每次执行生成 `query_id`；订阅取消时取消 future，并开启 `cancel_http_readonly_queries_on_client_close`，由服务端终止查询。
- **先检查，后 I/O**：准入与翻译都在第一次查询之前完成。
- **并发**：执行池有界，满时快速失败，不排队。
- **缓存**：可选开启 `use_query_cache`，只用于不带调用方范围的查询，与现有缓存规则一致。

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
| Q5 | 核心改动方式 | 引入 `QueryTarget`，不用“伪聚合”冒充数据集；伪聚合只用于不合并的原型（§10 S0） |

## 10. 实施阶段

每个阶段一个 PR，S0 除外。

| 阶段 | 内容 | 证明 |
|---|---|---|
| **S0 原型** | 用伪聚合（`contextName = "bi"`、`aggregateName = <dataset>`，`QueryModel("DATASET")`）在一张真实宽表上翻译过滤与聚合，用 view-engine Analysis 端到端跑通 | 价值、延迟、语义差异清单；不合并 |
| **S1 核心** | `QueryTarget`；`DatasetQueryGateway`；`QueryBackendProvider.dataset`；声明格式与加载；数据集 profile；敏感等级例外 | wow-query 单测；ABI dump 的 `-` 行进 `x.Y.0` 的 `## Breaking` |
| **S2 后端** | `wow-clickhouse`：存储适配器、四个原语、预算映射、取消 | TCK `contractTest` + Testcontainers `integrationTest` |
| **S3 接入** | starter `clickhouse-support`；HTTP 路由与 describe；OpenAPI；wow-client `DatasetQueryApi`；view-engine 示例；中英文用户文档 | 路由契约测试（入口为 `HTTP`）、OpenAPI 快照、typescript-contract |
| **S4 BI 联动** | 由 wow-bi 展开计划生成数据集声明，继承敏感等级 | wow-bi golden + 集成测试 |
| 后续 | 协议演进：同比、环比、占比、累计、组内 TopN | 规范定义一次；各后端经 `StorageSupport` 声明 NATIVE、RESIDUAL 或 NONE，ClickHouse 先做 NATIVE |

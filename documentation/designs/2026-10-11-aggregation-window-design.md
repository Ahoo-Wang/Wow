# 聚合查询的窗口计算设计（数据集平台 R4）

日期：2026-10-11。状态：§8 的决定已由用户确认（2026-10-11，「按你推荐，开始 R4a」）；R4a 分两步实施，见 §7：R4a-1 已实施（Ahoo-Wang/Wow#4169），R4a-2 已实施（Ahoo-Wang/Wow#4170）。

基线：`main` `6c2bff7651`。本文是[数据集平台化设计](2026-10-11-dataset-platform-design.md)的 R4（其 §11 T1），建立在[查询子系统目标架构](2026-09-24-query-target-architecture-design.md)的聚合协议与 `StorageSupport` 之上；view-engine 侧的现状见 `typescript/wow-view-engine/docs/design/decisions.md` 的 Q53、Q59 与 D38。

## 1. 问题

BI 平台最常用的几类分析——**占比、累计（含帕累托）、环比与同比、组内排名与 TopN**——都要跨组计算：一组的值依赖其他组。Wow 的聚合协议只能在一组之内算（`DERIVED` 是同一行里指标的四则运算），所以今天这些都由 view-engine 在客户端对拿回来的行计算：

- 行被 `limit` 截断、被「只保留」筛过、时间轴上有不确知的洞时，客户端算出的累计与趋势是错的，所以 view-engine 只好置灰（Q53）；
- 与上一期、去年同期的整段对比要第二条查询和平移范围，被推迟（Q59）；
- 占比只对拿回来的组成立，前 N 组的占比与「全部的占比」说的不是一件事。

这些计算只有在服务端、面对**完整的分组结果**时才算得对。

## 2. 原则

1. **仍然只有一个概念：派生指标**。不加「后处理阶段」。窗口计算是 `DerivedExpression` 的新节点，与指标引用、常量、四则运算一样可以组合：占比是 `指标 / TOTAL(指标)`，同比是 `指标 / PREVIOUS(指标, 一年) - 1`。
2. **对完整的分组结果计算**。窗口在 `limit` 之前、对 filter 选出的全部分组计算，结果不因截断而变；这正是客户端做不到的。
3. **只对可加指标做加和类窗口**。`TOTAL` 与 `RUNNING` 的加和只对 `COUNT`、`SUM` 成立（平均值的合计没有意义），与 view-engine 现有的「占比只对可加指标成立」一致。
4. **能力按函数声明**。每个存储经 `StorageSupport` 按函数声明 NATIVE、RESIDUAL 或 NONE，能力描述发布出去；view-engine 只在声明了的地方用服务端结果，否则沿用今天的行为。

## 3. 协议：派生表达式的窗口节点

在 `DerivedExpression` 上增加四种节点（JSON 的 `type`）：

| 节点 | 含义 | 参数 | 只接受 |
|---|---|---|---|
| `TOTAL` | 指标在一组分组上的合计 | `metric`；`within`：分区的分组别名，缺省为全部分组 | 可加指标 |
| `RUNNING` | 沿一个次序的累计 | `metric`；`order`：分组别名或指标，带方向；`within`；`frame`：最近几行，缺省为从头累计；`function`：`SUM`（缺省）或 `AVG`（移动平均） | `SUM` 只接受可加指标 |
| `PREVIOUS` | 上一期的值 | `metric`；`along`：一个 `DATE_HISTOGRAM` 分组的别名；`shift`：往前几个桶（环比，缺省 1），或日历周期 `YEAR`、`QUARTER`、`MONTH`、`WEEK`（同比、同期） | 任何指标 |
| `RANK` | 在分区内的名次（并列同名次，名次连续） | `metric`；`within`；`direction`：缺省 `DESC` | 任何数值指标 |

例：各城市 GMV 及其占比、按月累计、同比，和每个省取 GMV 前三的城市：

```json
{
  "groupBy": [
    { "type": "TERMS", "field": "province", "alias": "province" },
    { "type": "TERMS", "field": "city", "alias": "city" }
  ],
  "metrics": [
    { "type": "SUM", "expression": { "type": "FIELD", "field": "amount" }, "alias": "gmv" },
    { "type": "DERIVED", "alias": "share", "expression": {
        "type": "BINARY", "operator": "DIVIDE",
        "left": { "type": "METRIC_REF", "metric": "gmv" },
        "right": { "type": "TOTAL", "metric": "gmv", "within": ["province"] } } },
    { "type": "DERIVED", "alias": "rank", "expression": {
        "type": "RANK", "metric": "gmv", "within": ["province"] } }
  ],
  "having": { "type": "CONDITION", "metric": "rank", "operator": "LTE", "value": 3 }
}
```

### 3.1 求值次序

1. filter 选出记录，按 `groupBy` 分组，算出每组的指标；
2. 同一行的派生指标（四则运算，今天的 `DERIVED`）；
3. **窗口节点**：对第 1 步得到的**全部分组**计算；
4. `having`：可以引用窗口指标（组内 TopN 就是 `having rank <= N`）；
5. `sort`、`limit`。

所以 `TOTAL` 是 filter 选出的全部分组的合计，不受 `having` 与 `limit` 影响；`RUNNING`、`RANK` 同理。

### 3.2 `PREVIOUS` 与时间范围

同比要的是「去年同期」的数，而 filter 的时间条件通常只选出今年。规则：

- `PREVIOUS` 的时间范围是**查询自己的范围按 `shift` 平移**：filter 顶层 AND 里，对 `along` 分组字段的范围条件（`GT`/`GTE`/`LT`/`LTE`/`BETWEEN` 与相对时间）整体前移；其余条件不变；
- filter 对这个字段没有顶层范围条件时，平移后就是同一个无界范围，`PREVIOUS` 等于结果里前移的桶；
- 对这个字段的条件不在顶层 AND 里（放在 OR、NOR、`ELEMENT_MATCH`、`EXPRESSION`、`SEARCH` 之下），或取值不是时间点时，范围无法平移，查询在准入时被拒绝（`PREVIOUS_UNSHIFTABLE`），并说明原因。
- 平移在分组时区的本地日历上进行（与 dense 网格同一条时间线）：按桶平移时桶键前移 `shift` 个桶；按 `period` 平移时桶键前移后再落到所在的桶（3 月 31 日前移一个月是 2 月 28 日）。ISO 字符串保持原来的形式（`Z`、带偏移、本地日期时间、日期），epoch 数值保持原来的单位。
- 平移后没有对应桶（上一期没有数据）的，`PREVIOUS` 为 `null`，引用它的算术也是 `null`，与今天除以零得 `null` 一致。

### 3.3 校验（`AggregationQuery.init` 与准入）

- 窗口节点引用的 `metric` 必须是本查询声明的、非派生的指标；`TOTAL`、`RUNNING`（`SUM`）只接受 `COUNT`、`SUM`；
- `within`、`order`、`along` 只能引用本查询的分组别名；`along` 必须是 `DATE_HISTOGRAM`；
- 窗口节点计入现有的表达式深度与节点数上限；每个查询的窗口节点数另设上限（`maxWindows`，缺省 8）；
- `dense` 日期直方图与窗口可以同时使用：补齐的空桶参与 `RUNNING`（值为 0）与 `PREVIOUS`。
- 实现时补充的精确语义：`null` 值不参与 `TOTAL`、`RUNNING`，全为 `null` 时结果为 `null`；`null` 值没有名次；`RUNNING` 在 `order` 上并列的分组按其余分组键升序排列，`frame` 按行计；`RUNNING` 不能按自己的 `within` 分组排序；`PREVIOUS` 的 `period` 必须是 `along` 桶的整数倍（年、季只能平移年及以上；月可按年、季、月；周只能按周；日及更细可按年、季、月、周）。

## 4. 能力与实现

`AggregationSupport` 增加 `windows: Map<WindowFunction, SupportMode>`（`TOTAL`、`RUNNING`、`PREVIOUS`、`RANK`），能力描述的 `analysis.windows` 列出支持的函数。

| 存储 | 实现 |
|---|---|
| ClickHouse | NATIVE：内层分组查询外包一层窗口函数（`sum(gmv) OVER (PARTITION BY province)`、`rank() OVER (…)`、`sum(…) OVER (ORDER BY … ROWS BETWEEN …)`）；`PREVIOUS` 用平移范围的第二个分组子查询按平移后的桶键 `LEFT JOIN`；`having`、`sort`、`limit` 在最外层 |
| MongoDB | NATIVE：`$setWindowFields`（`TOTAL`、`RUNNING`、`RANK`）；`PREVIOUS` 用 `$facet` 跑平移范围再按键合并 |
| Elasticsearch | RESIDUAL：核心取回完整分组再计算，见下 |

**RESIDUAL 的边界**：核心要拿到**完整**的分组才能算对，所以以 `GroupWindow` 请求全部分组（上限 `maxResidualGroups`，缺省 10 000）；超过上限时查询被拒绝，而不是在截断的结果上算错。实现沿用 HAVING、指标排序已有的残余上限（`QueryBudget.maxResidualGroups`，HTTP 入口为 `wow.query.http.max-residual-groups`）与错误码 `RESIDUAL_GROUPS_EXCEEDED`，不另设错误码。这条与 Q53 的「算不对的不画」同一原则，只是从客户端挪到了服务端。

## 5. view-engine 的衔接

- 能力描述声明了对应函数时，`cumulative`、`cumulative-share`、`moving-average` 与占比由服务端计算，**不再因截断而置灰**（Q53 只对没有声明的存储保留）；
- 与上一期、去年同期的整段对比（Q59）改为一个 `PREVIOUS` 派生指标，不再需要第二条查询；
- 组内 TopN（「每个省前三的城市」）成为分析配置的一个选项。
- view-engine 这一侧的具体形状（配置成员、编辑器）在它自己的设计文档里写，属 R4d。

## 6. 不做的事

- 任意 SQL 窗口（自定义 frame 起止、`LEAD`、分位排名）：四种节点覆盖 BI 的常见问题，多了能力描述与跨存储一致性都难保证。
- 跨查询的指标（如「本查询的 GMV / 另一个数据集的 GMV」）：关联在宽表里做（数据集设计 §2）。

## 7. 实施阶段

| 阶段 | 内容 | 证明 |
|---|---|---|
| **R4a-1 协议与核心** | `DerivedExpression` 的四种节点与校验；准入；`AggregationSupport.windows` 与能力描述（`analysis.windows`）；`TOTAL`、`RUNNING`、`RANK` 的 RESIDUAL 实现，所有存储缺省为 RESIDUAL，`PREVIOUS` 缺省为 NONE | wow-api / wow-query 单测 |
| **R4a-2 `PREVIOUS`** | 平移范围（§3.2）与第二次分组读取的 RESIDUAL 实现，`PREVIOUS` 缺省改为 RESIDUAL；不可平移时准入以 `PREVIOUS_UNSHIFTABLE` 拒绝 | wow-query 单测 |
| **R4a-3 TCK** | 窗口语义规格：同一份期望在每个存储上跑（随 R4b 一起，先验证 ClickHouse 的 RESIDUAL 与 NATIVE 一致） | TCK + Testcontainers |
| **R4b ClickHouse** | NATIVE 实现 | TCK + Testcontainers |
| **R4c MongoDB、Elasticsearch** | Mongo NATIVE、ES RESIDUAL | TCK + Testcontainers |
| **R4d view-engine** | 用服务端窗口结果，Q53 置灰收窄，Q59 落地，组内 TopN | view-engine 测试与故事 |
| wow-client | DSL 的窗口节点 | API 报告 |

## 8. 决定（用户已确认，2026-10-11）

| # | 问题 | 推荐 |
|---|---|---|
| W1 | 窗口是派生指标的节点，还是独立的后处理阶段 | 派生指标的节点（§2 原则 1）：可与四则运算组合，`having`、`sort` 天然能引用 |
| W2 | 窗口在 `having` 之前还是之后计算 | 之前（§3.1）：`TOTAL` 是全部分组的合计，组内 TopN 用 `having` 表达 |
| W3 | `PREVIOUS` 的范围规则 | 顶层 AND 里对该字段的范围整体平移；不在顶层时拒绝（§3.2） |
| W4 | 是否包含移动平均 | 包含，作为 `RUNNING` 的 `function: AVG` 加 `frame` |
| W5 | RESIDUAL 的分组上限 | 10 000，超过拒绝（§4） |

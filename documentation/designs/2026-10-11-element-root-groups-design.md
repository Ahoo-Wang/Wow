# 元素作用域里按根字段分组

日期：2026-10-11。状态：用户已确认（2026-10-11）：§3 用分组的 `scope: ROOT`。按 E1～E3 推进。

## 1. 来由

聚合查询展开元素（`AggregationQuery.elements`）之后，计数单位是元素，分组与指标的字段都相对于最内层的元素容器解析；根上的字段一律报 `ElementScopeRequired`。补偿控制台「补偿活动」板想要「按天 × 事件名」：事件名在元素里，天是根上的 `createTime`，今天只能拆成四张图（view-engine todo「H」，用户 2026-09-27 定下由后端支持）。

## 2. 范围

- **在内**：分组（`groupBy`）可以按根字段；MongoDB 与 Elasticsearch 都支持；TCK 两个后端都加用例；wow-client 镜像；view-engine 放开 `analysis.field.outside-scope` 对分组的拒绝；控制台把结局画成一张多序列图。
- **不在内**：指标按根字段（一个根值会随它的元素被数多次，求和就错了，仍然拒绝）；过滤（根条件写在顶层 `filter` 里，已经可以）；中间层（多级展开时按某个中间元素的字段分组）——今天没有需要，§3 的写法给它留了位置。

## 3. 写法（已确认）

分组多一个可选的 `scope`：

```json
{ "type": "DATE_HISTOGRAM", "field": "createTime", "alias": "day", "unit": "DAY", "scope": "ROOT" }
```

- `scope` 缺省为 `ELEMENT`，即今天的读法（相对最内层元素）；`ROOT` 表示字段是根上的绝对路径。没有展开元素时 `ROOT` 与缺省相同。
- 准入照常检查：根字段要有分组能力、不能是受保护字段；`scope: ROOT` 的分组不能是表达式输入（表达式里的字段仍相对元素）。
- `ROOT` 分组排在元素分组之前（用户 2026-10-11 确认，各后端统一）：有效排序里 `ROOT` 分组不能出现在元素分组之后。后端按「根键 → 元素键」的层次读出分组，核心层从不自己比较分组键，所以根键套在元素键里面没有原生次序；指标排序不受影响。
- 以后要中间层时，可以加 `ELEMENT_<n>` 一类的值，不改这个字段的形状。

另外两种写法，不推荐：

- **路径前缀**（如 `"$root.createTime"`）：要改 `QueryField` 的语法，每个读路径的地方都得懂它。
- **自动回退**（相对元素找不到就按根找）：同名时有歧义，字段改名后查询悄悄换了意思。

## 4. 两个后端

- **MongoDB**：管道是 `$match → $unwind 元素 → $match 元素条件 → $group`。`$unwind` 之后根字段仍在文档上，根分组的 `_id` 键直接取根路径即可。
- **Elasticsearch**：今天的 composite 分组整个在 `nested` 里。有根分组时改成两层：外层 composite 只放根分组的来源，分页照旧用它的 `after_key`；每个外层桶里是 `nested → filter →` 内层 composite，放元素分组的来源与指标，内层不分页（一次取够本页剩余的行数，超出时与今天的残余上限同样报错）。没有根分组时，编译与今天完全相同。
- **结果**：行里根分组与元素分组的键并列，次序照 `groupBy`，排序与 `limit` 照旧在后端之后由查询服务处理。

## 5. 客户端与视图引擎

- **wow-client**：`AggregationGroup` 的四种类型都多一个可选的 `scope?: 'ELEMENT' | 'ROOT'`。
- **view-engine**：分析的分组若是元素作用域之外、但在根上的字段，不再报 `analysis.field.outside-scope`，编译时带 `scope: 'ROOT'`；能力描述里没有这项能力的服务端（9.9 之前）照旧报。指标仍按今天的规则拒绝。
- **补偿控制台**：「补偿活动」板的结局改成一张「按天 × 事件名」的四线图。

## 6. 批次

| 批 | 内容 | 验证 |
| -- | -- | -- |
| E1 | wow-api 的 `scope`、wow-query 的准入、MongoDB 编译、TCK 用例；wow-client 镜像（契约测试逐键对照服务端的 schema，须同批） | TCK 在 MongoDB 上过；契约测试 |
| E2 | Elasticsearch 的两层编译与分页 | 同一套 TCK 在 Elasticsearch 上过 |
| E3 | view-engine 放开分组、控制台的图 | 单测、契约测试、浏览器故事 |

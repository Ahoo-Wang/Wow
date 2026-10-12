# Elasticsearch 上按指标排序的 Top-N 原生执行

日期：2026-10-12。状态：用户已确认（2026-10-12）：只在结果精确时原生执行。对应 view-engine todo「R2-92」。

## 1. 来由

`ElasticsearchQuerySchemaAdapter` 把按指标排序的 Top-N（`topN`）标为残余：核心要读完全部分组（每页一次 composite 请求），再自己排序取前 N。分组超过 `QueryBudget.maxResidualGroups`（默认 10,000）就失败，即使只要前 10 个。MongoDB 原生执行，没有这个上限。

## 2. 精确性

Elasticsearch 的 `terms` 聚合按子指标排序时，每个分片只交出自己的前 `shard_size` 个桶再合并。所以在多分片上，按指标排序的前 N 是近似的；升序时误差没有上界。单个索引、单个主分片时没有合并这一步，结果精确。Wow 的索引模板不设分片数，Elasticsearch 7 起默认 1 个主分片，所以常见部署是精确的。

用户的选择：**只在精确时原生**。别名或数据流解析到多个物理索引、或主分片多于 1 时，照旧走残余，结果与今天相同。不提供「近似但快」的模式。

## 3. 写法

- **能力**：`AggregationSupport.termsTopN: Set<MetricSpec>`（缺省为空）：存储能自己按这些类型的指标，给单个 TERMS 分组排序并取前 N。只在 `topN` 为 RESIDUAL 时起作用。
- **核心的计划**（`AggregationShape` 的 `Residual`）：查询满足以下全部条件时，按指标排序不再是残余，核心把排序与 `limit` 交给存储（`GroupWindow.First(limit)`），不再读全部分组：
  - 恰好一个分组，是 TERMS，且不是展开元素时的 ROOT 分组；
  - 没有 HAVING，也没有因稠密补齐或窗口而必须在核心计算的部分；
  - 有效排序里每个指标的类型都在 `termsTopN` 里。
- **Elasticsearch**：绑定 schema 时读 `index.number_of_shards`（与已在读的 `index.max_result_window` 同一个请求）。名字只解析到一个物理索引、且主分片为 1 时，声明 `termsTopN = {COUNT, DISTINCT_COUNT}`。
  - 编译：分组的 composite 来源转成 `terms` 桶聚合（与根分组 E2 共用一个转换），`size` 取 `limit`，`order` 按有效排序写成 `[指标, _key]`。COUNT 用 `_count`（带指标条件时用 `<条件聚合>>_count`），DISTINCT_COUNT 用 cardinality 的路径。
  - 先只放开 COUNT 与 DISTINCT_COUNT：这两种没有空值。SUM、AVG 等在一组没有取值时，Wow 给 `null`（排序时最小），Elasticsearch 给 0 或空，两边的顺序在边界上不同，证明一致之前不放开。
- **描述符**：`AggregationLimitsDescriptor.maxResidualGroups`（HTTP 入口的预算；没有上限时缺省），让客户端在准入时知道残余运算最多读多少组。

## 4. 不在内

- 多个分组、HAVING、数值指标的原生 Top-N。
- 多分片上的近似模式。

## 5. 验证

- 核心的计划单测：满足与不满足条件的各种查询。
- TCK：在残余上限很小的预算下（例如 10 组），对超过上限的分组按计数取前 3：MongoDB 与单分片的 Elasticsearch 都直接回答；不满足条件的查询仍按残余上限失败。
- Elasticsearch：多分片索引照旧走残余，并给出与单分片相同的结果。

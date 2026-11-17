# wow-clickhouse

Wow 的 ClickHouse 支持：数仓 Sink 把每个发布的聚合的提交复制进 ClickHouse，数据集查询后端从中提供服务的数据集。

**孵化中：**`wow-clickhouse` 在 10.0 中是实验性的（`@ExperimentalWowApi`），在数据集与分析模块正式发布之前可能不经弃用周期而改变。

如何连接服务、Sink 保存的表与列、集群的建表方式以及数据集，见 [ClickHouse](https://wow.ahoo.me/zh/operate/clickhouse)。

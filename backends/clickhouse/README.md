# wow-clickhouse

ClickHouse for Wow: the warehouse sink, which copies each published aggregate's commits into ClickHouse, and the dataset query backend, which serves a service's datasets from it.

**Incubating:** `wow-clickhouse` is experimental in 10.0 (`@ExperimentalWowApi`) and may change without a deprecation cycle until the dataset and analytics modules are released stable.

How to connect a service, the tables and columns the sink keeps, a cluster's layout and the datasets are described in [ClickHouse](https://wow.ahoo.me/operate/clickhouse).

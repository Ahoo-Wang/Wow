---
title: 容量规划
description: 由实测的每条命令成本、单个节点的可持续速率以及不随节点数提升的上限，把负载换算成部署；每个数字都由已提交的基准报告生成，并注明其构建、CPU 与 JDK。
outline: deep
---

# 容量规划

本页把你的负载换算成部署：需要多少节点、多少数据库能力，以及加节点从哪里开始不再有用。页中每个数字都在构建文档时由已提交的基准报告写入，并注明测量所用的构建、CPU 与 JDK。Wow 怎样处理一条命令、与别的框架相比如何，见[性能](../guide/performance)；节点在负载下做什么，见[伸缩与容量](./scaling)。

- **四项输入、三张表，然后是公式。** [算例](#example)把它们完整走一遍。
- **每个数字都在注明的硬件上测得**，数据库、Kafka 与负载生成器和节点在同一台机器上。[数字何时适用](#transfer)说明它们何时对你的环境成立，以及怎样测出你自己的数字。

## 测了什么 {#what-was-measured}

领域是基准测试自己的：账户，以及由 Saga 执行的两个账户之间的转账（[`test/benchmarks/server`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/server)）。

| 场景 | 一次操作是什么 | 何时应答 |
| --- | --- | --- |
| E1 写入 | 向一个账户存款 | 它的提交已存储 |
| E2 读己之写 | 一次存款，再读该账户的状态 | 读到的版本包含这次存款 |
| E3 Saga 一步 | 在一个账户上发起一笔转账 | Saga 在另一个账户上的入账已存储 |
| E4 读取 | 按 id 读一个账户的状态，或读一页十个账户 | 应答本身 |
| 混合 | 读写混合：一半 E1，其余 E4 | 各按其场景 |
| 大写入 | 每条命令五个约 2 KB 事件的 E1 | 同 E1 |

在 GitHub Actions 上，每个套件独占一台 4 核 runner，其 CPU 型号写在所在行。在 Apple M5 Max（18 核，64 GB）上，存储运行在同一主机的容器中。

## 1. 你的四项输入 {#inputs}

| 输入 | 要准备什么 |
| --- | --- |
| 速率 | 最忙的一小时里每秒的命令数，按种类分：写入、Saga 步骤、按 id 读取、列表 |
| 聚合 | 共有多少个，一小时内访问多少个（热集） |
| 历史 | 每天的提交数，以及日志保留多久（默认永久） |
| 大小 | 一个状态的大小，以及一条命令的事件大小 |

## 2. 每条命令的成本（T1） {#unit-costs}

::: warning 先看速率一列
Wow 会合批：排在同一个聚合上的命令一起提交，几个分区的追加合并成一条语句。所以一条命令的成本随速率升高而下降；在高速率下测得的成本乘以低负载，得出的结果会偏小。取与你的速率最接近的那一行；落在两行之间时，取较低速率那一行的成本。
:::

一个节点，100,000 个账户。只标速率的行，是 beta.6 该套件 L3 三轮的中位数，速率为该套件校准选定的速率；节点绑定在一个核上，PostgreSQL 或 MongoDB 与 Kafka 各绑定一个核，CPU 按三者分项。标为“可持续”的行是 [T2](#sustainable) 的 L4 速率，其 CPU 是三者合计。大写入是五个约 2 KB 的事件；混合是一半 E1 写入、一半 E4 读取。提交数与 WAL 字节是 PostgreSQL 每条命令的数字。

<!-- performance:begin unit-costs -->
<!-- 由 .github/scripts/docs-capacity.mjs 依据各报告的 headline.json 生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

CPU 以每条命令的毫秒计。构建 `e3a4e937aa`，JDK 25.0.5+8；每行注明其机器的 CPU。短横表示 L4 套件没有分项或没有计数的数字。

**PostgreSQL**

| 负载 | 速率 | 节点 | 数据库 | Kafka | 三者合计 | 提交数 | WAL 字节 | 机器 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| E1 写入 | 1,487.5/s | 0.579 | 0.431 | 0.335 | 1.34 | 0.674 | 1,132 | Xeon 8573C |
| E1 写入 | 1,957.8/s，可持续 | – | – | – | 1.33 | – | – | EPYC 7763 |
| E3 Saga 一步 | 478.7/s | 1.678 | 1.487 | 1.019 | 4.2 | 2.065 | 4,222 | EPYC 7763 |
| E3 Saga 一步 | 839.9/s，可持续 | – | – | – | 3.34 | – | – | EPYC 7763 |
| 大写入 | 546.4/s | 1.152 | 1.096 | 0.948 | 3.2 | 1.461 | 13,033 | EPYC 9V74 |
| 混合 | 1,515.5/s | 0.61 | 0.363 | 0.295 | 1.28 | 1.064 | 610 | Xeon 8370C |

**MongoDB**

| 负载 | 速率 | 节点 | 数据库 | Kafka | 三者合计 | 机器 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| E1 写入 | 1,771.1/s | 0.344 | 0.503 | 0.244 | 1.09 | EPYC 9V74 |
| E1 写入 | 2,975.1/s，可持续 | – | – | – | 0.95 | Xeon 6975P-C |
| E1 写入，Zipf 键 | 2,232.6/s | 0.335 | 0.408 | 0.186 | 0.93 | EPYC 9V45 |
| E2 读己之写 | 869.8/s | 0.736 | 1.004 | 0.476 | 2.21 | EPYC 9V74 |
| E2 读己之写 | 1,272.2/s，可持续 | – | – | – | 2.2 | Xeon 6975P-C |
| E3 Saga 一步 | 375.2/s | 1.54 | 2.584 | 1.273 | 5.35 | EPYC 7763 |
| E3 Saga 一步 | 1,008.3/s，可持续 | – | – | – | 2.8 | Xeon 6975P-C |
| 大写入 | 1,048.4/s | 0.505 | 0.771 | 0.375 | 1.65 | EPYC 9V45 |
| 混合 | 1,843.3/s | 0.405 | 0.505 | 0.22 | 1.13 | EPYC 9V45 |

MongoDB 每条命令的提交数与日志字节数未测。
<!-- performance:end -->

各行来自 [actions-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md) 报告；各 runner 的 CPU 型号不同，所以不同型号上的两行不构成比较。

**每个聚合，在 PostgreSQL 上**（[actions-axon](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-axon.md) 报告中 `axon-pg` 套件两次会话里较大的一次中的 Wow，构建 <!-- perf:actions-axon/footprint.axon-pg.100k.wow:commit -->`3fd5349050`<!-- /perf -->，<!-- perf:actions-axon/footprint.axon-pg.100k.wow:cpu -->EPYC 9V74<!-- /perf -->）：

- 日志中的一个事件占 <!-- perf:actions-axon/footprint.axon-pg.100k.wow:bytesPerEvent.1 -->627<!-- /perf --> 字节，含索引；
- 一个快照（每个聚合一个，原地重写）占 <!-- perf:actions-axon/footprint.axon-pg.100k.wow:bytesPerSnapshot.1 -->441<!-- /perf --> 字节。

**未测：** MongoDB 上每个事件与每个快照的字节数；一个缓存状态占用的堆；单独计的一次缓存未命中（它对延迟的影响见 T2 的[冷缓存](#cold-cache)）；以及一个订阅的成本。

## 3. 单个节点的可持续速率（T2） {#sustainable}

p99 不超过 200 ms 的最高速率：在 runner 上要求错误不超过 1%，以开环二分查找得出；在工作站上要求没有错误，取闭环扫描中最好的一级。在 runner 上，节点、数据库与 Kafka 共用其 4 个核中的 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.server -->3<!-- /perf --> 个，负载生成器占另外 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.load -->1<!-- /perf --> 个。

<!-- performance:begin snapshots -->
<!-- 由 .github/scripts/docs-performance.mjs 依据各报告的 headline.json 生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

每个数字都来自其所在行注明的构建、JDK 与机器。各场景见[测了什么](#what-was-measured)。

**GitHub Actions，每个套件一台 4 核 runner**：[actions-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md)，构建 `e3a4e937aa`。

这些行来自 2 种 CPU 型号，每行注明了自己的型号：型号不同的两行既不能用来比较存储，也不能用来比较场景。

| 存储 | 节点 | 场景 | 键 | 峰值 | 可持续 | CPU、JDK、构建 |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 一个 | E1 | 均匀 | ≥ 5,010.8/s，闭环 | 2,975.1/s，开环，p99 198.7 ms | Xeon 6975P-C，JDK 25.0.5+8，`e3a4e937aa` |
| MongoDB | 一个 | E2 | 均匀 | 2,261.6/s，闭环 | 1,272.2/s，开环，p99 190.5 ms | Xeon 6975P-C，JDK 25.0.5+8，`e3a4e937aa` |
| MongoDB | 一个 | E3 | 均匀 | 1,466.6/s，闭环 | 1,008.3/s，开环，p99 173.1 ms | Xeon 6975P-C，JDK 25.0.5+8，`e3a4e937aa` |
| PostgreSQL | 一个 | E1 | 均匀 | 4,474.9/s，闭环 | 1,957.8/s，开环，p99 139.3 ms | EPYC 7763，JDK 25.0.5+8，`e3a4e937aa` |
| PostgreSQL | 一个 | E3 | 均匀 | 1,493.1/s，闭环 | 839.9/s，开环，p99 190.5 ms | EPYC 7763，JDK 25.0.5+8，`e3a4e937aa` |

**Apple M5 Max，18 核，64 GB，一台主机**：[m5max-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/m5max-snapshot.md)，构建 `103f9b6746`。

| 存储 | 节点 | 场景 | 键 | 峰值 | 可持续 | CPU、JDK、构建 |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 一个 | E1 | 单个热点账户 | 9,948.1/s，256 个客户端 | 9,948.1/s，256 个客户端，p99 78.8 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| MongoDB | 一个 | E1 | 均匀 | ≥ 28,609.4/s，4,096 个客户端 | 28,196.1/s，1,024 个客户端，p99 66.6 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| MongoDB | 一个 | E1 | Zipf | 15,957.5/s，1,024 个客户端 | 14,914.4/s，256 个客户端，p99 42.2 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| MongoDB | 一个 | E3 | 均匀 | 7,738.2/s，1,024 个客户端 | 5,898.6/s，256 个客户端，p99 72.2 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 一个 | E1 | 单个热点账户 | 11,853.7/s，256 个客户端 | 11,853.7/s，256 个客户端，p99 76.8 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 一个 | E1 | 均匀 | ≥ 41,706.1/s，4,096 个客户端 | 41,706.1/s，4,096 个客户端，p99 167.9 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 一个 | E1 | Zipf | 19,746.8/s，256 个客户端 | 19,746.8/s，256 个客户端，p99 29.6 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 一个 | E3 | 均匀 | 8,042.7/s，1,024 个客户端 | 3,637.9/s，64 个客户端，p99 185.3 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |

三个节点在同一台主机上：节点、存储与负载生成器分用同一组 CPU 核，所以这几行显示的是集群的开销（节点之间的转发），不是它带来的收益。跨多台机器的扩展尚未测量。

| 存储 | 节点 | 场景 | 键 | 峰值 | 可持续 | CPU、JDK、构建 |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 3 | E1 | 均匀 | 19,052.3/s，1,024 个客户端 | 19,052.3/s，1,024 个客户端，p99 131.1 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| MongoDB | 3 | E3 | 均匀 | 6,060.2/s，1,024 个客户端 | 5,462.9/s，256 个客户端，p99 78.8 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 3 | E1 | 均匀 | 25,104.3/s，1,024 个客户端 | 25,104.3/s，1,024 个客户端，p99 90.6 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
| PostgreSQL | 3 | E3 | 均匀 | 8,623/s，1,024 个客户端 | 7,279/s，256 个客户端，p99 59.6 ms | Apple M5 Max，JDK 25.0.5+8，`103f9b6746` |
<!-- performance:end -->

### 冷缓存 {#cold-cache}

一百万个账户、没有任何缓存，runner 上的一个节点（构建 <!-- perf:actions-snapshot/cold.postgres.E1:commit -->`e3a4e937aa`<!-- /perf -->；PostgreSQL 的 runner 为 <!-- perf:actions-snapshot/cold.postgres.E1:cpu -->Xeon 6975P-C<!-- /perf -->，MongoDB 的为 <!-- perf:actions-snapshot/cold.mongo.E1:cpu -->EPYC 9V74<!-- /perf -->）以 <!-- perf:actions-snapshot/cold.postgres.E1:value -->200/s<!-- /perf --> 写入时，p99 在 PostgreSQL 上为 <!-- perf:actions-snapshot/cold.postgres.E1:p99Ms -->5.1 ms<!-- /perf -->，在 MongoDB 上为 <!-- perf:actions-snapshot/cold.mongo.E1:p99Ms -->154.6 ms<!-- /perf -->。

**节点的速率怎样随核数变化：** 只测了两类硬件，不是一条曲线。介于两者之间时，按一条命令消耗的 CPU（T1）估算，而不是只按核数。

## 4. 上限（T3） {#ceilings}

加节点也提不高的限制。

| 上限 | 测得的情况 | 到达时怎么办 |
| --- | --- | --- |
| 单个聚合 | 工作站上单一热点账户的峰值在 PostgreSQL 上为 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-hot-sweep:value -->11,853.7/s<!-- /perf -->，在 MongoDB 上为 <!-- perf:m5max-snapshot/sweep.mongo.1.e1-hot-sweep:value -->9,948.1/s<!-- /perf -->（构建 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-hot-sweep:commit -->`103f9b6746`<!-- /perf -->，<!-- perf:m5max-snapshot/sweep.postgres.1.e1-hot-sweep:cpu -->Apple M5 Max<!-- /perf -->） | 把负载分散到多个聚合 |
| 一个分区承载许多聚合 | 未测 | 在首次部署前选定分区数（[节点与分区](./scaling#nodes-and-partitions)） |
| 数据库 | 它在每条命令上消耗的 CPU，见 T1 | 更大或更少共享的数据库；MongoDB 分片；把另一种聚合的日志放到另一个存储上 |
| 连接 | 不是测量值：每个节点的连接池，再加一个池，不超过 `max_connections` | [确定连接池大小](./postgresql#size-the-pools) |
| 下游写入（Sink） | 本页未测 | 它给日志所在数据库增加的负载未测 |
| 落后的订阅 | 不是速率：`wow.subscription.backlog` | 更快的处理器，或更多节点（[订阅](./scaling#subscriptions)） |

## 5. 从输入到部署 {#formulas}

- **节点数** = 以下两者中较大的一个：
  - ⌈Σ 速率 ÷（它在 T2 中的可持续速率 × 0.6）⌉，使每个节点运行在其可持续速率的 60% 左右；
  - ⌈Σ 速率 × 节点 CPU ms（T1）÷（1,000 × 每个节点的核数 × 0.6）⌉；

  再加一个，使失去的节点的分区能容纳在其余节点上。
- **数据库 CPU（核）** = Σ 速率 × 数据库 CPU ms（T1）÷ 1,000。
- **PostgreSQL 上每秒的提交数与 WAL** = Σ 速率 × 提交数，以及 Σ 速率 × WAL 字节（T1）。
- **日志存储** = 每天的事件数 × 每个事件的字节数 × 保留天数；**快照** = 聚合数 × 每个快照的字节数（T1 的每个聚合数字）。按事件计，而不是按命令或提交计：一条命令写一个或多个事件，一次提交可以包含多条命令。在基准测试的领域里，一次存款（E1）写一个事件；一笔转账（E3）写四个，发起时两个，入账时一个，确认时一个；大写入写五个约 2 KB 的事件，上面按小事件测得的每个事件字节数不适用于它。
- **堆** = 热集 × 一个缓存状态占用的堆（未测）× 10（状态缓存为堆的十分之一时，见[Wow 推导的设置](./configuration#what-wow-derives)），再加飞行中的命令。
- **分区：** 规则见[节点与分区](./scaling#nodes-and-partitions)。

**节点启动后的头半分钟不是它的稳态。** 在 runner 上（构建 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:commit -->`e3a4e937aa`<!-- /perf -->，<!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cpu -->EPYC 9V45<!-- /perf -->，MongoDB，1,000 个未预热的账户），一个刚启动的节点以 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:rate -->423.9<!-- /perf -->/s 承接 E3 时，用掉了它那一个核的 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.nodeCores -->1.00<!-- /perf -->，而 MongoDB 只用了它自己那个核的 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.storeCores -->0.65<!-- /perf -->。它的 p50 在第 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.warmUpSeconds -->10<!-- /perf --> 秒为 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.p50FirstMs -->2,637.8 ms<!-- /perf -->，第 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.lastSecond -->39<!-- /perf --> 秒为 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:cold.p50LastMs -->41.2 ms<!-- /perf -->。预热 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:warm.warmUpSeconds -->120<!-- /perf --> 秒之后，同样的速率只用该核的 <!-- perf:actions-snapshot/start.mongo.e3-mongo.1k.a:warm.nodeCores -->0.49<!-- /perf -->。所以滚动重启要为每个重新加入的节点留出 CPU 余量：在它的头半分钟里，同样的速率要用掉比之后多的 CPU。

## 6. 算例 {#example}

由生成器按上面的公式从 T1 与 T2 计算；每个输出都注明它读的是哪几行。

<!-- performance:begin example -->
<!-- 由 .github/scripts/docs-capacity.mjs 依据各报告的 headline.json 生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

**输入：** 一个用 PostgreSQL 的上下文，最忙的一小时里每秒 2,000 次写入（E1，每次 1 个事件）和 500 笔转账（E3，每笔 4 个事件）。它一天的平均负载是峰值的 1/4。它有 2,000,000 个账户，日志保留 3 年。

| 输出 | 数值 | 来源 |
| --- | --- | --- |
| 节点数，按可持续速率 | 相当于一个节点可持续速率的 162%：每个节点按 60% 计需要 3 个，再加一个容纳失去节点的分区，共 4 个 | T2：E1 写入，PostgreSQL，1,957.8/s，EPYC 7763，`e3a4e937aa`；T2：E3 Saga 一步，PostgreSQL，839.9/s，EPYC 7763，`e3a4e937aa` |
| 节点 CPU | 2 个核；按 60% 计为 3.33 个 | T1：E1 写入，PostgreSQL，1,487.5/s，Xeon 8573C，`e3a4e937aa`；T1：E3 Saga 一步，PostgreSQL，478.7/s，EPYC 7763，`e3a4e937aa` |
| 数据库 CPU | 1.61 个核 | T1：E1 写入，PostgreSQL，1,487.5/s，Xeon 8573C，`e3a4e937aa`；T1：E3 Saga 一步，PostgreSQL，478.7/s，EPYC 7763，`e3a4e937aa` |
| 每秒提交数 | 每秒 2,381 | T1：E1 写入，PostgreSQL，1,487.5/s，Xeon 8573C，`e3a4e937aa`；T1：E3 Saga 一步，PostgreSQL，478.7/s，EPYC 7763，`e3a4e937aa` |
| 每秒 WAL | 每秒 4.38 MB | T1：E1 写入，PostgreSQL，1,487.5/s，Xeon 8573C，`e3a4e937aa`；T1：E3 Saga 一步，PostgreSQL，478.7/s，EPYC 7763，`e3a4e937aa` |
| 每天的事件数 | 86,400,000 | 输入：Σ 峰值速率 × 每条命令的事件数 × 86,400 ÷ 峰均比 |
| 3 年的日志存储 | 59.3 TB | 每个事件 627 B，actions-axon 报告中 PostgreSQL 上的 Wow，EPYC 9V74，`3fd5349050` |
| 快照 | 0.9 GB | 每个快照 441 B，actions-axon 报告中 PostgreSQL 上的 Wow，EPYC 9V74，`3fd5349050` |
<!-- performance:end -->

**算例展示的是方法，用的是注明的行；它不是一份规模估算。** 它的节点与数据库 CPU 把在两种 CPU 型号上测得的成本相加（E1 一行在 <!-- perf:actions-snapshot/unit.postgres.e1-pg-uniform.100k.r1-head:cpu -->Xeon 8573C<!-- /perf --> 上，E3 一行在 <!-- perf:actions-snapshot/unit.postgres.e3-pg.100k.r1-head:cpu -->EPYC 7763<!-- /perf --> 上），它的字节数来自更早的一个构建，都在旁边注明。请按在你自己那类硬件上测得的行来估算（[怎样测出你自己的数字](#transfer)）。节点数针对的是 runner 这一类节点，数据库与它共用机器；节点独占机器时，改按节点 CPU 估算。堆没有计算，因为一个缓存状态占用的堆未测。

## 7. 在哪里不再扩展 {#where-it-stops}

加节点能提高吞吐，直到数据库满载。

- **上界：** 服务的速率 ≤ 数据库的 CPU ÷ 每条命令的数据库 CPU（T1）。超过它，再加节点也没有用；下一步是更大或更少共享的数据库。
- **然后是热点聚合**，无论节点多少，都停在它的上限（T3）。
- **然后是一种聚合的日志只在一个数据库上；** 拆分它计划在 10.x 中提供。

**没有测量跨多台机器的扩展。** 三节点的运行与数据库共用一台机器，所以它们显示的是集群的开销，而不是它带来的收益。要在你自己的机器上测：用同一个 E1 扫描，分别对 1、2、4 个各在独立主机上的节点和一个数据库运行，看数据库的 CPU 在哪里到达上限。

## 8. 数字何时适用，以及怎样测出你自己的数字 {#transfer}

**这些数字适用于：**

- 所注明的那类硬件上的节点，数据库、Kafka 与负载生成器在它旁边；
- 按基准测试工具的方式启动的 PostgreSQL 或 MongoDB（[`compose.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/harness/compose.yml)）；
- 基准测试中小的事件与状态（大负载见 T1 的大写入一行）；
- 除非注明，键均匀分布；
- JDK 25，1 GB 堆，G1；
- Kafka 发布开启。

**你自己的数字**，在你的硬件上，于仓库根目录，用 Docker 与 JDK 25：

```bash
test/benchmarks/harness/bench.sh build
test/benchmarks/harness/bench.sh up --backend=postgres --accounts=100000 --prime
test/benchmarks/harness/bench.sh saturate --reuse --backend=postgres --accounts=100000 --scenario=E1 --sweep
test/benchmarks/harness/bench.sh run --reuse --backend=postgres --accounts=100000 --scenario=E1 --rate=1000
test/benchmarks/harness/bench.sh down
```

扫描给出你的机器上 T2 的数字；按你自己的速率运行一次给出 T1 的数字。在 GitHub 的 runner 上：`gh workflow run bench.yml -f tier=L4 -f head_ref=<tag>`。[基准测试指南](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/README.md)说明了测试工具及每个套件；[`test/benchmarks/results/reports/`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/results/reports) 保存这些数字所出自的报告，每份都带有其原始结果。

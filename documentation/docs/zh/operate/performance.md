---
title: 性能
description: Wow 的基准测试测什么、在什么上测，最新报告的主要数字，怎样读它们，它们对容量规划意味着什么，以及怎样复现其中一个。
outline: deep
---

# 性能

Wow 按端到端测量：负载生成器经 HTTP 向运行在 PostgreSQL 或 MongoDB 与 Kafka 上的 Wow 节点发送命令，并等待各场景指定的阶段。每个环境的每类报告只保留一份，即最新的那份，连同它的原始结果。本页给出这些报告的主要数字（由报告生成），以及怎样读和用它们。节点在负载下做什么见[伸缩与容量](./scaling)；存储的选择见[选择存储](./choosing-storage)。

## 测了什么 {#what-was-measured}

领域是基准测试自己的：账户，以及由 Saga 执行的两个账户之间的转账（[`test/benchmarks/server`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/server)）。

| 场景 | 一次操作是什么 | 何时应答 |
| --- | --- | --- |
| E1 写入 | 向一个账户存款 | 它的提交已存储 |
| E2 读自己的写 | 一次存款，再读该账户的状态 | 读到的版本包含这次存款 |
| E3 Saga 链 | 在一个账户上发起一笔转账 | Saga 在另一个账户上的入账已存储 |
| E4 读取 | 按 id 读一个账户的状态，或读一页十个账户 | 应答本身 |
| E5 集群 | 三个节点上的 E1 与 E3，轮询分发，稳态以及停止并重启一个节点 | 同 E1 与 E3 |
| Mixed | 读写混合：一半 E1，其余 E4 | 各按其场景 |
| Large | 每条命令五个约 2 KB 事件的 E1 | 同 E1 |

键在账户上均匀分布、按 Zipf 分布（少数账户承担大多数命令），或只有一个热点账户。数据集为 1,000 个账户（每一级缓存都装得下）；100,000 个，测量前逐一访问一次；以及一百万个，不预先访问（冷缓存）。

两个环境：

- **GitHub Actions。** 每个套件独占一台共享 Runner，4 核 16 GB，节点、存储、Kafka 与负载生成器都在其上。一次比较在同一台 Runner 上轮流运行当前提交和一个已发布的基线，因此它的比值可跨 Runner 比较，而绝对数字只属于那台 Runner。
- **一台 Apple M5 Max**（18 核，64 GB）。存储运行在同一主机的容器中，节点为一个或三个；闭环扫描从 1 个客户端到 4,096 个，单节点的 E1 继续向上。

## 数字 {#figures}

<!-- performance:begin -->
<!-- 由 .github/scripts/docs-performance.mjs 依据各报告的 headline.json 生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

**GitHub Actions，每个套件一台 4 核 runner**：[actions-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md)，构建 `d8284109df`。

| 存储 | 节点 | 场景 | 键 | 峰值 | 可持续 | CPU、构建 |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 一个 | E1 | 均匀 | 2,166.1/s，闭环 | 1,353.8/s，开环，p99 171 ms | EPYC 7763，`d8284109df` |
| MongoDB | 一个 | E2 | 均匀 | 1,353.4/s，闭环 | 676.7/s，开环，p99 196.6 ms | EPYC 7763，`d8284109df` |
| MongoDB | 一个 | E3 | 均匀 | 776.8/s，闭环 | 485.5/s，开环，p99 161.8 ms | EPYC 7763，`d8284109df` |
| PostgreSQL | 一个 | E1 | 均匀 | 4,912.5/s，闭环 | 2,609.8/s，开环，p99 160.8 ms | EPYC 9V45，`d8284109df` |
| PostgreSQL | 一个 | E3 | 均匀 | 2,374/s，闭环 | 741.9/s，开环，p99 188.4 ms | EPYC 9V45，`d8284109df` |

**Apple M5 Max，18 核，64 GB，一台主机**：[m5max-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/m5max-snapshot.md)，构建 `d9b0781205`。

| 存储 | 节点 | 场景 | 键 | 峰值 | 可持续 | CPU、构建 |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 一个 | E1 | 单个热点账户 | 6,911.2/s，1,024 个客户端 | 5,417.2/s，64 个客户端，p99 150.5 ms | Apple M5 Max，`d9b0781205` |
| MongoDB | 一个 | E1 | 均匀 | ≥ 21,775.2/s，4,096 个客户端 | 19,759.6/s，1,024 个客户端，p99 102.4 ms | Apple M5 Max，`d9b0781205` |
| MongoDB | 一个 | E1 | Zipf | 13,372.5/s，1,024 个客户端 | 3,353.8/s，64 个客户端，p99 197.6 ms | Apple M5 Max，`d9b0781205` |
| MongoDB | 一个 | E3 | 均匀 | 7,341.7/s，1,024 个客户端 | 4,809.4/s，256 个客户端，p99 173.1 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 一个 | E1 | 单个热点账户 | 11,521.2/s，1,024 个客户端 | 8,520.7/s，256 个客户端，p99 155.6 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 一个 | E1 | 均匀 | ≥ 37,713.6/s，4,096 个客户端 | 23,030.1/s，1,024 个客户端，p99 74.8 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 一个 | E1 | Zipf | 17,090.6/s，1,024 个客户端 | 15,386.1/s，256 个客户端，p99 52.2 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 一个 | E3 | 均匀 | 7,956.2/s，1,024 个客户端 | 3,687.4/s，64 个客户端，p99 41.7 ms | Apple M5 Max，`d9b0781205` |
| MongoDB | 3 | E1 | 均匀 | 16,657.2/s，1,024 个客户端 | 16,657.2/s，1,024 个客户端，p99 178.2 ms | Apple M5 Max，`d9b0781205` |
| MongoDB | 3 | E3 | 均匀 | 5,380.1/s，1,024 个客户端 | 4,894.1/s，256 个客户端，p99 105 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 3 | E1 | 均匀 | 21,114.1/s，1,024 个客户端 | 21,114.1/s，1,024 个客户端，p99 122.9 ms | Apple M5 Max，`d9b0781205` |
| PostgreSQL | 3 | E3 | 均匀 | 6,950.1/s，1,024 个客户端 | 6,119.1/s，256 个客户端，p99 75.8 ms | Apple M5 Max，`d9b0781205` |
<!-- performance:end -->

## 怎样读 {#how-to-read}

- **峰值**是失败不超过 1% 的闭环步骤中最好的吞吐量。它说明节点最多能做多少，而不是在什么延迟下。
- **可持续速率**是 p99 不超过 200 ms、错误不超过 1% 的最高速率。容量规划应以它为准。它与峰值之间的差距，就是节点为吞吐量付出的延迟。
- **下限**写作 `≥`，指测量无法再往上的峰值：负载生成器先到了极限，或者已是扫描的最后一步。
- **一次运行不构成比较。** 快照中的数字是一次运行，它与上一份快照的差异只列出、不下结论。一项改动让 Wow 更快还是更慢，要看同一台 Runner 上当前提交与基线的成对结果，并对照该套件自身的运行间噪声（[`noise.json`](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/noise.json)）。
- **两个环境之间不做比较**，二者也都不是生产部署：在两者上，存储和负载生成器都与节点共用机器。生产容量规划需要在目标硬件上运行。

## 对容量规划意味着什么 {#sizing}

- **数据库承载所有节点。** 所有节点的追加和读取都落在同一个数据库上，所以数据库一旦到达极限，更快的节点或更多的节点都不会抬高上限；更快的、或较少共享的数据库才会。
- **尾延迟决定可用容量。** 按可持续速率规划。越过它，延迟比吞吐量增长得更快；越过峰值，节点以 `wow.command.busy`（429，带 `Retry-After`）拒绝命令，而不是无限排队（[节点施加的限制](./scaling#limits-a-node-enforces)）。
- **热点聚合本身就是一个限制。** 一个聚合的命令在它的属主上逐条执行，所以热点账户的数字限定了任何单个聚合所能承受的量，与集群大小无关。在模型允许的地方，把负载分散到多个聚合上。
- **更多节点分摊分区。** 只要存储跟得上，吞吐量随节点增长（[节点与分区](./scaling#nodes-and-partitions)）。这里的三节点数字来自与存储和负载生成器共用一台主机或一台 Runner 的节点。它们显示的是集群的代价（转发、停止节点的分区交接），而不是在独立机器上的收益。

## 与 Axon Framework 4 比较 {#axon}

GA 之前，Wow 与 Axon Framework 4 在同一个 PostgreSQL 上比较过一次，双方都不用消息总线，使用相同的领域、负载生成器和测试框架，分别在 GitHub Actions（[actions-axon](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-axon.md)）和 M5 Max（[m5max-axon](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/m5max-axon.md)）上。Wow 的 E3 峰值在两个环境中都高于 Axon。它的 E1 峰值在 M5 Max 上高于 Axon；在 Actions 上，均匀键时持平，热点键时更高。比值以及各自成立的条件见报告。

## 复现一个数字 {#reproduce}

在 GitHub Actions 上，Bench 工作流（`.github/workflows/bench.yml`）把当前提交与一个已发布基线按层级或指定的套件运行：

```bash
gh workflow run bench.yml --ref main -f tier=L3 -f head_ref=main
```

L1 统计每次操作的存储往返，L2 测单点，L3 测场景与数据集的网格，L4 测可持续速率、悬崖、集群和冷缓存。在本地，于仓库根目录，配合 Docker 与 JDK 25：

```bash
test/benchmarks/harness/bench.sh build
test/benchmarks/harness/bench.sh up --backend=postgres --accounts=100000 --prime
test/benchmarks/harness/bench.sh saturate --reuse --backend=postgres --accounts=100000 --scenario=E1 --sweep
test/benchmarks/harness/bench.sh down
```

[基准测试指南](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/README.md)说明了测试框架、它的选项和每个套件。每份报告自己的复现一节给出其数字背后的确切命令。

## 完整报告 {#reports}

[`test/benchmarks/results/reports/`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/results/reports) 在每个环境上保留每类报告的最新一份：快照，以及与 Axon Framework 4 的比较，分别在 GitHub Actions 和 M5 Max 上。新报告替换它之前的那一份。每份报告是一个 `.md` 加同名目录，目录中保存其数字所依据的原始结果；它的 [README](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/README.md) 列出了它们。

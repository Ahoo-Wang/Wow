---
title: 性能
description: 一个 Wow 节点怎样处理一条命令，在注明的硬件上有多快，在同一套规则下与 Axon Framework 4 相比如何，以及它在哪里到头；每个数字都由已提交的基准报告生成。
outline: deep
---

# 性能

一个 Wow 节点怎样处理一条命令，在注明的硬件上有多快，在同一套规则下与 Axon Framework 4 相比如何，以及它在哪里到头。本页的每个数字都在构建文档时由已提交的基准报告写出，并注明测量它的构建、CPU 与 JDK。

::: tip 一屏看完
- **在一台 4 核云端 runner 上**，一个节点与数据库、Kafka 共用 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.server -->3<!-- /perf --> 个核，负载生成器占另外 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.load -->1<!-- /perf --> 个，这个节点在 PostgreSQL 上以 <!-- perf:actions-snapshot/sustainable.postgres.E1:value -->1,957.8/s<!-- /perf --> 持续写入，p99 不超过 <!-- perf:actions-snapshot/sustainable.postgres.E1:p99Ms -->139.3 ms<!-- /perf -->（runner <!-- perf:actions-snapshot/sustainable.postgres.E1:cpu -->EPYC 7763<!-- /perf -->）；在 MongoDB 上为 <!-- perf:actions-snapshot/sustainable.mongo.E1:value -->2,975.1/s<!-- /perf -->（runner <!-- perf:actions-snapshot/sustainable.mongo.E1:cpu -->Xeon 6975P-C<!-- /perf -->）。两种存储各在自己的 runner 上运行，所以两者不作比较（[在一台 4 核云端 runner 上](#on-a-4-core-cloud-runner)）。
- **在一台 18 核工作站上**，一个节点在 PostgreSQL 上以 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.value -->41,706.1/s<!-- /perf --> 持续写入，p99 不超过 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.p99Ms -->167.9 ms<!-- /perf -->，此时有 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.clients -->4,096<!-- /perf --> 个客户端（[一个节点在工作站上能走多远](#how-far-one-node-goes-on-a-workstation)）。
- **与 Axon Framework 4 相比**，同一个 PostgreSQL、同一套规则下，在节点与 PostgreSQL 共用 <!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:cpus.server -->2<!-- /perf --> 个核时，Wow 在 Saga 链上的峰值是 Axon 的 <!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:lo -->1.9<!-- /perf -->～<!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:hi -->2.3<!-- /perf --> 倍，在 18 核上是 <!-- perf:m5max-axon/axon.m5max-axon-peak-e3.e3.peak:lo -->5.2<!-- /perf -->～<!-- perf:m5max-axon/axon.m5max-axon-peak-e3.e3.peak:hi -->5.4<!-- /perf --> 倍（[与 Axon Framework 4 对比](#against-axon-framework-4)）。

要为部署定规模？见[容量规划](../operate/performance)。
:::

## Wow 怎样处理一条命令，以及为什么它便宜 {#what-wow-does-with-a-command-and-why-that-is-cheap}

一条命令发往拥有其聚合所在分区的节点。正在使用的聚合在其所属节点的缓存里，所以它的命令对着内存做决定，途中不从数据库读任何东西；节点第一次遇到的聚合会加载一次，来自它的快照和其后的事件（[冷缓存](#a-cold-cache)测的就是这种情况）。

- **一个聚合的命令在它自己的通道里按序执行，不加锁。** 排在一条命令之后的命令与它一起提交，多个分区的追加合并成一条语句，所以一次写入至多一次数据库往返、一次提交，速率越高，命令共用得越多。
- **不在命令的路径上：** 快照、Saga 与 Sink 在提交之后跟随日志。
- **调用方要求什么时候应答，就什么时候应答**（`Wow-Wait`）：写入用 `committed`，读自己的写用 `snapshot`，列表必须看到它时用 `queryable`，或 Saga 的下一步。

**换来的代价。** 每条命令的请求 id 都存在一个唯一键下，所以发了两次的命令只处理一次。因此一个事件在 Wow 的 PostgreSQL 日志里占 <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:bytesPerEvent.0 -->615<!-- /perf --> 字节，Axon 的为 <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.axon:bytesPerEvent.0 -->462<!-- /perf -->，均含索引。工作站上一个节点在启动后 <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:readyMs.0 -->1,790 ms<!-- /perf --> 就绪，常驻内存 <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:rssMbAtReady.0 -->461<!-- /perf --> MB（构建 <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:commit -->`cc11b16aa8`<!-- /perf -->，<!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:cpu -->Apple M5 Max<!-- /perf -->，JDK <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:jdk -->25.0.5+8<!-- /perf -->）。

## 怎样测量 {#what-was-measured}

领域是基准测试自己的：账户，以及由 Saga 执行的两个账户之间的转账（[`test/benchmarks/server`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/server)）。

| 场景 | 一次操作是什么 | 何时应答 |
| --- | --- | --- |
| E1 写入 | 向一个账户存款 | 它的提交已存储 |
| E2 读自己的写 | 一次存款，再读该账户 | 读到的结果包含这次存款 |
| E3 Saga 链 | 在一个账户上发起一笔转账 | Saga 在另一个账户上的入账已存储 |
| E4 读取 | 按 id 读一个账户，或读一页十个 | 应答本身 |

- **峰值：** 失败命令不超过 1% 的最好一级闭环负载。**≥** 表示测量本身无法再往上走的峰值。
- **可持续：** p99 保持在 200 ms 以内的最高速率：在工作站上要求没有错误，在云端 runner 上要求错误不超过 1%，并以开环二分查找得出。规划时用这个数字。
- **延迟只算已应答的命令。** 越过峰值后，被拒绝的命令立刻返回，所以要看的是应答速率。
- **每个数字都注明其构建、CPU 与 JDK，没有跨构建计算的数字。** 比较只在同一台机器上的同一批测量中进行，两侧交替运行（与 Axon 比较时为 W A A W），让两侧在同一时间面对同一台机器。两次运行的两个数字从不并列成一个差值。
- 完整方法与原始数据见[基准报告](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/results/reports)。

## 在一台 4 核云端 runner 上 {#on-a-4-core-cloud-runner}

一个节点运行在 4 核、15～16 GB 的 GitHub Actions runner 上，100,000 个账户。测试工具把节点、数据库与 Kafka 绑定在其中 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.server -->3<!-- /perf --> 个核上，负载生成器绑定在另外 <!-- perf:actions-snapshot/sustainable.postgres.E1:cpus.load -->1<!-- /perf --> 个上；下文的阶梯、冷缓存与三节点运行也这样绑定。每个套件在各自的 runner 上运行，其 CPU 型号写在所在行。

<!-- performance:begin actions -->
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
<!-- performance:end -->

在 runner 上没有测量的：PostgreSQL 上 E2 与 E4 的可持续速率。

## 轻负载时 {#at-light-load}

大部分时间里，服务运行在远低于峰值的速率上。这时一次写入的延迟主要是数据库向磁盘同步的时间，而不是节点的代码。

- **云端 runner 上的 Saga 链，PostgreSQL**：转账速率 <!-- perf:actions-snapshot/cliff.postgres.E3.50:value -->50/s<!-- /perf --> 时，p50 <!-- perf:actions-snapshot/cliff.postgres.E3.50:p50Ms -->2.7 ms<!-- /perf -->，p99 <!-- perf:actions-snapshot/cliff.postgres.E3.50:p99Ms -->7.6 ms<!-- /perf -->；<!-- perf:actions-snapshot/cliff.postgres.E3.500:value -->500/s<!-- /perf --> 时，p50 <!-- perf:actions-snapshot/cliff.postgres.E3.500:p50Ms -->1.7 ms<!-- /perf -->，p99 <!-- perf:actions-snapshot/cliff.postgres.E3.500:p99Ms -->14.2 ms<!-- /perf -->（<!-- perf:actions-snapshot/cliff.postgres.E3.500:cpu -->Xeon 6975P-C<!-- /perf -->，构建 <!-- perf:actions-snapshot/cliff.postgres.E3.500:commit -->`e3a4e937aa`<!-- /perf -->）。
- **同样的测量在 MongoDB 上**：<!-- perf:actions-snapshot/cliff.mongo.E3.50:value -->50/s<!-- /perf --> 时，p50 <!-- perf:actions-snapshot/cliff.mongo.E3.50:p50Ms -->5 ms<!-- /perf -->，p99 <!-- perf:actions-snapshot/cliff.mongo.E3.50:p99Ms -->14.8 ms<!-- /perf -->；<!-- perf:actions-snapshot/cliff.mongo.E3.500:value -->500/s<!-- /perf --> 时，p50 <!-- perf:actions-snapshot/cliff.mongo.E3.500:p50Ms -->8.4 ms<!-- /perf -->，p99 <!-- perf:actions-snapshot/cliff.mongo.E3.500:p99Ms -->62.5 ms<!-- /perf -->（<!-- perf:actions-snapshot/cliff.mongo.E3.500:cpu -->Xeon 6975P-C<!-- /perf -->）。
- **这对读者意味着什么：** 降低轻负载延迟的，是同步又快又稳的存储。

## 一个节点在工作站上能走多远 {#how-far-one-node-goes-on-a-workstation}

在一台 Apple M5 Max（18 核，64 GB）上做从 1 到 4,096 个客户端的闭环扫描，数据库、Kafka 与负载生成器在同一台机器上，100,000 个账户。图为应答速率对 p99，画出了 200 ms 线；空心点是节点拒绝了超过 1% 命令的那一级。

<PerfFigure name="m5max-sweep" />

<!-- performance:begin m5max -->
<!-- 由 .github/scripts/docs-performance.mjs 依据各报告的 headline.json 生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

每个数字都来自其所在行注明的构建、JDK 与机器。各场景见[测了什么](#what-was-measured)。

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

## 一个热点聚合 {#a-hot-aggregate}

一个聚合的命令在它的所有者上一条接一条地执行，所以无论集群多大，一个热点账户都限定了单个聚合能承受的速率。在工作站上，一个热点账户的峰值在 PostgreSQL 上为 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-hot-sweep:value -->11,853.7/s<!-- /perf -->，在 MongoDB 上为 <!-- perf:m5max-snapshot/sweep.mongo.1.e1-hot-sweep:value -->9,948.1/s<!-- /perf -->；按 Zipf 分布取键时峰值为 <!-- perf:m5max-snapshot/sweep.postgres.1.e1-zipf-sweep:value -->19,746.8/s<!-- /perf --> 与 <!-- perf:m5max-snapshot/sweep.mongo.1.e1-zipf-sweep:value -->15,957.5/s<!-- /perf -->。模型允许时，把负载分散到多个聚合上。

## 越过峰值 {#past-the-peak}

节点拒绝它承受不了的命令，返回 `429 wow.command.busy` 与 `Retry-After`。它不会无限排队：越过峰值后它继续应答，同时立刻拒绝其余命令，上图的空心点就是每一级仍在应答的速率。

仍然存在的限制：Saga 的命令要等一个订阅，而准入看不到订阅的队列。下游订阅滞后时，过载仍可能在 10 s 后表现为 `504`，而不是 `429`。

## 冷缓存 {#a-cold-cache}

一百万个账户、没有任何缓存，在云端 runner 上（构建 <!-- perf:actions-snapshot/cold.postgres.E1:commit -->`e3a4e937aa`<!-- /perf -->；PostgreSQL 的 runner 为 <!-- perf:actions-snapshot/cold.postgres.E1:cpu -->Xeon 6975P-C<!-- /perf -->，MongoDB 的为 <!-- perf:actions-snapshot/cold.mongo.E1:cpu -->EPYC 9V74<!-- /perf -->）：

- 以 <!-- perf:actions-snapshot/cold.postgres.E1:value -->200/s<!-- /perf --> 写入时，p99 在 PostgreSQL 上为 <!-- perf:actions-snapshot/cold.postgres.E1:p99Ms -->5.1 ms<!-- /perf -->，在 MongoDB 上为 <!-- perf:actions-snapshot/cold.mongo.E1:p99Ms -->154.6 ms<!-- /perf -->；
- 以 <!-- perf:actions-snapshot/cold.postgres.E4-state:value -->500/s<!-- /perf --> 按 id 读取时，为 <!-- perf:actions-snapshot/cold.postgres.E4-state:p99Ms -->3 ms<!-- /perf --> 与 <!-- perf:actions-snapshot/cold.mongo.E4-state:p99Ms -->4.9 ms<!-- /perf -->。

## 三个节点，以及失去一个节点 {#three-nodes-and-a-node-lost}

同一台云端 runner 上的三个节点，与数据库、Kafka 共用 <!-- perf:actions-snapshot/cluster.postgres.e1-steady:cpus.server -->3<!-- /perf --> 个核（构建 <!-- perf:actions-snapshot/cluster.postgres.e1-steady:commit -->`e3a4e937aa`<!-- /perf -->；PostgreSQL 的 runner 为 <!-- perf:actions-snapshot/cluster.postgres.e1-steady:cpu -->EPYC 7763<!-- /perf -->，MongoDB 的为 <!-- perf:actions-snapshot/cluster.mongo.e1-steady:cpu -->Xeon 6975P-C<!-- /perf -->）：

- **稳态下**，以 <!-- perf:actions-snapshot/cluster.postgres.e1-steady:value -->150/s<!-- /perf --> 写入时，p99 在 PostgreSQL 上为 <!-- perf:actions-snapshot/cluster.postgres.e1-steady:p99Ms -->14.1 ms<!-- /perf -->，在 MongoDB 上为 <!-- perf:actions-snapshot/cluster.mongo.e1-steady:p99Ms -->13.7 ms<!-- /perf -->。
- **运行中停掉一个节点：** 它的分区在几秒内移到其他节点。写入最差一秒的 p99 在 PostgreSQL 上为 <!-- perf:actions-snapshot/cluster.postgres.e1-handover:worstSecondP99Ms -->3,129.3 ms<!-- /perf -->，在 MongoDB 上为 <!-- perf:actions-snapshot/cluster.mongo.e1-handover:worstSecondP99Ms -->1,671.2 ms<!-- /perf -->；失败的写入分别为 <!-- perf:actions-snapshot/cluster.postgres.e1-handover:failed -->0<!-- /perf --> 与 <!-- perf:actions-snapshot/cluster.mongo.e1-handover:failed -->0<!-- /perf --> 条。在同一次交接中，saga 链在 MongoDB 上失败 <!-- perf:actions-snapshot/cluster.mongo.e3-handover:failed -->39<!-- /perf --> 条，在 PostgreSQL 上失败 <!-- perf:actions-snapshot/cluster.postgres.e3-handover:failed -->1<!-- /perf --> 条，各为一次运行，构建为 <!-- perf:actions-snapshot/cluster.mongo.e3-handover:commit -->`e3a4e937aa`<!-- /perf -->。MongoDB 上的失败都发生在被停节点重启之后、它的分区逐个追赶期间：这正是 [#6407](https://github.com/Ahoo-Wang/Wow/issues/6407)，此构建之后由 [#6412](https://github.com/Ahoo-Wang/Wow/pull/6412) 修复（时间线见[报告](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md#l4-cluster-and-hand-over-e5)）。
- **没有测量：跨多台机器的扩展。** 这些节点与数据库共用一台机器，所以它们显示的是集群的开销，而不是它带来的收益。[容量规划](../operate/performance)给出上限，以及在你自己的机器上要做的测量。

## 与 Axon Framework 4 对比 {#against-axon-framework-4}

**这里的"公平"指什么：**

- **两侧同样的设置：** 同样的领域、负载生成器、测试工具和 PostgreSQL，同样的连接池（Spring Boot 的 10 个），另有一组 32 个的测量单列。
- **Axon 4 按其参考指南在没有 Axon Server 时配置生产环境的方式：** JDBC 事件存储与令牌存储、16 段的 tracking processor、一个缓存，每 100 个事件一个快照。
- **Wow 关闭发布**，因为 Axon 不发布任何东西；两侧都是 10 s 超时。
- **噪声：** 两侧在同一批测量中交替运行，W A A W；落在一侧自身 A/A 波动之内的差别称为持平。
- **正确性：** 每次会话之后都检查其数据库；数据有误的一侧不参与比较。

在一台 4 核的 GitHub Actions runner 上，节点（Wow 或 Axon 的）与 PostgreSQL 绑定在其中 <!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:cpus.server -->2<!-- /perf --> 个核上，负载生成器绑定在另外 <!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:cpus.load -->2<!-- /perf --> 个上；每个区间是 Wow 的速率除以 Axon 的，画在两侧自身波动的范围之内；空心区间为持平：

<PerfFigure name="actions-axon" />

在 18 核上（工作站），在它自己的一批测量中、按它自己的构建：

<PerfFigure name="m5max-axon" />

没有比较的：调优过的 Axon（Disruptor 命令总线、批处理）、Axon 5、Axon Server，以及开启 Kafka 的 Wow。

## 自己运行 {#run-it-yourself}

在你自己的机器上，需要 Docker 与 JDK 25：

```bash
test/benchmarks/harness/bench.sh build
test/benchmarks/harness/bench.sh up --backend=postgres --accounts=100000 --prime
test/benchmarks/harness/bench.sh saturate --reuse --backend=postgres --accounts=100000 --scenario=E1 --sweep
test/benchmarks/harness/bench.sh down
```

在 GitHub 的 runner 上：`gh workflow run bench.yml -f tier=L4 -f head_ref=<tag>`。所有选项见[基准测试 README](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks)。

## 限制在哪里 {#where-the-limits-are}

| 限制 | 怎么办 |
| --- | --- |
| 一个数据库承载所有节点 | 更快或更少共享的数据库；MongoDB 分片；聚合类型放在不同的存储上 |
| 一个热点聚合（[见上](#a-hot-aggregate)） | 建模时让负载分散到多个聚合上 |
| 轻负载延迟取决于磁盘同步 | 同步又快又稳的存储 |
| 一个聚合类型的日志在一个数据库上 | 计划在 10.x 中解决 |
| 每个事件的字节数比 Axon 多 | 让发了两次的命令只处理一次的代价 |
| 准入看不到订阅的队列 | 按可持续速率规划，而不是峰值 |

以及测量本身的限制：数据库、负载生成器与节点共用一台机器；工作站的数字都是单次运行；没有在多台机器上测量集群；云端 runner 的数字随其 CPU 型号变化，每行都注明了型号。

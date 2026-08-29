# Query Gateway / ObjectNode 后端性能证据（2026-08-29）

## 结论

本轮性能验收为 **BLOCKED / MISSING EVIDENCE**，Task 12 与分支合并均保持阻塞，不能宣称 32 个参数组合全部完成：

- Elasticsearch 的 16 个组合已在 main 与 V9 上完成，共得到 64 条 JMH 原始记录（每个版本 16 个组合 × throughput/sample 两种 mode）。
- MongoDB 的 16 个组合没有结果。仓库 pin 的 8.3.4、compose 默认的 8.3.7，以及经明确批准用于两轮相对比较的 8.3.8，均因 Docker VM 内核兼容性在启动前退出；未使用 mock、内存 Backend 或外推数值补表。
- 已完成的 Elasticsearch 局部证据中，V9 有 13/16 行吞吐下降、13/16 行 p95 上升、16/16 行每记录分配量上升。分配量增加范围为 `+2.89%..+33.92%`，因此这是需要评审的回归信号；本任务按约束只记录证据，不增加第二条 Mongo normalizer，也不在生产代码中做性能优化。
- 这些 Elasticsearch 不利信号尚未被接受为生产风险，目前不存在产品性能预算或风险接受结论。关闭该缺口需要先定义产品预算，再用更长、稳定的同环境运行复测；若结果超过未来预算，应调查并优化 `ObjectNode` 转换路径，然后重跑完整矩阵。

这只是同一台本机、固定短迭代下的框架相对证据，不是生产容量、SLO 或跨环境结论。

## 基准设计与 source equivalence

两轮都查询同一类 Snapshot 模型，参数矩阵为：

```text
storage   = mongo | elasticsearch
operation = single | list100 | list1000 | paged100
result    = dynamic | typed
masking   = none | inPlace
```

共同工作负载：

- 数据集固定为 1,000 条 `MaterializedSnapshot<QueryBenchmarkState>`；seed 为 `20260829`。
- 每条文档包含相同的 envelope，以及 `state.id`、`state.group`、128 字符 `state.payload` 和可选 `state.maskProbe`。
- `single` 用固定首个 aggregate id；`list100`、`list1000` 使用 `MatchAllFilter` 并完整 `collectList()`；`paged100` 查询第一页并完整物化 100 条记录。
- Elasticsearch `queryBatchSize=100`，所以 `list1000` 经过真实 PIT/search-after 分页路径；其余操作经过具体查询实现的普通搜索路径。
- `inPlace` 只把可选 `state.maskProbe` 原位改为 `***`。setup 在正式测量前校验返回条数及 mask 结果。
- benchmark 方法对阻塞完成后的完整 single/list/page 对象调用 `Blackhole.consume`，不是只消费 Publisher 创建。
- dynamic 与 typed 在每个 trial 内共享同一个 Gateway 及同一个存储实现；差异只在最终结果 API。

由于 main 的 QueryService API 与 V9 的 Backend/Gateway API 不能由同一份源码编译，main 使用 detached worktree 中的 API 专用源。共享的数据生成、存储 seed、查询、完整消费、参数和校验代码逐字相同；API 差异如下：

| 项目 | main `2e43a9f0d` | V9 `6b67462c8` + benchmark source |
| --- | --- | --- |
| Mongo 实现 | `MongoSnapshotQueryService` | `MongoSnapshotQueryBackend` |
| Elasticsearch 实现 | `ElasticsearchSnapshotQueryService` | `ElasticsearchSnapshotQueryBackend` |
| Gateway terminal | `TailSnapshotQueryFilter` 绑定同一 QueryService | `DefaultSnapshotQueryGateway` 直接绑定同一 Backend |
| dynamic | 旧 `DynamicDocument` | `ObjectNode` |
| typed | 旧 QueryService 直接物化 | 同一 `ObjectNode` 链末端 Jackson 物化 |
| mask | 旧 API 原生的 in-place `StateDynamicDocumentMasker`；typed state 的 `DataMasking.mask()` 原位写同一字段 | 一个 in-place `StateObjectNodeMasker`，在 dynamic/typed 共同结果链写同一字段 |
| 聚合元数据 | 临时 `src/main/resources/META-INF/wow-metadata.json` 仅供旧 typed QueryService 解析 state 类型 | Gateway 显式持有 `JavaType`，无需临时 metadata |

旧 API 不存在 ObjectNode masker，因此 main 使用其原生 mutable result boundary 完成同一个单字段原位写入；这是唯一不可逐字相同的实现差异，也是被测重构的一部分。没有为 main 或 V9 添加 fake Backend。

源证据：

- V9 benchmark：`wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QueryGatewayBackendBenchmark.kt`，SHA-256 `8f35e8b5024c9bffd7a75bbc46f3c68b062fe7ac59bc1b88232702d742a1ad20`。
- main API 专用 benchmark：`docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/QueryGatewayBackendBenchmark-main.kt`，SHA-256 `fe71b30975917bc6d0660378606d8d2628e394fcc8be153d1f3f223a0506fd86`。
- main metadata：`docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/META-INF/wow-metadata.json`，SHA-256 `5753d0aa17512785454f6558e7a19ffddda6ce19925c5d3db59d1a7c90461a30`。

## 环境

| 项目 | 值 |
| --- | --- |
| Host | Apple M4 Pro，14 logical CPUs |
| OS | macOS 26.5.2 (25F84)，Darwin 25.5.0 arm64 |
| Docker VM kernel | `7.0.12-linuxkit` |
| Docker | client/server 29.7.2 |
| JDK | Azul Zulu OpenJDK 17.0.7+7-LTS |
| JVM | OpenJDK 64-Bit Server VM，G1，`-Xms1g -Xmx1g -XX:+UseG1GC` |
| JMH | 1.37，1 thread，3 forks，5 × 200ms warmup，10 × 200ms measurement |
| Modes / profiler | `thrpt,sample`；`-prof gc` |
| main commit | `2e43a9f0d3fee099ee249bc75f55f8678bb27635` |
| V9 production commit | `6b67462c8645fe135f34c882f90dc442e2dd84c9` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.2.6`；image ID `sha256:8fb2a046f8adf4e8d64066206ebcb798bef8ff4420379feedd427c30500c5d3c`；repo digest `sha256:e5673d86bb6a41ed543329ec094fc93d5ef749d32cc87a4150a15d520ad9c670` |

main 与 V9 没有并发运行；正式采样期间没有并发 Gradle build、镜像拉取或 Mongo 重启负载。

## 复现命令与原始输出

main harness 在 detached worktree `2e43a9f0d` 编译：

```bash
./gradlew :wow-benchmarks:compileJmhKotlin --stacktrace
./gradlew :wow-benchmarks:jmhJar --stacktrace
```

V9 先按任务简报原样运行：

```bash
./gradlew :wow-benchmarks:jmh \
  -Pjmh.include='.*QueryGatewayBackendBenchmark.*' \
  -Pjmh.profilers=gc
```

该命令退出码为 0，但仓库明确输出 `:wow-benchmarks:jmh SKIPPED`，因为通用 `jmh` task 已禁用。两轮正式证据因此都从各自构建的 `jmhJar` 使用完全相同的 JMH CLI：

```bash
java -jar wow-benchmarks/build/libs/wow-benchmarks-8.16.1-jmh.jar \
  '.*QueryGatewayBackendBenchmark.query' \
  -p storage=elasticsearch \
  -wi 5 -w 200ms -i 10 -r 200ms -f 3 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff <result.json> -o <human.txt>
```

正式采样的原始输出已从本地 JMH 结果目录逐字节复制到 committed artifact 目录；JSON 保留每个 fork 的 `rawData` 与 percentile，human output 保留 sample histogram/percentile 表：

| 版本 | committed JSON | JSON SHA-256 | committed human output | text SHA-256 |
| --- | --- | --- | --- | --- |
| main | `docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/main-elasticsearch.json` | `19f4c438e8563a171c3b7d95db5ce95bf54413399df1743f93ef1d466011ccc5` | `docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/main-elasticsearch.txt` | `55115865cadb2446af95964ca6758c5b9f10934573305d7102cf96157adb7f3a` |
| V9 | `docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/v9-elasticsearch.json` | `f7d8389575dde7c05f3e64f7bfa4b927339880efa73403b94d87ce9fce311199` | `docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/v9-elasticsearch.txt` | `bbf245afe7177a805e3ce6cc66963ed14eae7aa37d0a44e958f89e4d9c6b13f5` |

原始 JMH text 的 histogram 行包含生成器写入的行尾空格，JSON 也保留生成时的 EOF 布局；根 `.gitattributes` 只把本 artifact 目录的 `*-elasticsearch.json` / `*-elasticsearch.txt` 标记为 `binary`，从而保持上述字节与哈希不变，同时让 diff whitespace 检查继续覆盖可编辑源码与 Markdown。

两份 JSON 均通过以下结构校验：32 条记录、16 个唯一参数组合、每个 mode 16 条、每条 `forks=3`、无缺失 `gc.alloc.rate.norm`、无缺失 sample `95.0` percentile。

最终 gate 命令、时间、环境、退出码、BUILD tail、测试计数与本地 source-log SHA-256 记录在 `docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/gate-manifest.md`。

## 指标定义

- `throughput`：throughput mode 的 `primaryMetric.score`，单位 `ops/s`；变化为 `(V9/main - 1) × 100%`，越高越好。
- `p95`：sample mode 的 `primaryMetric.scorePercentiles["95.0"]`，由 `s/op` 转为毫秒；变化为 `(V9/main - 1) × 100%`，越低越好。
- `B/record`：throughput mode 的 `gc.alloc.rate.norm` 除以本操作实际返回记录数；single 除以 1，list100/paged100 除以 100，list1000 除以 1,000。变化越低越好。

## 完整 32 行矩阵

| Storage | Operation | Result | Mask | main ops/s | V9 ops/s | Δ throughput | main p95 ms | V9 p95 ms | Δ p95 | main B/record | V9 B/record | Δ allocation |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Elasticsearch | single | dynamic | none | 1614.49 | 1867.69 | +15.68% | 0.682 | 0.695 | +1.96% | 53657.9 | 55346.6 | +3.15% |
| Elasticsearch | single | dynamic | inPlace | 1970.39 | 1959.91 | -0.53% | 0.695 | 0.716 | +2.92% | 53679.7 | 55583.2 | +3.55% |
| Elasticsearch | single | typed | none | 1903.96 | 1720.23 | -9.65% | 0.700 | 0.711 | +1.46% | 60129.4 | 62042.4 | +3.18% |
| Elasticsearch | single | typed | inPlace | 2015.72 | 1965.46 | -2.49% | 0.701 | 0.815 | +16.23% | 60344.6 | 62088.7 | +2.89% |
| Elasticsearch | list100 | dynamic | none | 985.13 | 937.20 | -4.87% | 1.335 | 1.370 | +2.61% | 5227.6 | 6981.9 | +33.56% |
| Elasticsearch | list100 | dynamic | inPlace | 986.40 | 927.21 | -6.00% | 1.217 | 1.818 | +49.44% | 5229.5 | 7000.3 | +33.86% |
| Elasticsearch | list100 | typed | none | 808.63 | 775.99 | -4.04% | 1.571 | 1.626 | +3.52% | 11684.8 | 13420.6 | +14.85% |
| Elasticsearch | list100 | typed | inPlace | 778.99 | 784.62 | +0.72% | 1.536 | 1.622 | +5.60% | 11746.9 | 13235.9 | +12.68% |
| Elasticsearch | list1000 | dynamic | none | 77.21 | 80.27 | +3.96% | 14.107 | 14.909 | +5.69% | 5285.3 | 7039.8 | +33.20% |
| Elasticsearch | list1000 | dynamic | inPlace | 83.04 | 80.33 | -3.27% | 20.141 | 24.707 | +22.67% | 5285.2 | 7064.2 | +33.66% |
| Elasticsearch | list1000 | typed | none | 71.09 | 68.61 | -3.49% | 16.367 | 17.334 | +5.91% | 11723.6 | 13497.9 | +15.13% |
| Elasticsearch | list1000 | typed | inPlace | 72.07 | 68.53 | -4.90% | 21.004 | 17.626 | -16.08% | 11803.5 | 13291.2 | +12.60% |
| Elasticsearch | paged100 | dynamic | none | 980.79 | 969.26 | -1.18% | 1.262 | 1.333 | +5.68% | 5210.6 | 6965.4 | +33.68% |
| Elasticsearch | paged100 | dynamic | inPlace | 997.85 | 931.99 | -6.60% | 2.126 | 1.325 | -37.67% | 5217.0 | 6986.6 | +33.92% |
| Elasticsearch | paged100 | typed | none | 852.65 | 797.69 | -6.45% | 1.592 | 1.605 | +0.82% | 11653.8 | 13409.0 | +15.06% |
| Elasticsearch | paged100 | typed | inPlace | 833.50 | 801.26 | -3.87% | 1.645 | 1.544 | -6.10% | 11739.5 | 13228.4 | +12.68% |
| MongoDB | single | dynamic | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | single | dynamic | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | single | typed | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | single | typed | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list100 | dynamic | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list100 | dynamic | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list100 | typed | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list100 | typed | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list1000 | dynamic | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list1000 | dynamic | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list1000 | typed | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | list1000 | typed | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | paged100 | dynamic | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | paged100 | dynamic | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | paged100 | typed | none | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |
| MongoDB | paged100 | typed | inPlace | MISSING EVIDENCE | MISSING EVIDENCE | — | — | — | — | — | — | — |

## MongoDB 阻塞证据

三个真实镜像均由同一 compose 配置启动；没有修改仓库的 image pin：

```bash
docker compose --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.mongo.yml up -d --wait

WOW_BENCHMARK_MONGO_IMAGE=mongo:8.3.7 docker compose \
  --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.mongo.yml up -d --wait

WOW_BENCHMARK_MONGO_IMAGE=mongo:8.3.8 docker compose \
  --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.mongo.yml up -d --wait
```

每次 compose 均报告 `container wow-benchmark-mongo is unhealthy`，容器日志相同：

```text
MongoDB cannot start: Linux kernel versions 6.19 and newer has a known incompatibility with this version of MongoDB. See https://jira.mongodb.org/browse/SERVER-121912 for more information.
```

镜像证据：

| Image | image ID | repo digest |
| --- | --- | --- |
| `mongo:8.3.4` | `sha256:6818b4556f741c6d220e7edf3d51730719d965f51d5c4e80ac9e0eeafcaac94d` | `sha256:48a009d2d8007e92d6d7e8baa31713cd11c48c06e827e856240e5a1d319b49d9` |
| `mongo:8.3.7` | `sha256:6285ae7ce4648634b93894bb21de0784ff81d109b3350b64f1f4331717f5c783` | `sha256:2f02e2184c6d91c3208e5ab75a0707d1386a05377b20fdaf49a314815774d863` |
| `mongo:8.3.8` | `sha256:d50db15ba4794a2940fc2173104abf0540dac953de03a0d66be1a1361ddce80c` | `sha256:5211c51171f57ae60842b11664bb244628971b3d35325762a97888337b9bb0db` |

因此没有 main/V9 Mongo 数值，也不能判断 Mongo byte round-trip 是否是主要瓶颈。后续必须在 Mongo 能启动、且 main/V9 继续使用同一镜像/JDK/JVM/host/JMH 设置的环境重跑全部 16 个 Mongo 组合，才能关闭性能验收。

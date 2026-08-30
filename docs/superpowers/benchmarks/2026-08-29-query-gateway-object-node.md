# Query Gateway / ObjectNode 后端性能证据（2026-08-30）

> **临时 Mask 降级说明：** `inPlace` 组合与文中 V9 source checksum 是删除 Mask 前的历史证据。当前 benchmark 已移除 masking 参数和相关实现，只测量无 Mask 的 Gateway/Backend/ObjectNode 路径；原始结果与 artifact 不追溯改写，也不作为当前 HEAD 的新性能证明。

## 结论

本轮 32 个参数组合已全部完成，性能验收为 **PASS（accepted scoped exception）**：

- 仓库现有正式基准规则定义 throughput、latency、allocation 阈值均为 `10%`；越阈值且区间不重叠才是回归候选，候选需做受控配对复测。
- 短矩阵覆盖 Elasticsearch/MongoDB 各 16 个组合，main/V9 各含 throughput/sample，共 128 条 JMH 记录；它只用于筛选候选。
- 定向确认采用 `5 × 1s` warmup、`5 × 1s` measurement、单 fork，按 AB/BA/AB 三轮交替 main/V9；每轮同时采集 throughput、p95 与 allocation。
- Elasticsearch 15 个候选最终为 `PASS=1`、`INCONCLUSIVE=14`、已确认回归 `0`。所有 allocation 配对区间都在 ±10% 预算内；外部服务 throughput/p95 有显著顺序效应，不能据此宣称回归。
- MongoDB 14 个候选最终为已确认回归 `12`、`INCONCLUSIVE=2`。12 行均由 allocation 触发；另有 2 行同时确认 throughput 回归，未确认 p95 回归。
- Mongo allocation 的根因是 API 合约差异：旧 dynamic 路径直接复用驱动解码出的 `Document`/Map 图；V9 必须再构造完整 `ObjectNode`，typed 路径随后还要物化领域对象。SPI 已移除 bytes→tree 中转，但不能移除合约要求的树。
- 已明确接受 MongoDB ObjectNode 合约的 scoped 性能例外：覆盖 12 行确定性 allocation 回归及其中 2 行伴随的 throughput 回归；不扩展为其他存储、指标或未来回归的通用豁免。
- 不实现自定义 BSON→ObjectNode 驱动 codec；其 BSON 类型兼容、wire 语义与维护成本高于本轮已接受的局部收益风险。

这份证据用于重构前后的相对比较，不代表生产容量或 SLO。

## 已落地的最小优化

- Elasticsearch 直接把 `_source` 反序列化为 `ObjectNode`，移除 `Map -> ObjectNode` 的双对象图。
- MongoDB `Document.toObjectNode()` 直接调用全局 `JsonSerializer.valueToTree()`；`MongoJacksonModule` 通过 `wow-mongo/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule` 被 `findAndAddModules()` 自动发现，统一处理 `Decimal128` 与 BSON `Binary` 的 `ByteArray`。
- typed 结果使用 Jackson `treeToValue`，不再经 `convertValue` 的 `TokenBuffer` 中转。

没有增加 serializer factory、第二套 ObjectMapper 或缓存层；现有 SPI、Jackson tree API 已覆盖需求。

## 配对确认

配对比值均为 `V9/main`；throughput 越高越好，allocation/p95 越低越好。95% CI 由三轮对数比值计算，`t(2)=4.303`。原始成对分数与可重算摘要保存在 artifact 目录的 `paired-*.tsv`。

Elasticsearch 的 15 个候选没有确认回归。其稳定 allocation 结果为：dynamic `+0.7%..+3.8%`，typed `-4.5%..-0.5%`；其余 throughput/p95 区间跨越预算边界，按仓库规则保持 `INCONCLUSIVE`，不视为失败。

MongoDB 的确认结果：

| Case | Throughput ratio [95% CI] | Allocation ratio [95% CI] | P95 ratio [95% CI] | Verdict |
| --- | --- | --- | --- | --- |
| list100/dynamic/inPlace | 0.895 [0.764, 1.048] | 1.532 [1.523, 1.540] | 2.019 [0.117, 34.800] | REGRESSION: allocation |
| list100/dynamic/none | 0.893 [0.838, 0.953] | 1.521 [1.520, 1.521] | 0.436 [0.007, 25.256] | REGRESSION: allocation |
| list100/typed/inPlace | 1.646 [0.218, 12.408] | 1.103 [1.103, 1.103] | 1.017 [0.907, 1.139] | REGRESSION: allocation |
| list100/typed/none | 1.473 [0.212, 10.247] | 1.131 [1.131, 1.132] | 1.077 [0.962, 1.206] | REGRESSION: allocation |
| list1000/dynamic/inPlace | 0.792 [0.723, 0.868] | 1.578 [1.578, 1.578] | 1.200 [0.960, 1.502] | REGRESSION: throughput, allocation |
| list1000/dynamic/none | 0.859 [0.728, 1.013] | 1.564 [1.564, 1.564] | 1.250 [0.936, 1.670] | REGRESSION: allocation |
| list1000/typed/inPlace | 0.960 [0.811, 1.136] | 1.106 [1.106, 1.106] | 0.999 [0.887, 1.124] | REGRESSION: allocation |
| list1000/typed/none | 0.990 [0.940, 1.042] | 1.135 [1.135, 1.135] | 0.965 [0.861, 1.081] | REGRESSION: allocation |
| paged100/dynamic/inPlace | 1.013 [0.907, 1.132] | 1.484 [1.483, 1.486] | 1.040 [0.907, 1.193] | REGRESSION: allocation |
| paged100/dynamic/none | 0.996 [0.987, 1.005] | 1.480 [1.479, 1.482] | 1.031 [0.856, 1.242] | REGRESSION: allocation |
| paged100/typed/inPlace | 0.775 [0.726, 0.827] | 1.101 [1.100, 1.101] | 1.176 [1.022, 1.354] | REGRESSION: throughput, allocation |
| paged100/typed/none | 1.004 [0.421, 2.394] | 1.128 [1.125, 1.131] | 0.442 [0.005, 36.000] | REGRESSION: allocation |
| single/dynamic/none | 1.006 [0.772, 1.311] | 1.077 [0.999, 1.161] | 2.587 [0.042, 158.955] | INCONCLUSIVE |
| single/typed/none | 1.031 [0.950, 1.119] | 1.042 [1.038, 1.046] | 1.037 [0.775, 1.387] | INCONCLUSIVE |

## 基准设计

参数矩阵：

```text
storage   = mongo | elasticsearch
operation = single | list100 | list1000 | paged100
result    = dynamic | typed
masking   = none | inPlace
```

共同工作负载：

- 固定 1,000 条 `MaterializedSnapshot<QueryBenchmarkState>`，seed `20260829`。
- `single` 查询固定 aggregate id；`list100`、`list1000` 完整收集结果；`paged100` 完整物化第一页 100 条。
- `inPlace` 在 Gateway 结果链原位把 `state.maskProbe` 改为 `***`；setup 在测量前校验条数与 mask。
- benchmark 阻塞等待并消费完整结果，不只消费 Publisher。
- main 使用旧 QueryService/DynamicDocument API 专用 harness；V9 使用 QueryBackend/QueryGateway/ObjectNode。数据、查询和 JMH 参数相同，API 边界差异正是被测重构。

源文件：

- V9 benchmark：`wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QueryGatewayBackendBenchmark.kt`，SHA-256 `8f35e8b5024c9bffd7a75bbc46f3c68b062fe7ac59bc1b88232702d742a1ad20`。
- main harness：`docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/QueryGatewayBackendBenchmark-main.kt`，SHA-256 `fe71b30975917bc6d0660378606d8d2628e394fcc8be153d1f3f223a0506fd86`。
- main metadata：`docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/META-INF/wow-metadata.json`，SHA-256 `5753d0aa17512785454f6558e7a19ffddda6ce19925c5d3db59d1a7c90461a30`。

## 环境与复现

| 项目 | 值 |
| --- | --- |
| Host | Apple M4 Pro，14 logical CPUs |
| OS | macOS 26.5.2 (25F84)，Darwin 25.5.0 arm64 |
| Docker | Desktop 4.88.1；client/server 29.7.2；VM kernel `7.0.12-linuxkit` |
| JDK | Azul Zulu OpenJDK 17.0.7+7-LTS |
| JVM | G1，`-Xms1g -Xmx1g -XX:+UseG1GC` |
| JMH | 1.37；1 thread；3 forks；5 × 200 ms warmup；10 × 200 ms measurement；`thrpt,sample`；`-prof gc` |
| main source | `2e43a9f0d3fee099ee249bc75f55f8678bb27635` |
| V9 source | `b56f9cbb39db25ffe4f32d0591b9f6c90d18b8c9` + 本轮未提交优化 |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.2.6` |
| MongoDB | 官方 macOS ARM64 8.3.8；tarball SHA-256 `00e8d49b4ee064cef23a42af09cdf8f1c06cf4cdee16e747db95406843b08472`；`mongod` SHA-256 `f17130625c4abb436006eee52653c6f472cd0d29a16aee880907563c14f46449` |

Docker 中的 MongoDB 8.3.4/8.3.7/8.3.8 均被 Linux 7.0.12 内核兼容性检查拒绝启动；Docker Desktop 已是当前可用最新版，因此经确认改用官方 macOS ARM64 tarball。下载入口与安装方式见 [MongoDB Community Download](https://www.mongodb.com/try/download/community-edition/releases) 和 [MongoDB macOS tarball 安装文档](https://www.mongodb.com/docs/v8.0/tutorial/install-mongodb-on-os-x-tarball/)。原生进程保留 compose 等价的鉴权、WiredTiger cache/compressor 与诊断参数。

两种存储仅替换 `storage` 参数：

```bash
java -jar wow-benchmarks/build/libs/wow-benchmarks-8.16.1-jmh.jar \
  '.*QueryGatewayBackendBenchmark.query' \
  -p storage=<mongo|elasticsearch> \
  -wi 5 -w 200ms -i 10 -r 200ms -f 3 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff <result.json> -o <human.txt>
```

## 指标定义

- throughput：`ops/s`，变化 `(V9/main - 1) × 100%`，越高越好。
- p95：sample mode `95.0` percentile，转为毫秒；变化越低越好。
- B/record：`gc.alloc.rate.norm` 除以操作返回记录数；变化越低越好。

## 完整 32 行矩阵

| Storage | Operation | Result | Mask | main ops/s | V9 ops/s | Δ throughput | main p95 ms | V9 p95 ms | Δ p95 | main B/record | V9 B/record | Δ allocation |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Elasticsearch | list100 | dynamic | inPlace | 955.31 | 455.39 | -52.33% | 1.286 | 2.259 | +75.62% | 5229.7 | 5433.8 | +3.90% |
| Elasticsearch | list100 | dynamic | none | 955.31 | 906.57 | -5.10% | 1.315 | 1.827 | +38.94% | 5227.4 | 5412.0 | +3.53% |
| Elasticsearch | list100 | typed | inPlace | 796.10 | 356.01 | -55.28% | 1.556 | 2.502 | +60.79% | 11766.1 | 11243.8 | -4.44% |
| Elasticsearch | list100 | typed | none | 790.71 | 828.38 | +4.76% | 1.522 | 2.126 | +39.70% | 11665.5 | 11410.5 | -2.19% |
| Elasticsearch | list1000 | dynamic | inPlace | 82.62 | 66.04 | -20.06% | 14.352 | 16.918 | +17.88% | 5285.1 | 5485.5 | +3.79% |
| Elasticsearch | list1000 | dynamic | none | 78.90 | 79.51 | +0.76% | 15.000 | 16.332 | +8.87% | 5285.3 | 5469.2 | +3.48% |
| Elasticsearch | list1000 | typed | inPlace | 72.30 | 58.60 | -18.94% | 17.554 | 31.929 | +81.89% | 11822.5 | 11282.3 | -4.57% |
| Elasticsearch | list1000 | typed | none | 71.33 | 55.51 | -22.18% | 18.029 | 27.099 | +50.31% | 11733.6 | 11471.9 | -2.23% |
| Elasticsearch | paged100 | dynamic | inPlace | 1015.55 | 767.74 | -24.40% | 3.305 | 3.197 | -3.30% | 5216.7 | 5418.1 | +3.86% |
| Elasticsearch | paged100 | dynamic | none | 1024.13 | 627.10 | -38.77% | 1.288 | 1.612 | +25.12% | 5210.0 | 5401.0 | +3.67% |
| Elasticsearch | paged100 | typed | inPlace | 840.46 | 646.90 | -23.03% | 5.588 | 3.029 | -45.79% | 11739.0 | 11220.6 | -4.42% |
| Elasticsearch | paged100 | typed | none | 858.74 | 432.76 | -49.60% | 1.554 | 1.843 | +18.58% | 11653.7 | 11404.6 | -2.14% |
| Elasticsearch | single | dynamic | inPlace | 1954.05 | 1236.60 | -36.72% | 0.732 | 0.954 | +30.36% | 53631.9 | 54407.9 | +1.45% |
| Elasticsearch | single | dynamic | none | 1241.60 | 1915.98 | +54.32% | 0.702 | 0.974 | +38.64% | 53953.4 | 53631.3 | -0.60% |
| Elasticsearch | single | typed | inPlace | 1962.48 | 1035.09 | -47.26% | 0.917 | 0.843 | -8.13% | 60259.7 | 60443.6 | +0.31% |
| Elasticsearch | single | typed | none | 1799.58 | 1799.48 | -0.01% | 0.690 | 1.047 | +51.63% | 60214.4 | 59749.8 | -0.77% |
| MongoDB | list100 | dynamic | inPlace | 3734.69 | 3159.46 | -15.40% | 0.341 | 0.393 | +15.20% | 3656.6 | 5580.9 | +52.62% |
| MongoDB | list100 | dynamic | none | 3922.61 | 3240.96 | -17.38% | 0.330 | 0.377 | +14.11% | 3642.9 | 5530.0 | +51.80% |
| MongoDB | list100 | typed | inPlace | 2130.25 | 2078.63 | -2.42% | 0.569 | 0.565 | -0.68% | 10874.2 | 11993.3 | +10.29% |
| MongoDB | list100 | typed | none | 2233.48 | 2122.43 | -4.97% | 0.557 | 0.573 | +2.93% | 10783.5 | 12198.4 | +13.12% |
| MongoDB | list1000 | dynamic | inPlace | 553.37 | 444.71 | -19.64% | 2.265 | 2.487 | +9.79% | 3357.6 | 5280.1 | +57.26% |
| MongoDB | list1000 | dynamic | none | 556.54 | 437.00 | -21.48% | 2.007 | 2.621 | +30.61% | 3346.6 | 5231.9 | +56.34% |
| MongoDB | list1000 | typed | inPlace | 287.63 | 269.35 | -6.35% | 3.817 | 4.084 | +7.00% | 10571.5 | 11688.8 | +10.57% |
| MongoDB | list1000 | typed | none | 266.05 | 290.04 | +9.02% | 4.029 | 3.957 | -1.79% | 10483.4 | 11896.5 | +13.48% |
| MongoDB | paged100 | dynamic | inPlace | 2100.93 | 2026.53 | -3.54% | 0.563 | 0.571 | +1.45% | 3964.5 | 5867.3 | +48.00% |
| MongoDB | paged100 | dynamic | none | 2092.62 | 2119.24 | +1.27% | 0.535 | 0.570 | +6.70% | 3958.2 | 5843.1 | +47.62% |
| MongoDB | paged100 | typed | inPlace | 2075.42 | 1571.05 | -24.30% | 0.586 | 0.731 | +24.83% | 11188.4 | 12317.1 | +10.09% |
| MongoDB | paged100 | typed | none | 2008.07 | 1643.37 | -18.16% | 0.612 | 0.782 | +27.74% | 11095.8 | 12516.7 | +12.81% |
| MongoDB | single | dynamic | inPlace | 12031.49 | 11517.24 | -4.27% | 0.140 | 0.126 | -9.78% | 36087.4 | 38157.3 | +5.74% |
| MongoDB | single | dynamic | none | 10949.50 | 12026.80 | +9.84% | 0.115 | 0.137 | +19.65% | 35954.2 | 37664.9 | +4.76% |
| MongoDB | single | typed | inPlace | 11366.51 | 10870.28 | -4.37% | 0.128 | 0.124 | -3.01% | 43256.9 | 44658.1 | +3.24% |
| MongoDB | single | typed | none | 10792.93 | 11534.31 | +6.87% | 0.112 | 0.134 | +20.27% | 43051.2 | 44568.3 | +3.52% |

## 原始证据

原始 JSON 保留每个 fork 的 `rawData`、GC secondary metrics 和 sample percentile；text 保留 16 个 histogram 与 16 个 percentile 表。文件哈希、运行时间和最终验证命令见同目录 `gate-manifest.md`。

短 200 ms 矩阵只负责覆盖与筛选；最终判定以三轮 AB/BA/AB 配对确认和仓库既有 10% 预算为准。Elasticsearch 外部服务的 throughput/p95 仍有高顺序效应，因此只报告 `INCONCLUSIVE`，不把噪声解释成代码回归。Mongo allocation 区间极窄且稳定，属于已确认并接受的 ObjectNode 合约成本。

# ObjectNode 与 Map 纯进程性能基准（2026-08-30）

## 结论

不建议将 V9 查询网关的标准结果表示从 `ObjectNode` 替换为
`Map<String, Any?>`：

- Map 解码的每条记录分配量稳定减少约 `7.2%`；
- 从 Map 物化 typed snapshot 的每条记录分配量稳定增加约 `8.9%`；
- 两项 allocation 差异都没有超过 `10%` 性能预算；
- throughput 与 p95 对执行顺序敏感且存在明显离群，不作为架构决策依据。

该结果只比较标准 JSON 表示，不包含 MongoDB `Document` 等后端原生对象。
原生对象复用可能减少转换分配，但会泄漏 BSON 类型，不等价于存储无关的查询结果契约。

## 范围

基准将表示构造与 typed 物化拆成两个独立阶段：

| 路径 | ObjectNode | Map |
| --- | --- | --- |
| decode | JSON bytes → `ObjectNode` | JSON bytes → `LinkedHashMap` |
| materialize | 预建 ObjectNode → `treeToValue` | 预建 Map → `convertValue` |

基准不启动或连接 MongoDB、Elasticsearch，也不测量 QueryGateway、
FilterChain、schema 路由、网络或查询引擎。它只回答标准 `ObjectNode` 与
`Map` 的构造及 typed 物化成本。

数据集包含 1,000 个 `MaterializedSnapshot<QueryBenchmarkState>`，使用固定随机种子
`20260829`，state payload 长度为 128 字符。参数矩阵：

```text
operation      = single | batch100 | batch1000
stage          = decode | materialize
representation = objectNode | map
```

## 环境

| 项目 | 值 |
| --- | --- |
| 测量基线 | `bb808a47768531615d9849b27f7edd085932ceda` |
| 后续 main 变化 | 仅 compensation 文件，与查询、序列化及基准模块无关 |
| macOS | 26.5.2 |
| CPU | Apple M4 Pro，14 核 |
| 内存 | 24 GiB |
| JDK | Azul Zulu OpenJDK 17.0.7 |
| JVM | G1，`-Xms1g -Xmx1g -XX:+UseG1GC` |
| JMH | 1.37，单线程，`thrpt,sample`，`-prof gc` |
| 外部服务 | 未启动 |

## 筛选结果

筛选使用单 fork、`2 × 200ms` warmup、`5 × 200ms` measurement。
allocation 已按每次操作的记录数归一化。

| Operation | Stage | Map throughput Δ | Map allocation Δ |
| --- | --- | ---: | ---: |
| single | decode | +9.55% | -7.07% |
| single | materialize | -20.65% | +8.89% |
| batch100 | decode | +4.37% | -7.20% |
| batch100 | materialize | -24.52% | +8.88% |
| batch1000 | decode | +3.29% | -7.21% |
| batch1000 | materialize | -21.68% | +8.88% |

## batch1000 确认结果

确认采用 AB/BA/AB 三轮，单 fork，每轮 `5 × 1s` warmup 和
`5 × 1s` measurement。下表取三轮中位数：

| Stage | Representation | Throughput (ops/s) | Throughput Δ | B/record | Allocation Δ |
| --- | --- | ---: | ---: | ---: | ---: |
| decode | ObjectNode | 1405.92 | - | 3065.00 | - |
| decode | Map | 1462.20 | +4.00% | 2844.04 | -7.21% |
| materialize | ObjectNode | 1021.93 | - | 5676.04 | - |
| materialize | Map | 794.06 | -22.30% | 6180.04 | +8.88% |

三轮 allocation 方向稳定：

- decode Map：`-7.2091%`、`-7.2091%`、`-7.2091%`；
- materialize Map：`+8.8794%`、`+8.8794%`、`+8.8794%`。

吞吐存在显著顺序效应：decode Map 三轮为 `+6.20%`、`-26.80%`、
`+3.44%`，materialize Map 三轮为 `-22.30%`、`+107.54%`、
`-28.50%`。两项均不能确认吞吐差异。

p95 的逐轮变化出现 `+501%` 等离群值，因此不用于结论。

## 复现

构建 JMH jar：

```bash
./gradlew :wow-benchmarks:jmhJar
```

本轮使用明确版本的 jar，避免目录中保留旧版本产物时通配符展开成多个参数：

```bash
JMH_JAR=wow-benchmarks/build/libs/wow-benchmarks-8.16.3-jmh.jar
ARTIFACT_DIR=docs/superpowers/benchmarks/2026-08-30-object-node-vs-map-in-process-artifacts
```

筛选命令：

```bash
java -jar "$JMH_JAR" \
  '.*ObjectNodeVsMapInProcessBenchmark.execute' \
  -p operation=single,batch100,batch1000 \
  -p stage=decode,materialize \
  -p representation=objectNode,map \
  -wi 2 -w 200ms -i 5 -r 200ms -f 1 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff "$ARTIFACT_DIR/screen.json"
```

确认按 AB/BA/AB 顺序运行以下三条命令：

```bash
java -jar "$JMH_JAR" \
  '.*ObjectNodeVsMapInProcessBenchmark.execute' \
  -p operation=batch1000 -p stage=decode,materialize -p representation=objectNode,map \
  -wi 5 -w 1s -i 5 -r 1s -f 1 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff "$ARTIFACT_DIR/confirm-ab1.json"

java -jar "$JMH_JAR" \
  '.*ObjectNodeVsMapInProcessBenchmark.execute' \
  -p operation=batch1000 -p stage=materialize,decode -p representation=map,objectNode \
  -wi 5 -w 1s -i 5 -r 1s -f 1 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff "$ARTIFACT_DIR/confirm-ba.json"

java -jar "$JMH_JAR" \
  '.*ObjectNodeVsMapInProcessBenchmark.execute' \
  -p operation=batch1000 -p stage=decode,materialize -p representation=objectNode,map \
  -wi 5 -w 1s -i 5 -r 1s -f 1 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff "$ARTIFACT_DIR/confirm-ab2.json"
```

## 原始结果

- [screen.json](./2026-08-30-object-node-vs-map-in-process-artifacts/screen.json)
- [confirm-ab1.json](./2026-08-30-object-node-vs-map-in-process-artifacts/confirm-ab1.json)
- [confirm-ba.json](./2026-08-30-object-node-vs-map-in-process-artifacts/confirm-ba.json)
- [confirm-ab2.json](./2026-08-30-object-node-vs-map-in-process-artifacts/confirm-ab2.json)

默认注解配置仍与现有 QueryGateway 基准一致：`5 × 200ms` warmup、
`10 × 200ms` measurement、3 forks；本报告使用上述命令行参数覆盖默认值。

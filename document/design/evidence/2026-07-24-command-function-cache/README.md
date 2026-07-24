# Command Function Cache Benchmark Evidence

该目录保存
[`2026-07-24-command-function-cache-throughput.md`](../../2026-07-24-command-function-cache-throughput.md)
引用的 JMH machine-readable JSON 与完整控制台输出。根目录的 22 个 artifact 是
dirty-source 筛选/早期 A/B；`clean-confirmation/` 是两个 clean commit 上的正式交错
A/B，应作为生产改动判断的最高等级证据：

| Group | Files | 用途 |
|---|---:|---|
| `adaptive-function-cache-formal-t{1,16}` | 4 | 同 JAR `MAP_FIRST` / `SINGLE_ENTRY` 结构对照 |
| `adaptive-sourcing-replay-formal-t{1,16}` | 4 | 500-event mixed replay 2×2 |
| `function-cache-real-{baseline,candidate}-t{1,16}` | 8 | 精确旧/新生产实现 A/B |
| `function-cache-pipeline-focused-{baseline,eager-candidate,lazy-candidate}-t16` | 6 | 5-fork pipeline confirmation |
| [`clean-confirmation/`](./clean-confirmation/) | 48 + provenance | 6-pair clean crossover：pipeline 与 core |

每组同时保存 `.json` 和 `.txt`；JSON 用于数值复核，TXT 保留 JMH/JVM 参数、逐 iteration
输出、GC profiler 与异常状态。

clean confirmation 的顺序校正结果：

| Benchmark | Throughput effect | 95% CI | Allocation effect | 95% CI |
|---|---:|---:|---:|---:|
| `handleAggregateOnly` | `+1.64%` | `[-5.32%, +9.12%]` | `-92.00 B/op` | `[-173.53, -10.47]` |
| `processCommandAggregate` | `+0.22%` | `[-1.23%, +1.69%]` | `-85.33 B/op` | `[-111.25, -59.42]` |

因此旧 dirty A/B 的核心吞吐 `+2.98%/+4.34%` 只能保留为历史筛选信号，不能再作为
最终收益主张。clean core 支持 `-3%` non-inferiority 但不支持 superiority；clean
pipeline 仍然功效不足。两条路径的 allocation CI 均低于 0，但 pipeline pair
波动明显大于 core，因此 allocation 结论也应以 core 为更强证据。

## Environment

```text
Host: Apple M4 Pro, 14 physical cores (10P + 4E), 24 GiB
OS: macOS 26.5.2 (Build 25F84), arm64
JVM: Azul Zulu OpenJDK 17.0.7+7-LTS
JMH: 1.37
GC/heap: G1, -Xms2g -Xmx2g
```

正式 component profile：

```text
-wi 2 -w 2s -i 5 -r 2s -f 3 -prof gc
```

focused pipeline profile：

```text
-wi 3 -w 2s -i 7 -r 2s -f 5 -prof gc
```

t1/t16 是 JMH threads，不是 Scheduler pool size。

clean crossover 使用独立 baseline/candidate worktree，每个顺序位置启动一个 JVM；
完整协议、pair 派生数据、统计模型与 hash 见
[`clean-confirmation/README.md`](./clean-confirmation/README.md)。

## Reproduction commands

构建当前 candidate JMH JAR：

```bash
./gradlew :wow-benchmarks:jmhJar
JMH_JAR=wow-benchmarks/build/libs/wow-benchmarks-8.9.1-jmh.jar
```

Cache policy component（把 `THREADS` 和 `RESULT` 分别替换为 `1/16` 与目标文件）：

```bash
java -cp "$JMH_JAR" org.openjdk.jmh.Main \
  '.*AdaptiveFunctionCacheComponentBenchmark.(createEmptyCache|createAndResolveFirst|createAndResolveSecond|singleTypeHit|twoTypeAlternatingHit)$' \
  -bm thrpt -t "$THREADS" \
  -wi 2 -w 2s -i 5 -r 2s -f 3 -foe true -prof gc \
  -jvmArgs '-Xms2g -Xmx2g -XX:+UseG1GC' \
  -rf json -rff "$RESULT"
```

Sourcing replay 2×2：

```bash
java -cp "$JMH_JAR" org.openjdk.jmh.Main \
  '.*AdaptiveSourcingRegistryReplayComponentBenchmark.replayConstantSizeEvents$' \
  -p sourcingRegistryPolicy=EAGER,ADAPTIVE \
  -p functionCachePolicy=MAP_FIRST,SINGLE_ENTRY \
  -p eventPattern=MIXED_TWO_TYPES -p eventCount=500 \
  -bm thrpt -t "$THREADS" \
  -wi 2 -w 2s -i 5 -r 2s -f 3 -foe true -prof gc \
  -jvmArgs '-Xms2g -Xmx2g -XX:+UseG1GC' \
  -rf json -rff "$RESULT"
```

真实实现 A/B 必须分别给 `JMH_JAR` 指向 baseline/candidate JAR，然后运行相同命令：

```bash
java -cp "$JMH_JAR" org.openjdk.jmh.Main \
  '.*(AggregateHandleComponentBenchmark.(createCommandAggregate|processCommandAggregate)|CommandPipelineComponentBenchmark.handleAggregateOnly)$' \
  -bm thrpt -t "$THREADS" \
  -wi 2 -w 2s -i 5 -r 2s -f 3 -foe true -prof gc \
  -jvmArgs '-Xms2g -Xmx2g -XX:+UseG1GC' \
  -rf json -rff "$RESULT"
```

focused pipeline：

```bash
java -cp "$JMH_JAR" org.openjdk.jmh.Main \
  '.*CommandPipelineComponentBenchmark.handleAggregateOnly$' \
  -bm thrpt -t 16 \
  -wi 3 -w 2s -i 7 -r 2s -f 5 -foe true -prof gc \
  -jvmArgs '-Xms2g -Xmx2g -XX:+UseG1GC' \
  -rf json -rff "$RESULT"
```

## Binary provenance

clean confirmation：

| Artifact | SHA-256 |
|---|---|
| Baseline commit | `04b4310f69d9b578d26144782bfa08abec58c5e5` |
| Candidate commit | `dfa607d0ea6c2d7a663dbd49f01608e6ba7607d2` |
| Clean baseline JMH JAR | `4659b9560901f89857438eabeb28fa41d70e4dcb0daf863a9301183e7607c573` |
| Clean candidate JMH JAR | `94675ccc7f907468087fcf8e39696919a646351504cef8c500fe15236f1f546d` |

下表是 dirty-source 筛选阶段的历史 provenance：

| Artifact | SHA-256 |
|---|---|
| Exact map-first baseline JMH JAR | `956b9c81fa98d953e9d4c3b04d2f9deb9bdb4e26a90cd11c4b308619be2e4c25` |
| Measured eager-cache/single-entry candidate JMH JAR | `02de257d024d8a5c72f6d600750e34d7a3c855f3811d93c0dae5a643cc7a0e70` |
| Final rebuilt JMH JAR | `db3fba8d5f852d189eea22ea23d70140dad1c98d9e75ab63ecf63996154108ec` |

最终 JAR 与 measured candidate 的 whole-JAR hash 不同，因为 benchmark fixture/generated
内容在收尾时重新构建；实际生产相关 class hash 完全相同：

| Class | Final = measured candidate SHA-256 |
|---|---|
| `CommandFunctionResolver.class` | `ed9c326b8f1e37996b62d8416314b39231780f03c224e2815eb9b719005cb341` |
| `FunctionCache.class` | `688fd154a6df444ecdbcc9c18f5a8458c5ce99483b549f1af8a7066d97b2b5f2` |
| `FunctionCacheEntry.class` | `8112f8ea7a43f0e89718e05b66f49d0c6d87d8b4e4dea124c4f4e0c329b34ed8` |

Baseline `javap` 复核只有 nullable Map field；candidate/final 均为
`singleEntry + cache`，且 `CommandFunctionResolver` 继续持有两个 eager final cache。

## Integrity and limitations

README 以外 22 个原始 artifact 的路径相关聚合校验：

```bash
find document/design/evidence/2026-07-24-command-function-cache \
  -maxdepth 1 -type f ! -name README.md -print0 |
  sort -z |
  xargs -0 shasum -a 256 |
  shasum -a 256
```

结果：

```text
2a671af1b6dba0ac106fbed8aa088687fdd7e0376dd35f7e526bf11e41dc7848
```

clean confirmation 的 48 个 JSON/TXT 与 `provenance.txt` 聚合校验为：

```text
45febe9baa407288df07c0c9deb65f32f2943398c55ed5db27e3556cda0138d3
```

限制：

- 根目录筛选证据来自 dirty worktree，不能提升为 clean-release confirmation；
- `clean-confirmation/` 的两侧 commit/worktree 均 clean，whole JAR hash 与 production
  class hash 均已记录；
- component/pipeline 使用本地 fixture，不覆盖 Mongo、Kafka、网络与真实序列化；
- `.txt` 的控制台环境信息与 `.json` 参数、fork/iteration 数已交叉核对；
- 所有完成的 JMH fork 均无 benchmark failure；结论仍按 confidence interval 与预设
  non-inferiority gate 解读，不用单一 point estimate 外推。

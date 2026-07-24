# Clean FunctionCache A/B Confirmation

该目录保存 `FunctionCache` map-first baseline 与 single-entry candidate 在两个 clean commit
上的交错 A/B 结果。每个顺序位置都启动独立 JVM；JSON 是 JMH machine-readable
结果，TXT 保留完整参数、逐 iteration 输出与 GC profiler 数据。

## Provenance

| Variant | Commit | JMH JAR SHA-256 | `FunctionCache.class` SHA-256 |
|---|---|---|---|
| baseline | `04b4310f69d9b578d26144782bfa08abec58c5e5` | `4659b9560901f89857438eabeb28fa41d70e4dcb0daf863a9301183e7607c573` | `b4f091713567e285fe7d529b33ddb005a729e5c37697fc825a93cdeccee2ce12` |
| candidate | `dfa607d0ea6c2d7a663dbd49f01608e6ba7607d2` | `94675ccc7f907468087fcf8e39696919a646351504cef8c500fe15236f1f546d` | `688fd154a6df444ecdbcc9c18f5a8458c5ce99483b549f1af8a7066d97b2b5f2` |

两个 worktree 在构建与运行前均满足 `git status --porcelain` 为空。
`CommandFunctionResolver.class` 在两侧均为
`ed9c326b8f1e37996b62d8416314b39231780f03c224e2815eb9b719005cb341`；
candidate 的 `FunctionCacheEntry.class` 为
`8112f8ea7a43f0e89718e05b66f49d0c6d87d8b4e4dea124c4f4e0c329b34ed8`。
完整环境与原始 JAR 路径见 [`provenance.txt`](./provenance.txt)。

## Protocol

固定环境：

```text
Host: Apple M4 Pro, 14 physical cores (10P + 4E), 24 GiB
JVM: Azul Zulu OpenJDK 17.0.7+7-LTS
JMH: 1.37
GC/heap: G1, -Xms2g -Xmx2g
Threads: 16
```

每个 benchmark 运行 6 个 pair，顺序为 `BC, CB, BC, CB, BC, CB`：

- `B`：baseline；
- `C`：candidate；
- pipeline：`CommandPipelineComponentBenchmark.handleAggregateOnly`，
  `3 × 2s` warmup、`7 × 2s` measurement；
- core：`AggregateHandleComponentBenchmark.processCommandAggregate`，
  `2 × 2s` warmup、`5 × 2s` measurement；
- 每个顺序位置 `-f 1 -prof gc`，因此一组包含 12 个隔离 JVM。

顺序是预先确定的交替而非随机化；pipeline 的第 6 pair 在最初 5 pair 完成后追加，
不是一个完全连续的 6-pair block。每个 period 也只有一个 fork，因此下述模型只能
校正一阶顺序效应，不能消除非线性主机漂移或后补批次效应。

命令模板：

```bash
java -cp "$JMH_JAR" org.openjdk.jmh.Main "$BENCHMARK" \
  -bm thrpt -t 16 \
  -wi "$WARMUP_ITERATIONS" -w 2s \
  -i "$MEASUREMENT_ITERATIONS" -r 2s \
  -f 1 -foe true -prof gc \
  -jvmArgs '-Xms2g -Xmx2g -XX:+UseG1GC' \
  -rf json -rff "$RESULT"
```

## Analysis

以 pair 为统计单位。吞吐先计算 `d = ln(candidate / baseline)`，allocation 计算
`d = candidate - baseline`。对于交错顺序：

```text
mean(d_BC) = treatment + period
mean(d_CB) = treatment - period
treatment = (mean(d_BC) + mean(d_CB)) / 2
```

95% CI 使用两个 sequence 内的 pooled variance，`df=4`，
`t(0.975, 4)=2.776445`。该模型校正一阶顺序效应，但只有 3 个 pair/sequence，
对 pipeline 的高方差仍然功效不足；不能把包含 0 的区间写成已证明提升。

| Benchmark | Arithmetic baseline | Arithmetic candidate | Corrected throughput effect | 95% CI | Corrected allocation effect | 95% CI |
|---|---:|---:|---:|---:|---:|---:|
| `handleAggregateOnly` | `872,614.64 ops/s` | `887,447.51 ops/s` | `+1.64%` | `[-5.32%, +9.12%]` | `-92.00 B/op` | `[-173.53, -10.47]` |
| `processCommandAggregate` | `970,525.90 ops/s` | `972,308.55 ops/s` | `+0.22%` | `[-1.23%, +1.69%]` | `-85.33 B/op` | `[-111.25, -59.42]` |

顺序效应估计分别为：

- pipeline：throughput `-0.33%`，allocation `+57.33 B/op`；
- core：throughput `+2.75%`，allocation `-5.33 B/op`。

准确结论：

- clean core confirmation 支持 `-3%` non-inferiority，但不支持 throughput
  superiority；
- clean pipeline confirmation 既不支持 superiority，也不能排除超过 `3%` 的回退；
- 两条路径的 allocation CI 均低于 0；pipeline pair 波动更大，core 证据更强；
- 生产改动只能定位为 allocation 优化；是否转化为容量收益需在 GC-bound、
  production-like workload 中验证 GC CPU、pause 与 throughput。

逐 pair 的派生值见 [`summary.csv`](./summary.csv)。原始 48 个 JSON/TXT 与
`provenance.txt` 的路径相关聚合 SHA-256（不含本 README 与派生 CSV）为：

```bash
cd document/design/evidence/2026-07-24-command-function-cache/clean-confirmation
find . -maxdepth 1 -type f ! -name README.md ! -name summary.csv -print0 |
  sort -z |
  xargs -0 shasum -a 256 |
  shasum -a 256
```

```text
45febe9baa407288df07c0c9deb65f32f2943398c55ed5db27e3556cda0138d3
```

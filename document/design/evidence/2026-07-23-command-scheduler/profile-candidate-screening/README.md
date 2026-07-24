# Profile-driven Candidate Screening

该目录保存 2026-07-24 对 `sendAndWaitForProcessed` 热路径进行 allocation profile 与
短时候选筛选时产生的小型 JMH 原始结果。所有数据均来自 commit
`6c6e4ebbe891fed0752f635391b1b034c988dd66` 上的 dirty source；临时候选代码已回退，
因此本目录属于 `EXPLORATORY_DIRTY`，不能作为可从当前 source 重建的正式 confirmation。

## Profiler validity

- JMH 1.37 async-profiler wrapper 以 287 字符的 benchmark parameter ID 创建 trial
  目录，超过 macOS 单文件名 255 字符限制；wrapper run 因 `File name too long` 失败。
- JMH JFR wrapper 对同一长目录没有留下 `profile.jfr`。不能把“JMH 命令成功退出”当成
  已采到 profile。
- 改为直接在 fork JVM 注入短绝对路径后，async-profiler 与 JDK JFR 才留下原始文件。
  原始 JFR 因体积未入库：
  - async-profiler JFR SHA-256：
    `6a3a514bb09399a16aa4dc192cbf2186b4dca1c954fd3c57677713a0e9efce19`
  - JDK JFR SHA-256：
    `a9bb1140867b556851f1c47601fbb1f779a9444b56310dca6fd14f6f08d90771`
- macOS async-profiler `event=cpu` 的全局样本主要落在其他阻塞线程的
  `__psynch_cvwait`，不能用于量化命令热路径 CPU 比例。
- Zulu JDK 17.0.7 的 14 秒 JFR 只有 17 个 `jdk.ExecutionSample` 和 8 个
  `jdk.NativeMethodSample`，CPU 样本不足；但有 3,990 个
  `jdk.ObjectAllocationSample`，可用于 caller family 排序。JFR allocation weight
  是采样权重，不是精确字节，也同时覆盖 setup、warmup、measurement 与 teardown。
- 精确 per-operation allocation 只采用 JMH GC profiler。对应 t1 run 为
  `97,773.934 ops/s`、`3553.083 B/op`；参数与 JVM args 见
  [`jdk-jfr-human.txt`](./jdk-jfr-human.txt)。

## Same-JAR target/locality negative control

正式 2×2 confirmation 已单独固化在上级目录：

- [`209dff22-wait-locality-target-negative.json`](../209dff22-wait-locality-target-negative.json)
- [`209dff22-wait-locality-target-negative.manifest.json`](../209dff22-wait-locality-target-negative.manifest.json)
- [`209dff22-wait-locality-target-negative-human.txt`](../209dff22-wait-locality-target-negative-human.txt)

共享 immutable `StageWaitTarget(PROCESSED)` 的 allocation 主效应为
`-24.309 ±2.098 B/op`；locality upper bound 的 allocation 主效应只有
`-0.105 B/op`。throughput 的两个主效应和交互区间均不可辨识，且条件效应发生符号翻转。
因此共享 target 只证明小幅 allocation 清理，不能声称吞吐提升；绕过 locality guard
还会破坏 remote ID 语义，两项生产候选都已回退。

## Flat notification extraction

同一 JMH JAR
`6cb553d1001b7be154ec6b5933c74f1b7319f119556b75efc877a17f0f19540e`
内，benchmark-only filter 比较当前完整 `ExtractedWaitPlan` 与扁平
`endpoint + waitCommandId + target`：

| 拓扑 | Materialized plan | Flat fields | Allocation delta |
|---|---:|---:|---:|
| t1 / CPU pool | 98,026.72 ops/s; 3570.40 B/op | 100,322.52 ops/s; 3569.48 B/op | -0.92 B/op |
| t16 / pool4 | 347,239.89 ops/s; 3533.14 B/op | 345,931.10 ops/s; 3533.16 B/op | +0.02 B/op |

源码层少构造两个 wrapper 并未转化为规范化 allocation 收益，说明 JIT
scalar replacement/对象布局已抵消该差异。候选没有进入生产实现。

## Header backing exploratory screen

Header screen 使用同一 benchmark 参数、t16、pool4、1 fork、`2 × 1s` warmup、
`3 × 2s` measurement 与 GC profiler，但每种 backing 是独立 dirty JAR，且运行未随机
交错。JMH error 很宽，以下数值只能筛查 allocation 上界，不能构成吞吐确认：

| Backing | 两轮 throughput | 两轮 `gc.alloc.rate.norm` | 结论 |
|---|---:|---:|---|
| Current `LinkedHashMap` | 336,792.74 / 344,387.04 | 3533.79 / 3533.33 B/op | 独立 JAR baseline |
| `HashMap` | 380,818.56 / 348,888.58 | 3493.07 / 3492.64 B/op | 稳定少约 40.7 B/op；改变可观察迭代顺序，吞吐未正式确认 |
| Insertion-ordered compact array, eager copy | 343,730.33 / 339,505.61 | 3317.23 / 3317.44 B/op | 稳定少约 216.2 B/op；吞吐均值与 baseline 近似 |
| Compact array + COW | 348,040.09 / 346,788.01 | 3325.56 / 3325.40 B/op | allocation 反而比 eager 多约 8.2 B/op；增加共享/并发复杂度 |

对应 JAR SHA-256：

- first linked baseline：`0136f8cd31d58053040d1205186923b9b2e682d9339887f865066551fc062e0c`
- restored linked baseline：`dfb6ff95e02b14dcc5fc2522ea6051c674f0ac5caea5cd95f4de70d6af7ff2a6`
- HashMap：`638ae28b52caea4421e48829f79a6a23ef1247ab914a9a42a5d1edc4278f599f`
- eager compact：`5b1d39c8c049e0a418e3d6f50884fa0195a62328ce909dfbde7d220ac896ecfd`
- COW compact：`e9f25d29696979444fe22e3106d69f32cefe4a8abf2f43ad14cb405840372e6b`

恢复相同 source 后两个 linked JAR 的 hash 不同，说明当前 fat JAR packaging 不是字节级
可复现构建；这也是不能把跨 JAR 点估计提升为因果结论的额外理由。HashMap 会影响默认
keys/entries、Jackson Map 序列化、日志与 trace key iteration；compact/COW 则需要承担
完整 `MutableMap` view、iterator、copy 隔离和并发语义。由于保持顺序的 eager compact
没有吞吐信号、COW 也未改善 allocation，三项生产改动均已回退。

## Raw result integrity

正式 2×2 run 的 result SHA-256 为
`4daf2c9c25632517952b84ba3d1e0fc28a871a0805b96244a64ed4dc2297e208`，
manifest SHA-256 为
`139183c4f87d25a6b53fb4f42e5175d3174f42ab092537a703b6b3fb92b79e2f`。
本目录其余 JSON 的逐文件 SHA 可通过以下命令重新计算：

```bash
find document/design/evidence/2026-07-23-command-scheduler/profile-candidate-screening \
  -maxdepth 1 -type f -name '*.json' -print0 |
  sort -z |
  xargs -0 shasum -a 256
```

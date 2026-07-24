# Open-loop observer sensitivity

本目录固化 bounded-open-loop 观测器敏感性实验的派生数据。它用于审计 benchmark
自身的扰动，不是生产容量基线，也不改变 Scheduler 默认配置。

## 结论

- 340K/s 与 360K/s 的 `FULL`、`NO_LATENCY` 都被 offered rate 封顶；只能说这些点
  没有观察到容量分类变化，不能据此声称观测成本为零。
- 380K/s 是两种模式都 shedding 的过载点。三个完整配对中，
  `NO_LATENCY / FULL` processed-rate ratio 中位数为 `1.098629`，范围
  `1.085052–1.105322`；shed ratio 中位减少 `8.087` 个百分点。但三轮绝对吞吐随
  时间明显下降，主机也观察到瞬时后台 CPU 竞争，因此这是约 `8.5%–10.5%` 的
  **诊断性扰动信号**，不是 recorder 的因果效应或生产吞吐收益。
- `OpenLoopObserverComponentBenchmark` 直接隔离相同 `ConcurrentLatencyRecorder`
  的稳态写路径。t1/t4/t16 下，完整五路记录分别为 `8.07/8.96/15.05 ns/op`，
  `NO_LATENCY` 的四次 disabled 调用为 `0.52/0.53/0.98 ns/op`，规范化分配近似
  `0 B/op`。以 380K request/s 乘 t16 差值只约 `5.34 ms aggregate
  operation-time/s`；该 benchmark 未启用 CPU profiler，不能把这个算术量称为
  CPU time。这个数量级不支持把端到端 9.9% 直接归因给 recorder 稳态写入。
- 因而当前最强结论是：过载端到端差值混入了热状态、主机负载、顺序与剩余 hook
  交互，不能归因给 `SampleBuffer` 稳态写入。替换为 HdrHistogram 也没有证据：
  其共享 atomic 写和 percentile 语义不同，必须另做 backend A/B 后才能考虑。

## Provenance

端到端选定批次均为：

- source commit：`6c6e4ebbe891fed0752f635391b1b034c988dd66`
- `source.dirty=true`
- runner JAR SHA-256：
  `d895b79d5a198194c3f3751a289289c4c5d0254519c77a5acf1c12306281a903`
- pool：`4`
- resolved stripes：`896`
- producers：`16`
- `maxInFlight=65536`
- request deadline：`5s`

340K 使用 `5s warmup + 10s measurement`、5ms generator-lag gate、2 次；360K/380K
使用 `10s + 20s`、放宽后的 20ms gate、3 次。三档不能合并成同一 formal matrix。
340K 的实际执行顺序按 mode 分组，不能视为交错配对；360K/380K 为
`FULL → NO_LATENCY → NO_LATENCY → FULL → FULL → NO_LATENCY`。

组件基准由 `benchmarkOpenLoopObserverComponent` 生成，三种线程数共享 run ID
`a18509e8-45a1-4574-a377-824a885c9c82` 和 dirty JMH JAR SHA-256
`083b04386c3820f15770d20c56978ff986e3968c516d45a541ea516c56c0dfef`。
协议为 JMH 1.37、`avgt`、GC profiler、3 forks、`3x250ms` warmup、
`10x250ms` measurement。每线程输入在 invocation 间变化，recorder 每 iteration
重建，teardown 会读取 summary 并检查计数非负，避免早期固定 bucket/长 iteration
版本发生常量折叠或 `int` bucket 计数合并溢出。早期 direct-JMH 数值因此全部作废，
没有纳入 CSV。

修正版仍明确排除 clock、request state、deadline、terminal hook、summary integration
与命令运行时，所以只能解释 recorder 写入组件。其 manifest 虽能自证 runner JAR 与
artifact hash，但 source 仍为 dirty，故仍是诊断性 component evidence，不是
clean-source baseline。

本目录的两个 CSV 是从本机 ignored raw JSON 派生的最小可审计数据：

- [`observer-sensitivity.csv`](./observer-sensitivity.csv)
- [`observer-component.csv`](./observer-component.csv)

组件 artifact SHA-256：

| Threads | JSON SHA-256 | Human SHA-256 | Manifest SHA-256 |
|---:|---|---|---|
| 1 | `719ab269f7bbcc129df81ef3272850a90f859e3a83bd050a8a80d8dc3928fdef` | `2e1c7806a9877b59b470401c0187dc72c3cf613b3a9331acc767968993630605` | `329d8ea0fd5b4e16012fd55a317df44e97cb7b2f3891d6de228ac09197d0cb6b` |
| 4 | `fbd0556886776e5bff4b459104e93545df363b5b17aa8640a53647ed5fafa6bc` | `9198611b1caa6e2a70370b20995224eeaa402e68c7ef6984dd9b97f0ad232ddb` | `1df80a04b0ca91828d19a9d27c43fd1bf4997b0d3f126a67bdf8bcb42bd90ae5` |
| 16 | `a53b166ac15469af094bf764f66d6187bc64543eddeacc0ffa5d9c4c0bdadf4a` | `d6f42b1a2fddca7538a7a8b38b4257657f5a0158deda3a4198bdd6a314adddde` | `e511b862babacac45aabf465bee600449b06cfb689ee7ac3de07247ffc1a3d85` |

## 纳入与排除

只纳入同一 run ID 下同时有 finalized `SUCCESS` manifest 的 `FULL` 与
`NO_LATENCY`。`fda981a7-02f0-4eb1-8ce9-69025ed32422` 的 repeat 4 被整体排除：
其中 `FULL` 的 `generatorMissed=7887/7600000=0.103776%`，超过 0.1% 门槛，只留下
`manifest.in-progress.json`。不能保留该 block 中较轻模式的 SUCCESS，否则会产生
survivor bias。

`NO_LATENCY` 的 SUCCESS 只表示 mode-local invariants 通过；它没有 generator-lag
percentile/gate。正式资格只能由 finalized `formal` manifest 表达，并同时要求 clean
source、FULL observation、runner/orchestrator identity 与 artifact hashes 全部匹配。

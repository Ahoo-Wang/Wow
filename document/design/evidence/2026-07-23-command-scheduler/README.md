# Command Scheduler Benchmark Evidence

该目录保存
[`2026-07-23-command-runtime-scheduler-throughput.md`](../../2026-07-23-command-runtime-scheduler-throughput.md)
引用的 JMH 原始结果、manifest，以及 24 轮 bounded-open-loop 矩阵。JMH 文件按
run ID 固化，避免
`wow-benchmarks/results/jmh/` 各 profile 的固定输出名被后续运行覆盖。

| Run | Run ID | Result SHA-256 | Manifest SHA-256 |
|---|---|---|---|
| Scheduler pool sweep | `00c48718-75c8-467b-a63f-ab7674fe97bd` | `8c91f463ffba3977164996e45c0a0efc7c4735f287cbbca78fae7b5486d7b9dd` | `fda9bf24f3099c820104c284afbcc749bdd75159342aff8131886edb7387130c` |
| Per-message atomic proxy | `1bc4bf16-2e03-4988-b8f8-6b02ea7f0aac` | `909a632a0022dd6dc781074d4b324828731e454b1e1d0a339a5ab4ca008126e7` | `436b2e1d0c83a5a5273b021226d2d4c1a026977c97a68113d8cf15464d07325e` |
| Synthetic CPU boundary | `1fa976db-2550-4b2a-af72-0d26a50c9b77` | `c3d276e93b383df4827914753f0832ddf9f9ba4601533691fd452be9acf3d580` | `01ea08741e79e32d679fe9290c400d467fd7014843a7d2138f9baf99af651dc5` |
| Stripe × pool matrix | `b6cf7ee9-912a-4b5b-8ef2-5c912d2bbfb7` | `5067f625f3478197d27f48dad4dbd48dd0bb9b18c6c39e393716b662b0084132` | `f1c99992e6fe57e8054ce85988f331ecab36a0b4d488258cb0da87b9b749a46d` |
| Scheduler HOL pre-review replication | `ef55684b-1ae4-4f51-a651-4224aeed19fe` | `18a911456f1e5ea9ebfeeddff0a9353f54514090ac5ca3825b0ccc79fa948216` | `f1805c7e523b6a1ccaff84e21f031e6808b41d7c6c01128001e853425a66da09` |
| Scheduler HOL final full matrix | `3190552b-4628-4ce7-aae0-dae782e0938b` | `a55c3a06187de1f6d824d2126c05d30fb7e0dac87dd39ebc2175f2d0da8a08ea` | `38400a1062434f3cc3727cb28c5860ac0048c99640b869e61978839ac0e00b11` |
| Scheduler HOL final isolated subset | `c5e1316b-7397-4b53-beed-675afdbc6da9` | `f952db2b445cbf0cd09541cc6a0f9a969b2793223b9aaa55bd2cb18f5e828ecc` | `b027dbc9aa844d37af3541fb6f6e6f202a6f43e372a6704aae54cd9ded3b25a8` |
| Processed last-wait exact baseline | `8c92c301-0c66-4fb0-8483-f9bf9fac65b3` | `6ca650f6b862200962288d75f38e67a4fb33789c1717accf64b8c0439218d78a` | `e475d40fb0d1d2eb8064d69bc3a42b6359da66e479aab9249da865f53551494d` |
| Processed last-wait final / same-JAR proxy | `03a7b2ad-41bc-481c-b944-36679855ce43` | `b20d6bf39a93e34e4a48cf980efa758a58e0278e0e811df938977f71973ebc2a` | `31467ef787af9590691cf808493016588ad7565a8bdf9e90e0396249d0bf62ca` |
| Retry base-spec reuse negative control | `c60d2ee1-42c5-451e-8dc8-1ddacbafb169` | `2c29a706e74e8bcdb8b3009d93edd038e9302b6bd7a36d50b90040237c34be56` | `a70e0f36ee1286fc543a9284ca530399ac3c05a04683c68a12824195677cf36e` |
| Wait locality × target negative control | `209dff22-821d-42d6-91a8-ec10fbe23a8e` | `4daf2c9c25632517952b84ba3d1e0fc28a871a0805b96244a64ed4dc2297e208` | `139183c4f87d25a6b53fb4f42e5175d3174f42ab092537a703b6b3fb92b79e2f` |
| Multi-aggregate fixed 2-worker isolation | `0aac8db2-18c3-4c10-a078-89818b0144e3` | `9c5159e65ad938e335ff503f9bede747dedbbcb9fde8a8e7ec2b099d988e4bb1` | `b201b9e94e6518ae9a1362cb31c79a942a94017d747e3e5afdf56bac53459e69` |
| Multi-aggregate configured pool P=4 | `8f1bcbd7-b6a2-4fb3-b21c-5f389a7b385b` | `7cf0a1e8f073057862558f2ee1ae6b6547372fbeb4120074f735553a921dc6a1` | `33bb44d312881f8a725cb8b46cf0a865ed6388c9e0fd20889b80f4aa2fc31417` |
| Multi-aggregate fixed role budget=14 | `5abe67bf-794f-4a37-9c3f-7e938bd99aa5` | `ff48e9b7b674f578b4fb9ee28122a3a4d77b83d494ea98fb85173cebfb5e8546` | `d98108ecadb6d0967c3c0a9a9efc1d47bc61ce6c4b7b35d78f28f206795853d3` |

### Portable artifact mapping

根目录 14 份 JMH manifest 保留了运行时原始输出 basename 和 `resolvedJmhArgs` 的绝对
路径；归档时 result 被重命名为 `<manifest-prefix>.json`，因此不能把
`artifacts.result.path` 直接当成相对当前目录的路径。可携带映射规则是：

```text
<prefix>.manifest.json -> <prefix>.json
<prefix>.manifest.json -> <prefix>-human.txt  # 仅存在时
```

14/14 result 的当前文件 SHA-256 已与 manifest 中
`artifacts.result.sha256` 核对一致。仅 5 份 human 输出被归档；以下 9 份只保留
manifest 中的原始 human hash、大小与运行参数，不能声称控制台全文仍在目录中：

```text
00c48718-scheduler-pool
03a7b2ad-last-wait-fast-path
1bc4bf16-task-counter-proxy
1fa976db-synthetic-cpu
3190552b-scheduler-hol-final
8c92c301-processed-wait-baseline
b6cf7ee9-stripe-pool-matrix
c5e1316b-scheduler-hol-isolation
ef55684b-scheduler-hol
```

manifest 中的绝对路径只用于记录原始 invocation，不是复现前提；复现应使用报告和
`wow-benchmarks/README.md` 中的 Gradle 命令，并重新生成 clean-source provenance。

这些 run 的 manifest 均记录 `source.dirty=true`。目录未保存 JMH JAR 或每次 dirty patch，
因此可持续核对原始数值与运行参数，但不能把它们提升为 clean-source confirmation。
HOL run 使用固定 t4 JMH group、`thrpt,sample`、3 forks；其 fast-lane 指标位于
`secondaryMetrics.observedFastAggregates`，不能用混合快慢请求的 primary metric 替代。
最终 full matrix 与 isolated subset 使用相同 JMH JAR SHA-256
`437e0944a4c5a1be8b02b51c7ee18c0b83f53a3074bb5b76c0fbbbe08badda8d`；
pre-review replication 的 JAR 不同，只能作为方向复现，不能视作相同二进制重复实验。
processed last-wait baseline 使用旧实现 JAR
`437e0944a4c5a1be8b02b51c7ee18c0b83f53a3074bb5b76c0fbbbe08badda8d`；
final/proxy run 使用新 JAR
`69b2d751f9c4556c9daba70c68b7d8cfbe38117ef7d0d184a85c7e57828efee9`。
前后比较是精确代码路径的独立运行，只能作方向交叉验证；final/proxy 两行才是同 JAR
局部对照，而 proxy 额外包含一个内层 Reactor `doOnSuccess` operator 与一次
coordinator lookup。

`c60d2ee1` 在同一个 JMH JAR 中比较每 processor 重建
`Retry.backoff(...).filter(...)` 与共享 immutable base spec。共享路径为
`346,164.37 ±5,208.54 ops/s`，legacy 为
`351,511.71 ±7,436.54 ops/s`；`gc.alloc.rate.norm` 分别为
`3536.784 ±24.582 B/op` 与 `3536.726 ±23.436 B/op`。吞吐区间重叠且 allocation
不可区分，因此候选生产改动已回退；该 run 是负向筛选证据，不是待合入优化。

`209dff22` 在同一个 JMH JAR 中做 locality × processed target 2×2 confirmation。
共享 target 稳定减少 `24.309 ±2.098 B/op`，但 throughput 主效应只有 `+0.514%`，
条件效应发生符号翻转且四个 cell 的区间有共同交集。绕过 locality 只减少约
`0.105 B/op`，同时不满足 remote ID 语义。两项候选均已回退。

`8f1bcbd7` 使用修正后的 JMH JAR
`b510bd2e25d474bf5a5c0b6dd31ea26ec44342424e6805f09fb76f3a3cc17b02`，
固定 t16、每池 P=4、3 forks、`2×2s` warmup、`4×3s` measurement 和 GC profiler。
A=1 的 dedicated/shared 负控制为 `230,632.97`/`231,329.89 ops/s`（`+0.30%`）；
A=4 时 shared 以 4 worker 对 dedicated 的 16 worker，吞吐高 `75.88%`；A=16
以 4 对 64 worker，吞吐高 `112.05%`。该 run 复现当前配置语义与资源放大，不是
固定总资源的 topology 因果比较。

`5abe67bf` 与 `8f1bcbd7` 使用相同 JMH JAR
`b510bd2e25d474bf5a5c0b6dd31ea26ec44342424e6805f09fb76f3a3cc17b02`，
固定 role 总预算=14、t16、3 forks、GC profiler。A=1/2/7/14 时 dedicated 每池
分别为 14/7/2/1 worker，shared 始终为 pool14；每格实际 worker 都校验为 14。
shared throughput delta 为 `+2.87%/+1.08%/+0.17%/+0.48%`，四组 confidence
interval 全部重叠，per-fork mean CV 最大 `3.54%`。规范化 allocation delta 为
`-2.73%/-0.46%/+0.96%/-0.03%`，也没有一致方向。该固定资源控制表明配置等值
矩阵的大幅差异主要来自 `A×P` worker 预算，而非 ownership 本身。

`0aac8db2` 固定两类型总 worker=2，使用 t8 group、3 forks、`thrpt,sample`，比较
dedicated `1+1` 与 shared pool2。uniform 下 shared 的 Type B throughput 在
balanced/skewed 分别回退 `7.84%`/`12.51%`；Type A 加 100K CPU tokens 后回退
`96.48%`/`86.52%`，Type B p99 升高 `2142.90%`/`460.51%`。throughput 的四组
confidence interval 均不重叠，per-fork mean CV 最大 `4.80%`；p99 是合并 histogram
点估计，没有独立 CI。

隔离 run 使用 JMH JAR
`c8c084a6b3b58a03992ea03c3027563062f4000d32be5a163f1e41773b48de4d`，
其 2-worker setup 没有异常。后续扩大到 16 worker 时发现 benchmark worker-count
探针在 release 后立即 dispose 可能打断探针任务；该轮当场终止且未保存。探针随后
改为等待记录任务完成后再 dispose，并通过 16 类型/64-worker smoke。修复只改变 trial
setup，不进入 measurement path，但 `0aac8db2` 仍按 `DIRECTIONAL_DIRTY` 使用并等待
同一修正 JAR 复验。

[`profile-candidate-screening/`](./profile-candidate-screening/) 另存 direct-fork
JFR 的小型 human/result 文件、flat notification 同 JAR pilot 与 Header backing
跨 JAR exploratory screen。该目录明确标记为 `EXPLORATORY_DIRTY`；它用于解释为何
若干看似减少对象的候选没有进入生产，不属于正式吞吐 confirmation。

[`open-loop-observer-sensitivity/`](./open-loop-observer-sensitivity/) 固化后续
observer-ablation 的派生 CSV、组件级 JMH 数据与 provenance。340K/360K 的
`FULL`/`NO_LATENCY` 都被 offered rate 封顶；380K 过载点出现 8.5%–10.5% 的端到端
扰动信号，但相同 recorder 的稳态写入微基准只有 8.07–15.05ns/request。该数量级、
主机负载与热漂移共同表明“9.9% 直接归因于 SampleBuffer”缺乏支持。目录明确按完整
pair/block 纳入，排除了 FULL 失效而轻量 mode 成功的 survivor-bias 样本。组件数据
来自动态输入、iteration 重置、3 forks 的专用任务，并有 finalized manifest；由于
source dirty 且未覆盖完整 observer/runtime，仍只作诊断性组件证据。

## Bounded-open-loop matrix

[`open-loop-formal-matrix/`](./open-loop-formal-matrix/) 保存以下固定矩阵的 24 份 JSON、
24 份 SUCCESS manifest 和 24 份 human report：

- offered rate：`340k, 360k, 380k, 400k commands/s`；
- Scheduler pool：`4, cpu(14)`；
- repetition：每点 3 次；
- 固定条件：`stripeCount=896`、high cardinality、16 producers、
  `maxInFlight=65536`、10s warmup、20s measurement、5s request deadline。

在 repository root 执行以下命令；二次 hash 的输入同时包含逐文件 hash 和
repo-relative path，因此移动目录或更换 cwd 会改变结果：

```bash
find document/design/evidence/2026-07-23-command-scheduler/open-loop-formal-matrix \
  -type f -print0 |
  sort -z |
  xargs -0 shasum -a 256 |
  shasum -a 256
```

72 个文件的聚合校验为
`62c5f1616fb7411865ccebaa565f17bfbf293db1bb058e887508ee20cb79f285`。
24/24 result/manifest 的 run ID、参数与 artifact hash 已独立核对；runner JAR
SHA-256 均为
`214913070a82bf2a2a24b4a213a49805085edb0c9596b6da224ee7ad20c161c6`。

这批文件名沿用当时的 `formal` profile，但所有 manifest 都是
`source.dirty=true`，pool4 与 CPU 两组也不是交错运行；因此报告将它们降级为
`INSTRUMENTED_DIRTY_OPEN_LOOP`，只用于离散 offered-rate 过渡和机制判断。后续代码已让
formal open-loop task 拒绝 dirty source，并加入 generator expiry、missed ratio 与
p99 lag 有效性门槛。

这些历史 result/manifest 使用门槛加入前的旧 schema：没有顶层 `validity`、
`maxGeneratorMissedRatio` 或 `maxGeneratorLagP99Millis`。其中 pool4 360 repeat2/3 与
CPU 380/400 按当前 5ms lag 门槛回看会被拒绝。因此历史 manifest 的 `SUCCESS` 只表示
当时的 accounting invariant 通过，**不表示通过当前 formal validity protocol**。

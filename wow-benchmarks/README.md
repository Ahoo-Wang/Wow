# Wow Benchmarks

`wow-benchmarks` 是 Wow 仓库的 JMH 入口。它用于验证基准路径、收集有边界的本地回归线索，以及建立可重现的精确工作负载基线。它不是生产容量模型。

## 四层证据

| 层级 | 入口 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| Smoke | `benchmarkSmoke` | 选定的 component、Framework E2E、Batch E2E 和 WebFlux JMH 路径能编译、启动并完成 | 吞吐变化或性能报告 |
| Directional Quick | `benchmarkQuick*` 与对应 report task | 当前机器、当前配置下的方向性回归线索 | 稳定的跨环境变化或正式性能结论 |
| Reproducible Baseline / Confirmation | `benchmarkBaselineE2E`、`benchmarkCompare`、`benchmarkConfirmE2E` | 在方法、参数、线程、fork、JVM 和环境匹配时可比较的精确工作负载证据 | 未观测工作负载或任意环境的普遍结论 |
| Production capacity | 无 | 无 | 生产吞吐、尾延迟、容量、可用性、安全或 SLO |

## 最短工作流

### 1. PR / 入口健康

```shell
./gradlew :wow-benchmarks:check :wow-benchmarks:benchmarkSmoke
```

Smoke 使用单线程、单 fork、一次 1 秒测量且无预热，不生成性能报告。它的完成信号只是有界目录成功执行。

### 2. 方向性 Quick 线索

```shell
./gradlew :wow-benchmarks:benchmarkQuickE2E \
  :wow-benchmarks:generateBenchmarkReport
```

报告写入 [`results/reports/quick-framework-e2e.md`](results/reports/quick-framework-e2e.md)。Quick 使用短预热、单 fork 和有界测量；把它当作定位线索，不要把单次点估计写成正式回归。

按问题选一个更窄的 suite，不必默认跑全目录：

```shell
./gradlew :wow-benchmarks:benchmarkQuickBatchE2E \
  :wow-benchmarks:generateBatchBenchmarkReport
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickWebFlux \
  -PbenchmarkQuickWebFluxThreads=1
```

`benchmarkQuickInfrastructureE2E` 同时需要本地 Redis 和 MongoDB；存储批处理的 Quick/Confirmation task 则按 suite 声明需要 MongoDB 或 Elasticsearch。

### 3. 可重现基线

```shell
./gradlew :wow-benchmarks:benchmarkBaselineE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkCompare
```

`benchmarkCompare` 的阈值越界只是回归/改进候选。用相同 JVM、线程、参数、fork、预热、测量和 profiler 针对受影响方法运行 `benchmarkConfirmE2E`，再得出确认结论。

`updateBenchmarkBaseline` 只接受当前 clean `HEAD` 产生的 clean manifest，且 manifest commit 必须等于 `HEAD`：

```shell
./gradlew :wow-benchmarks:updateBenchmarkBaseline
```

只在已审查比较、已决定接受新基线时执行。不要在脏工作树中更新，也不要用 Quick、Latency、Diagnostic 或旧 JSON 替代新基线。

## 结果与提交边界

- `results/jmh/` 是本地 JMH JSON、人类可读输出和 manifest；不提交本地运行输出。
- `results/reports/*.md` 和 `*.frontier.json` 由 report task 生成；不手工改数据行或 frontier 证据。
- 每个成功的线程级 JMH 运行都会生成 schema-v2 manifest，绑定源码提交/脏状态、run spec、JVM/操作系统、结果哈希与所需服务。
- 历史报告只是其记录条件下的证据；若实现、工作负载或环境已变，从最终 clean 提交重跑。

## 生产容量非目标

Framework E2E 主要使用内存或 noop 基础设施；WebFlux suite 不启动真实 Netty server；本地 Redis/MongoDB/Elasticsearch suite 也不等于生产部署。因此仓库基准不能单独回答实际流量混合、网络、容器限制、副本、持久化、尾延迟、故障或 SLO。

需要生产容量证据时，在代表性环境上另行建立端到端负载、容量阶梯、尾延迟/SLO、稳态和故障恢复验收，并保留部署与运行时证据。

完整的测试分层、CI 对应关系与解读规则见[框架测试与基准](../documentation/docs/zh/guide/test-runtime.md)；从性能症状定位到测量阶段见[故障排查](../documentation/docs/zh/guide/troubleshooting.md#性能与告警)。

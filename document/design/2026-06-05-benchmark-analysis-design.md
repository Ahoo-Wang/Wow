# Benchmark 分析与优化设计

> 日期: 2026-06-05
> 状态: 已确认

## 背景

Wow 框架的 `wow-benchmarks` 模块使用 JMH 进行性能基准测试，目前存在以下问题：
- 存在 Bug 导致部分 benchmark 结果无效
- JMH 配置不统一，跨 benchmark 结果不可比较
- 缺少关键环节的性能覆盖（Projection、Saga、序列化、状态恢复等）
- 结果以手动粘贴纯文本形式存放在 README 中，无版本/环境信息，无法做回归对比

## 目标

1. 修复现有 benchmark 问题，统一配置
2. 补充新的 benchmark 用例，覆盖核心链路
3. 建立结构化报告 + 版本对比体系

## 一、修复现有问题

### 1.1 Bug 修复

**`NoopEventStoreBenchmark.append()` 调用错误：**

`wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt` 中 `append()` 方法调用了 `super.setup()` 而非 `super.append()`，导致测量结果无效。

修复：改为调用 `super.append()`。

### 1.2 配置统一

移除各 benchmark 类上的 `@Warmup/@Measurement/@Fork/@Threads` 注解，统一由 `build.gradle.kts` 全局控制：

```kotlin
jmh {
    threads.set(1)               // 单线程测量纯逻辑吞吐量
    warmupIterations.set(2)
    warmup.set("5s")
    iterations.set(3)
    timeOnIteration.set("10s")
    fork.set(2)
    resultFormat.set("json")
    // JVM args 不变
}
```

选择 threads=1 的理由：先测单线程下的纯逻辑性能基线，消除线程竞争噪声。多线程场景作为后续参数化运行。

### 1.3 测试场景改进

`Commands.kt` 中 `createCommandMessage` 支持两种场景：

- **固定 aggregateId**：测试"更新已有聚合"路径（EventSourcing 回放），CommandDispatcher benchmark 默认使用此模式
- **唯一 aggregateId**：测试"创建新聚合"路径

```kotlin
fun createCommandMessage(aggregateId: String = FIXED_AGGREGATE_ID): CommandMessage<AddCartItem>
fun createCommandMessageForNewAggregate(): CommandMessage<AddCartItem>
```

### 1.4 补充清理

所有 EventStore benchmark 添加 `@TearDown`，避免 InMemoryEventStore 内存无限增长导致 GC 压力污染测量。

## 二、新增 Benchmark

### 优先级与覆盖范围

| 优先级 | 类名 | 位置 | 测量内容 |
|--------|------|------|----------|
| P0 | `SerializationBenchmark` | `serialization/` | Command/DomainEvent/EventStream/Snapshot 的 JSON 序列化→反序列化 |
| P0 | `AggregateStateRecoveryBenchmark` | `eventsourcing/` | EventSourcing 回放重建聚合状态，参数化事件数（10/50/100/500） |
| P0 | `ProjectionBenchmark` | `projection/` | Projection 调度 + 单事件/批量事件处理 |
| P1 | `StatelessSagaBenchmark` | `saga/` | Saga 事件处理 + 命令发送开销 |
| P1 | `EventDispatcherBenchmark` | `event/` | 聚合事件分发器匹配 + 分发 |
| P1 | `SnapshotBenchmark` | `eventsourcing/` | 快照序列化/加载 + VersionOffset 策略决策 |
| P2 | `FilterChainBenchmark` | `messaging/` | FilterChain 构建链 vs 执行链开销 |
| P2 | `EventUpgraderBenchmark` | `event/` | 事件升级管道开销 |

### 文件结构

```
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/
├── command/                                  # 已有（修复）
├── eventsourcing/                            # 已有（修复）+ 新增
│   ├── AggregateStateRecoveryBenchmark.kt   # P0
│   └── SnapshotBenchmark.kt                 # P1
├── event/                                    # 新增
│   ├── EventDispatcherBenchmark.kt          # P1
│   └── EventUpgraderBenchmark.kt            # P2
├── projection/                               # 新增
│   └── ProjectionBenchmark.kt               # P0
├── saga/                                     # 新增
│   └── StatelessSagaBenchmark.kt            # P1
├── serialization/                            # 新增
│   └── SerializationBenchmark.kt            # P0
├── infra/                                    # 已有
├── modeling/                                 # 已有（修复）
└── messaging/                                # 已有 + 新增
    └── FilterChainBenchmark.kt              # P2
```

### 关键设计

**SerializationBenchmark**：对比不同消息类型的序列化→反序列化完整路径。

**AggregateStateRecoveryBenchmark**：使用 `@Param("10", "50", "100", "500")` 参数化事件数量，测量从空聚合开始回放 N 个事件重建状态的开销。

**ProjectionBenchmark**：使用示例聚合的真实 Projection，测试单事件和批量事件处理。

**StatelessSagaBenchmark**：测试 Saga 接收事件→匹配函数→发送命令的完整链路。

## 三、报告生成 + 版本对比

### 文件结构

```
wow-benchmarks/
├── results/
│   └── framework-e2e-baseline.json ← git 跟踪，Full E2E 性能基线
├── README.md                      ← 自动生成，最新报告（git 跟踪）
└── gradle/
    └── benchmarking.gradle.kts    ← benchmark catalog、报告和对比任务
```

### Gradle Tasks

| Task | 作用 |
|------|------|
| `benchmarkSmoke` | 快速入口健康验证，不用于性能结论 |
| `benchmarkFullE2E` | 运行完整 Framework E2E benchmark，输出多线程 JMH JSON |
| `generateBenchmarkReport` | 解析 Full E2E JSON + 环境信息 → 生成 `README.md` |
| `benchmarkCompare` | 对比当前 Full E2E 结果与 `framework-e2e-baseline.json` |
| `updateBenchmarkBaseline` | 将当前 Full E2E 结果保存为新的基线，手动执行 |

### 报告格式

`generateBenchmarkReport` 生成的 `README.md` 包含：

```markdown
# Benchmark Report

## Environment
- **Version**: 8.3.9
- **JVM**: OpenJDK 17.0.12 (Eclipse Temurin)
- **OS**: macOS 15.5 (aarch64)
- **Date**: 2026-06-05
- **JMH Config**: threads=1, warmup=2×5s, measurement=3×10s, fork=2

## Results

| Benchmark | Score | Error | Unit | gc.alloc.rate.norm |
|-----------|-------|-------|------|-------------------|
| ... | | | | |
```

### 对比脚本

`benchmarkCompare` 读取 `results/framework-e2e-baseline.json` 和当前 Full E2E JMH JSON：

- 按 benchmark、mode、threads 匹配
- 计算吞吐量、延迟和分配变化百分比
- 超过阈值时失败
- 输出 Markdown 表格

### 运行流程

```bash
# 完整运行 + 报告生成
./gradlew :wow-benchmarks:benchmarkFullE2E :wow-benchmarks:generateBenchmarkReport

# 版本对比
./gradlew :wow-benchmarks:benchmarkCompare

# 满意后更新基线
./gradlew :wow-benchmarks:updateBenchmarkBaseline

# 快速冒烟
./gradlew :wow-benchmarks:benchmarkSmoke
```

## 不做的事

- 不建 Web 仪表盘或可视化图表
- 不建独立的分析模块
- 不集成 GitHub Release Assets
- 不做多线程 benchmark（后续可参数化扩展）

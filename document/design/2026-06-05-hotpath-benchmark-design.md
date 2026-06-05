# 热点路径基准测试设计

> 日期: 2026-06-05
> 状态: 已确认

## 背景

现有 27 个 benchmark 覆盖了各独立组件，但都是单环节隔离测试。缺少针对框架运行中最高频调用链路的细粒度测量，无法快速定位性能瓶颈。

## 目标

建立三层热点路径 benchmark 体系：
- **L1 微操作**：框架底层高频方法，秒级运行，纳入 CI 冒烟
- **L2 环节独立**：命令处理链各阶段独立测量
- **L3 全链路**：命令发送 → 事件持久化 → 事件发布 → 快照更新的端到端吞吐量

## 设计

### L1 微操作 Benchmark（CI 冒烟，秒级）

| Benchmark | 测什么 |
|-----------|--------|
| `HeaderCreationBenchmark` | `DefaultHeader` 创建 + 属性读写 |
| `MessageWrappingBenchmark` | `SimpleCommandMessage` 包装 + header 传播 |
| `AggregateIdGenerationBenchmark` | CosId 生成 ID + `DefaultAggregateId` 构建 |
| `MessageFunctionLookupBenchmark` | `SimpleMessageFunctionRegistrar.supportedFunctions()` 在不同注册量下的查找速度（@Param 1/10/100/500） |
| `ObjectMapperLookupBenchmark` | Jackson ObjectMapper 的 writeValueAsString/readValue 对小对象的往返耗时 |

BloomFilter 和 EventStream 创建已有覆盖，不重复新增。

### L2 环节独立 Benchmark（发版前，分钟级）

命令处理链分解：

```
命令接收 → 验证 → 幂等检查 → 聚合加载 → 命令处理(事件生成) → 事件持久化 → 事件发布 → 快照更新
```

| Benchmark | 测什么 | Setup |
|-----------|--------|-------|
| `CommandValidationBenchmark` | `Validator.validate()` 对命令对象的校验耗时 | 准备好 CommandMessage |
| `IdempotencyBenchmark` | 完整幂等检查流程（BloomFilter check → 标记） | BloomFilterIdempotencyChecker + 预填充数据 |
| `AggregateLoadingBenchmark` | `EventSourcingStateAggregateRepository.load()` 回放 N 个事件重建状态（@Param 1/10/50） | InMemoryEventStore 预写入事件 |
| `CommandHandlingBenchmark` | 聚合接收命令 → 应用状态变更 → 生成事件流 | 预加载聚合状态 |
| `EventPublishBenchmark` | `DomainEventBus.send()` 发布事件到 InMemory bus | 准备好 DomainEventStream |
| `SnapshotSaveBenchmark` | `SnapshotRepository.save()` + `VersionOffsetSnapshotStrategy` 决策 + 写入 | InMemorySnapshotRepository |

EventStore append 已有 `InMemoryEventStoreBenchmark` 覆盖，增强参数化即可。

### L3 全链路 Benchmark（发版前，分钟级）

一个 benchmark 类测完整链路：命令发送 → 事件持久化 → 事件发布 → 快照更新。

- `sendAndWaitForProcessed`：完整同步处理
- `sendFireAndForget`：仅发送不等处理完成

复用 `AbstractCommandDispatcherBenchmark` 模式（InMemory 后端 + VersionOffsetSnapshotStrategy）。

### 共享 Fixture

`hotpath/HotPathFixture.kt` 提供所有 L1/L2/L3 benchmark 的共享测试数据：

- 预填充的 InMemoryEventStore
- 统一的 CommandMessage、DomainEventStream
- 统一的 AggregateMetadata

### CI 冒烟配置

将 L1 benchmark 加入 `benchmarkSmokeIncludes`：

```kotlin
val benchmarkSmokeIncludes = listOf(
    "me.ahoo.wow.command.CommandFactoryBenchmark",
    "me.ahoo.wow.command.GlobalIdBenchmark",
    "me.ahoo.wow.messaging.function.MessageFunctionRegistrarBenchmark",
    "me.ahoo.wow.hotpath.HeaderCreationBenchmark",
    "me.ahoo.wow.hotpath.MessageWrappingBenchmark",
    "me.ahoo.wow.hotpath.AggregateIdGenerationBenchmark",
    "me.ahoo.wow.hotpath.ObjectMapperLookupBenchmark",
)
```

### 文件结构

```
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/
├── HotPathFixture.kt                     # 共享 fixture
├── HeaderCreationBenchmark.kt            # L1
├── MessageWrappingBenchmark.kt           # L1
├── AggregateIdGenerationBenchmark.kt     # L1
├── ObjectMapperLookupBenchmark.kt        # L1
├── CommandValidationBenchmark.kt         # L2
├── IdempotencyBenchmark.kt               # L2
├── AggregateLoadingBenchmark.kt          # L2
├── CommandHandlingBenchmark.kt           # L2
├── EventPublishBenchmark.kt              # L2
├── SnapshotSaveBenchmark.kt              # L2
└── CommandProcessingPipelineBenchmark.kt # L3
```

### 运行方式

```bash
# L1 冒烟（CI，秒级）
./gradlew :wow-benchmarks:benchmarkSmoke

# L1 + L2 + L3 完整运行（发版前）
./gradlew :wow-benchmarks:jmh
```

## 不做的事

- 不做 Projection/Saga 的全链路 benchmark（不在本次写路径范围内）
- 不做读路径 benchmark（后续可扩展）
- 不建独立的报告体系（复用已有的 generateBenchmarkReport / benchmarkCompare）

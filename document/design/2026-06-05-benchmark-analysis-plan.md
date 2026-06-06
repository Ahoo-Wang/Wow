# Benchmark 分析与优化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复现有 JMH benchmark 问题、统一配置、补充新的性能测试用例、建立结构化报告与版本对比体系。

**Architecture:** 所有改动集中在 `wow-benchmarks` 模块内。修复现有 Bug 和配置后，新增 P0/P1/P2 三个优先级的 benchmark 类。报告体系通过 Gradle task 解析 JMH JSON 输出、附加环境元数据生成 Markdown 报告，对比脚本读取 baseline/latest JSON 做差值分析。

**Tech Stack:** Kotlin, JMH (Java Microbenchmark Harness), Gradle, Jackson (JSON 解析)

---

## File Structure

### 修改文件

| 文件 | 变更 |
|------|------|
| `wow-benchmarks/build.gradle.kts` | 统一 JMH 配置 + 新增 `generateBenchmarkReport` / `updateBaseline` task |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt` | Bug 修复：`super.setup()` → `super.append()` |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt` | 重构：支持固定/唯一 aggregateId |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/BloomFilterIdempotencyCheckerBenchmark.kt` | 移除 `@Warmup/@Measurement/@Fork/@Threads` 注解 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/DeepCopyBenchmark.kt` | 移除 `@Warmup/@Measurement/@Fork/@Threads` 注解 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/SinkBenchmark.kt` | 移除 `@Warmup/@Measurement/@Fork/@Threads` 注解 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt` | 移除注解 + 添加 `@TearDown` |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt` | 移除注解（同上文件） |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/EventStreamFactoryBenchmark.kt` | 移除注释掉的注解 |

### 新增文件

| 文件 | 职责 |
|------|------|
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/serialization/SerializationBenchmark.kt` | P0: JSON 序列化/反序列化全流程 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AggregateStateRecoveryBenchmark.kt` | P0: EventSourcing 回放重建 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/projection/ProjectionBenchmark.kt` | P0: Projection 调度与执行 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/saga/StatelessSagaBenchmark.kt` | P1: Saga 事件处理 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventDispatcherBenchmark.kt` | P1: 事件分发器 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/SnapshotBenchmark.kt` | P1: 快照操作 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/messaging/FilterChainBenchmark.kt` | P2: FilterChain 开销 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventUpgraderBenchmark.kt` | P2: 事件升级管道 |
| `wow-benchmarks/results/.gitkeep` | results 目录占位 |
| `wow-benchmarks/results/.gitkeep` | 占位，确保目录在 git 中 |

---

## Task 1: 修复 NoopEventStoreBenchmark Bug

**Files:**
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt`

- [ ] **Step 1: 修复 append() 调用**

将 `super.setup()` 改为 `super.append()`：

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt
// 修改前（第 34-36 行）:
@Benchmark
override fun append() {
    super.setup()
}

// 修改后:
@Benchmark
override fun append() {
    super.append()
}
```

- [ ] **Step 2: 运行 smoke 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt
git commit -m "fix(benchmark): correct NoopEventStoreBenchmark.append() to call super.append()"
```

---

## Task 2: 统一 JMH 配置

**Files:**
- Modify: `wow-benchmarks/build.gradle.kts`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/BloomFilterIdempotencyCheckerBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/DeepCopyBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/SinkBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/EventStreamFactoryBenchmark.kt`

- [ ] **Step 1: 更新 build.gradle.kts 全局 JMH 配置**

修改 `wow-benchmarks/build.gradle.kts` 中的 `jmh { }` 块：

```kotlin
jmh {
    zip64.set(true)
    includes.set(listOf(".*Benchmark.*"))
    threads.set(1)
    warmupIterations.set(2)
    warmup.set("5s")
    iterations.set(3)
    timeOnIteration.set("10s")
    fork.set(2)
    resultFormat.set("json")
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))
    resultsFile.set(layout.buildDirectory.file("results/jmh/latest.json"))
    jvmArgs.set(
        listOf(
            "-Xmx4g",
            "-Xms4g",
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:+AlwaysPreTouch",
        )
    )
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    val hasAsyncProfiler = asyncProfilerLib.exists()
    profilers.set(buildList {
        add("gc")
        if (hasAsyncProfiler) {
            add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
        } else {
            add("stack:lines=10;top=20")
        }
    })
}
```

- [ ] **Step 2: 移除 BloomFilterIdempotencyCheckerBenchmark 的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/BloomFilterIdempotencyCheckerBenchmark.kt
// 删除以下注解（第 28-31 行）:
// @Warmup(iterations = 1)
// @Measurement(iterations = 2)
// @Fork(value = 2)
// @Threads(2)

// 类定义改为:
@State(Scope.Benchmark)
open class BloomFilterIdempotencyCheckerBenchmark {
```

- [ ] **Step 3: 移除 DeepCopyBenchmark 的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/DeepCopyBenchmark.kt
// 删除以下注解（第 31-34 行）:
// @Warmup(iterations = 1, time = 5)
// @Measurement(iterations = 2, time = 5)
// @Fork(value = 2)
// @Threads(1)

// 类定义改为:
@State(Scope.Benchmark)
open class DeepCopyBenchmark {
```

- [ ] **Step 4: 移除 SinkBenchmark 的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/SinkBenchmark.kt
// 删除以下注解（第 31-34 行）:
// @Warmup(iterations = 1)
// @Measurement(iterations = 2)
// @Fork(value = 2)
// @Threads(2)

// 类定义改为:
@State(Scope.Benchmark)
open class SinkBenchmark {
```

- [ ] **Step 5: 移除 InMemoryEventStoreBenchmark 的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt
// 删除以下注解:
// @Warmup(iterations = 1)
// @Measurement(iterations = 1)
// @Fork(value = 2)
// @Threads(2)

// 类定义改为:
@State(Scope.Benchmark)
open class InMemoryEventStoreBenchmark : AbstractEventStoreBenchmark() {
```

- [ ] **Step 6: 移除 NoopEventStoreBenchmark 的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/NoopEventStoreBenchmark.kt
// 删除以下注解:
// @Warmup(iterations = 1)
// @Measurement(iterations = 2)
// @Fork(value = 2)

// 类定义改为:
@State(Scope.Benchmark)
open class NoopEventStoreBenchmark : AbstractEventStoreBenchmark() {
```

- [ ] **Step 7: 移除 EventStreamFactoryBenchmark 中注释掉的注解**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/EventStreamFactoryBenchmark.kt
// 删除注释掉的注解行:
// @Warmup(iterations = 1)
// @Measurement(iterations = 2)
// @Fork(value = 2)

// 类定义保持:
@State(Scope.Benchmark)
open class EventStreamFactoryBenchmark {
```

- [ ] **Step 8: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add wow-benchmarks/
git commit -m "refactor(benchmark): unify JMH config to build.gradle.kts, remove per-class annotations"
```

---

## Task 3: 改进 Commands.kt 测试场景 + 添加 EventStore TearDown

**Files:**
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt`

- [ ] **Step 1: 重构 Commands.kt 支持固定和唯一 aggregateId**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt
// 完整替换文件内容为:

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.command

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

val cartAggregateMetadata by lazy {
    aggregateMetadata<Cart, CartState>()
}

private val benchmarkCart = MaterializedNamedAggregate("example-service", "cart")
private val benchmarkIdSequence = AtomicLong()
const val FIXED_AGGREGATE_ID = "benchmark-cart-fixed-id"

fun createCommandMessage(): CommandMessage<AddCartItem> {
    val id = nextBenchmarkId()
    return createCommandMessage(
        id = id,
        requestId = id,
        aggregateId = FIXED_AGGREGATE_ID,
        namedAggregate = benchmarkCart,
    )
}

fun createCommandMessageForNewAggregate(): CommandMessage<AddCartItem> {
    val id = nextBenchmarkId()
    return createCommandMessage(
        id = id,
        requestId = id,
        aggregateId = nextBenchmarkId(),
        namedAggregate = benchmarkCart,
    )
}

fun createSmokeCommandMessage(): CommandMessage<AddCartItem> {
    return createCommandMessage(
        id = "benchmark-command-id",
        requestId = "benchmark-request-id",
        aggregateId = "benchmark-cart-id",
        namedAggregate = benchmarkCart,
    )
}

private fun createCommandMessage(
    id: String,
    requestId: String?,
    aggregateId: String?,
    namedAggregate: MaterializedNamedAggregate?,
): CommandMessage<AddCartItem> {
    return AddCartItem(
        productId = "productId"
    ).toCommandMessage(
        id = id,
        requestId = requestId,
        aggregateId = aggregateId,
        namedAggregate = namedAggregate,
    )
}

private fun nextBenchmarkId(): String = "benchmark-${benchmarkIdSequence.incrementAndGet()}"

fun createBloomFilterIdempotencyChecker(): BloomFilterIdempotencyChecker {
    return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
        BloomFilter.create(
            Funnels.stringFunnel(Charsets.UTF_8),
            10_000_000,
            0.00001,
        )
    }
}
```

- [ ] **Step 2: 给 InMemoryEventStoreBenchmark 添加 @TearDown**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt
// 完整替换为:

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.eventsourcing

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

@State(Scope.Benchmark)
open class InMemoryEventStoreBenchmark : AbstractEventStoreBenchmark() {

    @Setup
    override fun setup() {
        super.setup()
    }

    @TearDown
    fun tearDown() {
        (eventStore as? InMemoryEventStore)?.close()
    }

    override fun createEventStore(): EventStore {
        return InMemoryEventStore()
    }

    @Benchmark
    override fun append() {
        super.append()
    }
}
```

- [ ] **Step 3: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/InMemoryEventStoreBenchmark.kt
git commit -m "refactor(benchmark): support fixed/new aggregateId and add EventStore TearDown"
```

---

## Task 4: 新增 SerializationBenchmark (P0)

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/serialization/SerializationBenchmark.kt`

- [ ] **Step 1: 创建 SerializationBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/serialization/SerializationBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.serialization

import me.ahoo.wow.command.createCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.createEventStream
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class SerializationBenchmark {
    private val commandMessage = createCommandMessage()
    private val eventStream: DomainEventStream = createEventStream()

    @Benchmark
    fun commandSerializeDeserialize(blackhole: Blackhole) {
        val json = commandMessage.toJsonString()
        val deserialized = json.toObject<me.ahoo.wow.api.command.CommandMessage<*>>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun eventStreamSerializeDeserialize(blackhole: Blackhole) {
        val json = eventStream.toJsonString()
        val deserialized = json.toObject<DomainEventStream>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun commandSerialize(blackhole: Blackhole) {
        val json = commandMessage.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun eventStreamSerialize(blackhole: Blackhole) {
        val json = eventStream.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun commandDeserialize(blackhole: Blackhole) {
        val deserialized = preSerializedCommand.toObject<me.ahoo.wow.api.command.CommandMessage<*>>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun eventStreamDeserialize(blackhole: Blackhole) {
        val deserialized = preSerializedEventStream.toObject<DomainEventStream>()
        blackhole.consume(deserialized)
    }

    private val preSerializedCommand by lazy { commandMessage.toJsonString() }
    private val preSerializedEventStream by lazy { eventStream.toJsonString() }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/serialization/
git commit -m "feat(benchmark): add SerializationBenchmark (P0)"
```

---

## Task 5: 新增 AggregateStateRecoveryBenchmark (P0)

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AggregateStateRecoveryBenchmark.kt`

- [ ] **Step 1: 创建 AggregateStateRecoveryBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AggregateStateRecoveryBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class AggregateStateRecoveryBenchmark {
    @Param("10", "50", "100", "500")
    var eventCount: Int = 10

    private lateinit var eventStreams: List<DomainEventStream>
    private lateinit var aggregateId: me.ahoo.wow.api.modeling.AggregateId

    @Setup
    fun setup() {
        aggregateId = cartAggregateMetadata.aggregateId()
        eventStreams = (1..eventCount).map { index ->
            val event = CartItemAdded(CartItem("product-$index", index))
            listOf<Any>(event).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
            )
        }
    }

    @Benchmark
    fun recoverFromEvents(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            cartAggregateMetadata.state,
            aggregateId,
        )
        for (eventStream in eventStreams) {
            aggregate.onSourcing(eventStream)
        }
        blackhole.consume(aggregate)
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AggregateStateRecoveryBenchmark.kt
git commit -m "feat(benchmark): add AggregateStateRecoveryBenchmark (P0)"
```

---

## Task 6: 新增 ProjectionBenchmark (P0)

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/projection/ProjectionBenchmark.kt`

- [ ] **Step 1: 创建 ProjectionBenchmark**

此 benchmark 测试 Projection 函数匹配和执行的开销。使用 ProjectionFunctionRegistrar 模拟真实的 Projection 注册和查找流程：

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/projection/ProjectionBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.projection

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.aggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class ProjectionBenchmark {
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>
    private lateinit var domainEvent: SimpleDomainEvent<CartItemAdded>
    private lateinit var projectionFunction: BenchmarkProjectionFunction

    @Setup
    fun setup() {
        registrar = SimpleMessageFunctionRegistrar()
        projectionFunction = BenchmarkProjectionFunction(
            topic = cartAggregateMetadata.namedAggregate,
        )
        registrar.register(projectionFunction)

        val aggregateId = cartAggregateMetadata.aggregateId()
        domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(me.ahoo.wow.example.api.cart.CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
    }

    @Benchmark
    fun functionLookup(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(domainEvent).toList()
        blackhole.consume(functions)
    }

    @Benchmark
    fun functionLookupAndInvoke(blackhole: Blackhole) {
        val function = registrar.supportedFunctions(domainEvent).firstOrNull()
        if (function != null) {
            val exchange = SimpleBenchmarkExchange(domainEvent)
            val result = function.invoke(exchange)
            blackhole.consume(result)
        }
    }
}

private class SimpleBenchmarkExchange(
    override val message: SimpleDomainEvent<CartItemAdded>,
) : DomainEventExchange<CartItemAdded> {
    override val event = message
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun setAttribute(key: String, value: Any) {}
    override fun <T> getAttribute(key: String): T? = null
    override fun <T : Any> getAttribute(key: String, valueType: Class<T>): T? = null
}

private class BenchmarkProjectionFunction(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Any> {
    override val supportedType: Class<*> = CartItemAdded::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.PROJECTION
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-projection"
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun invoke(exchange: DomainEventExchange<*>): Any = Mono.empty()
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/projection/
git commit -m "feat(benchmark): add ProjectionBenchmark (P0)"
```

---

## Task 7: 新增 StatelessSagaBenchmark (P1)

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/saga/StatelessSagaBenchmark.kt`

- [ ] **Step 1: 创建 StatelessSagaBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/saga/StatelessSagaBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.saga

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.saga.stateless.CommandStream
import me.ahoo.wow.saga.stateless.DefaultCommandStream
import me.ahoo.wow.saga.stateless.StatelessSagaFunction
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class StatelessSagaBenchmark {
    private lateinit var sagaFunction: StatelessSagaFunction
    private lateinit var exchange: DomainEventExchange<CartItemAdded>
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>

    @Setup
    fun setup() {
        val topic = cartAggregateMetadata.namedAggregate
        val aggregateId = cartAggregateMetadata.aggregateId()

        val delegate = BenchmarkSagaDelegate(topic)
        sagaFunction = StatelessSagaFunction(delegate)

        registrar = SimpleMessageFunctionRegistrar()
        registrar.register(sagaFunction)

        val domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(me.ahoo.wow.example.api.cart.CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
        exchange = SimpleSagaExchange(domainEvent)
    }

    @Benchmark
    fun functionLookup(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(exchange.message).toList()
        blackhole.consume(functions)
    }

    @Benchmark
    fun sagaInvoke(blackhole: Blackhole) {
        val result = sagaFunction.invoke(exchange).block()
        blackhole.consume(result)
    }
}

private class SimpleSagaExchange(
    override val message: SimpleDomainEvent<CartItemAdded>,
) : DomainEventExchange<CartItemAdded> {
    override val event = message
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun setAttribute(key: String, value: Any) {}
    override fun <T> getAttribute(key: String): T? = null
    override fun <T : Any> getAttribute(key: String, valueType: Class<T>): T? = null
}

private class BenchmarkSagaDelegate(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<CommandStream>> {
    override val supportedType: Class<*> = CartItemAdded::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.SAGA
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-saga"
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun invoke(exchange: DomainEventExchange<*>): Mono<CommandStream> {
        return Mono.just(DefaultCommandStream(emptyList()))
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/saga/
git commit -m "feat(benchmark): add StatelessSagaBenchmark (P1)"
```

---

## Task 8: 新增 EventDispatcherBenchmark + SnapshotBenchmark + FilterChainBenchmark + EventUpgraderBenchmark (P1/P2)

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventDispatcherBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/SnapshotBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/messaging/FilterChainBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventUpgraderBenchmark.kt`

- [ ] **Step 1: 创建 EventDispatcherBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventDispatcherBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.event

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.dispatcher.DomainEventFunctionRegistrar
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.aggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class EventDispatcherBenchmark {
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>
    private lateinit var domainEvent: SimpleDomainEvent<CartItemAdded>

    @Setup
    fun setup() {
        registrar = SimpleMessageFunctionRegistrar()
        val topic = cartAggregateMetadata.namedAggregate
        registrar.register(BenchmarkEventHandler(topic))
        registrar.register(BenchmarkEventHandler(topic))

        val aggregateId = cartAggregateMetadata.aggregateId()
        domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(me.ahoo.wow.example.api.cart.CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
    }

    @Benchmark
    fun functionLookup(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(domainEvent).count()
        blackhole.consume(functions)
    }
}

private class BenchmarkEventHandler(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val supportedType: Class<*> = CartItemAdded::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-event-handler"
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty()
}
```

- [ ] **Step 2: 创建 SnapshotBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/SnapshotBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SimpleStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class SnapshotBenchmark {
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var snapshotStrategy: VersionOffsetSnapshotStrategy
    private lateinit var stateEventExchange: StateEventExchange<*>
    private lateinit var aggregateId: me.ahoo.wow.api.modeling.AggregateId

    @Setup
    fun setup() {
        aggregateId = cartAggregateMetadata.aggregateId()
        snapshotRepository = InMemorySnapshotRepository()
        snapshotStrategy = VersionOffsetSnapshotStrategy(
            versionOffset = 5,
            snapshotRepository = snapshotRepository,
        )

        val aggregate = ConstructorStateAggregateFactory.create(
            cartAggregateMetadata.state,
            aggregateId,
        )
        val snapshot = SimpleSnapshot(aggregate)
        snapshotRepository.save(snapshot).block()

        val stateEvent = SimpleStateEvent(
            aggregateId = aggregateId,
            version = 1,
            eventTime = System.currentTimeMillis(),
            state = aggregate,
        )
        stateEventExchange = StateEventExchange(stateEvent)
    }

    @Benchmark
    fun snapshotStrategyEvaluate(blackhole: Blackhole) {
        val result = snapshotStrategy.onEvent(stateEventExchange).block()
        blackhole.consume(result)
    }

    @Benchmark
    fun snapshotLoad(blackhole: Blackhole) {
        val snapshot = snapshotRepository.load<me.ahoo.wow.modeling.state.SimpleStateAggregate<*>>(aggregateId).block()
        blackhole.consume(snapshot)
    }
}
```

- [ ] **Step 3: 创建 FilterChainBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/messaging/FilterChainBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.messaging

import me.ahoo.wow.filter.EmptyFilterChain
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.SimpleFilterChain
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class FilterChainBenchmark {
    @Param("1", "5", "10")
    var filterCount: Int = 1

    private lateinit var chain: FilterChain<String>
    private val context = "benchmark-context"

    @Setup
    fun setup() {
        chain = FilterChainBuilder<String>()
            .apply {
                repeat(filterCount) {
                    addFilter(NoopFilter)
                }
            }
            .build()
    }

    @Benchmark
    fun executeChain(blackhole: Blackhole) {
        val result = chain.filter(context).block()
        blackhole.consume(result)
    }
}

private object NoopFilter : Filter<String> {
    override fun filter(context: String, chain: FilterChain<String>): Mono<Void> {
        return chain.filter(context)
    }
}
```

- [ ] **Step 4: 创建 EventUpgraderBenchmark**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventUpgraderBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.event


import me.ahoo.wow.event.upgrader.EventNamedAggregate
import me.ahoo.wow.event.upgrader.EventUpgrader
import me.ahoo.wow.event.upgrader.MaterializedEventNamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.event.DelegatingDomainEventRecord
import me.ahoo.wow.serialization.event.DomainEventRecord
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import tools.jackson.databind.node.ObjectNode
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toObject

@State(Scope.Benchmark)
open class EventUpgraderBenchmark {
    @Param("1", "3", "5")
    var upgraderCount: Int = 1

    private lateinit var upgraders: List<BenchmarkEventUpgrader>
    private lateinit var eventRecord: DomainEventRecord

    @Setup
    fun setup() {
        val namedAggregate = MaterializedNamedAggregate("benchmark", "aggregate")
        upgraders = (1..upgraderCount).map { index ->
            BenchmarkEventUpgrader(
                eventNamedAggregate = MaterializedEventNamedAggregate(namedAggregate, "EventV$index"),
            )
        }

        val bodyNode: ObjectNode = me.ahoo.wow.serialization.JsonSerializer.createObjectNode()
        bodyNode.put("field1", "value1")
        bodyNode.put("version", 1)
        val recordNode: ObjectNode = me.ahoo.wow.serialization.JsonSerializer.createObjectNode()
        recordNode.put("id", "event-id")
        recordNode.put("aggregateId", namedAggregate.aggregateId("agg-id").id)
        recordNode.put("contextName", namedAggregate.contextName)
        recordNode.put("aggregateName", namedAggregate.aggregateName)
        recordNode.put("version", 1)
        recordNode.put("commandId", "command-id")
        recordNode.put("bodyType", BenchmarkEventBody::class.java.name)
        recordNode.set<ObjectNode>("body", bodyNode)
        eventRecord = DelegatingDomainEventRecord(recordNode)
    }

    @Benchmark
    fun upgradePipeline(blackhole: Blackhole) {
        var current = eventRecord
        for (upgrader in upgraders) {
            current = upgrader.upgrade(current)
        }
        blackhole.consume(current)
    }
}

private data class BenchmarkEventBody(val field1: String = "", val version: Int = 1)

private class BenchmarkEventUpgrader(
    override val eventNamedAggregate: EventNamedAggregate,
) : EventUpgrader {
    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        return domainEventRecord
    }
}
```

- [ ] **Step 5: 验证编译通过**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/ wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/SnapshotBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/messaging/
git commit -m "feat(benchmark): add EventDispatcher/Snapshot/FilterChain/EventUpgrader benchmarks (P1/P2)"
```

---

## Task 9: 添加报告生成 Gradle Tasks + 对比 Task + 初始化 results 目录

**Files:**
- Modify: `wow-benchmarks/build.gradle.kts`
- Create: `wow-benchmarks/results/.gitkeep`
- Create: `wow-benchmarks/.gitignore`

- [ ] **Step 1: 在 build.gradle.kts 末尾追加 generateBenchmarkReport、benchmarkCompare、updateBaseline 三个 task**

使用 Jackson ObjectMapper（已在 classpath 中，无需新增依赖）解析 JMH JSON：

```kotlin
// === 追加到 wow-benchmarks/build.gradle.kts 末尾 ===

val resultsDir = layout.projectDirectory.dir("results")
val baselineJson = resultsDir.file("baseline.json")
val latestJson = layout.buildDirectory.file("results/jmh/latest.json")
val readmeFile = layout.projectDirectory.file("README.md")

tasks.register("generateBenchmarkReport") {
    description = "Generate benchmark README.md from JMH JSON results."
    group = "benchmark"
    dependsOn(tasks.named("jmh"))

    val resultsFile = latestJson.get().asFile
    inputs.file(resultsFile)
    outputs.file(readmeFile.asFile)

    doLast {
        if (!resultsFile.exists()) {
            throw GradleException("JMH results not found: ${resultsFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val resultsText = resultsFile.readText()
        @Suppress("UNCHECKED_CAST")
        val jmhResults = mapper.readValue(resultsText, List::class.java) as List<Map<String, Any>>

        val version = project.version.toString()
        val date = java.time.LocalDate.now().toString()
        val jvm = System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
        val os = System.getProperty("os.name") + " " + System.getProperty("os.arch")

        val sb = StringBuilder()
        sb.appendLine("# Benchmark Report")
        sb.appendLine()
        sb.appendLine("## Environment")
        sb.appendLine("- **Version**: $version")
        sb.appendLine("- **JVM**: $jvm")
        sb.appendLine("- **OS**: $os")
        sb.appendLine("- **Date**: $date")
        sb.appendLine("- **JMH Config**: threads=1, warmup=2×5s, measurement=3×10s, fork=2")
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()
        sb.appendLine("| Benchmark | Score | Error | Unit | gc.alloc.rate.norm |")
        sb.appendLine("|-----------|-------|-------|------|-------------------|")

        for (result in jmhResults) {
            val benchmark = result["benchmark"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val primaryMetric = result["primaryMetric"] as? Map<String, Any> ?: continue
            val score = primaryMetric["score"] as? Double ?: continue
            val scoreError = primaryMetric["scoreError"] as? Double ?: 0.0
            val unit = primaryMetric["scoreUnit"] as? String ?: "ops/s"

            var allocRateNorm = "—"
            @Suppress("UNCHECKED_CAST")
            val secondaryMetrics = result["secondaryMetrics"] as? Map<String, Map<String, Any>>
            if (secondaryMetrics != null) {
                val gcAlloc = secondaryMetrics["gc.alloc.rate.norm"]
                allocRateNorm = String.format("%.1f B/op", gcAlloc?.get("score") as? Double ?: 0.0)
            }

            val shortName = benchmark.substringAfterLast(".")
            sb.appendLine("| $shortName | ${String.format("%.2f", score)} | ±${String.format("%.2f", scoreError)} | $unit | $allocRateNorm |")
        }

        readmeFile.asFile.writeText(sb.toString())
        logger.lifecycle("Benchmark report generated: ${readmeFile.asFile.absolutePath}")
    }
}

tasks.register("benchmarkCompare") {
    description = "Compare latest benchmark results against baseline."
    group = "benchmark"

    doLast {
        val latestFile = latestJson.get().asFile
        val baselineFile = baselineJson.asFile

        if (!baselineFile.exists()) {
            throw GradleException("Baseline not found: ${baselineFile.absolutePath}. Run :wow-benchmarks:updateBaseline first.")
        }
        if (!latestFile.exists()) {
            throw GradleException("Latest results not found: ${latestFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        fun parseScores(file: java.io.File): Map<String, Double> {
            @Suppress("UNCHECKED_CAST")
            val results = mapper.readValue(file, List::class.java) as List<Map<String, Any>>
            return results.associate { result ->
                val benchmark = result["benchmark"] as String
                @Suppress("UNCHECKED_CAST")
                val primaryMetric = result["primaryMetric"] as Map<String, Any>
                benchmark to (primaryMetric["score"] as Double)
            }
        }

        val baseline = parseScores(baselineFile)
        val latest = parseScores(latestFile)
        val allBenchmarks = (baseline.keys + latest.keys).sorted()

        var regressions = 0
        var improvements = 0

        println()
        println("## Benchmark Comparison")
        println()
        println("| Benchmark | Baseline | Current | Δ% | Status |")
        println("|-----------|----------|---------|----|--------|")

        for (benchmark in allBenchmarks) {
            val baseScore = baseline[benchmark]
            val latestScore = latest[benchmark]
            val shortName = benchmark.substringAfterLast(".")

            if (baseScore == null) {
                println("| $shortName | — | ${String.format("%.2f", latestScore)} | NEW | 🆕 |")
                continue
            }
            if (latestScore == null) {
                println("| $shortName | ${String.format("%.2f", baseScore)} | — | REMOVED | ⚠️ |")
                continue
            }

            val changePercent = ((latestScore - baseScore) / baseScore) * 100
            val status = when {
                changePercent < -10.0 -> {
                    regressions++
                    "🔴 REGRESSION"
                }
                changePercent > 10.0 -> {
                    improvements++
                    "🟢 IMPROVED"
                }
                else -> "✅"
            }

            println("| $shortName | ${String.format("%.2f", baseScore)} | ${String.format("%.2f", latestScore)} | ${String.format("%+.1f%%", changePercent)} | $status |")
        }

        println()
        println("**Summary:** $regressions regression(s), $improvements improvement(s), ${allBenchmarks.size - regressions - improvements} stable")

        if (regressions > 0) {
            throw GradleException("Benchmark regressions detected: $regressions")
        }
    }
}

tasks.register("updateBaseline") {
    description = "Copy latest benchmark results as the new baseline."
    group = "benchmark"

    doLast {
        val latestFile = latestJson.get().asFile
        val baselineFile = baselineJson.asFile

        if (!latestFile.exists()) {
            throw GradleException("Latest results not found: ${latestFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        latestFile.copyTo(baselineFile, overwrite = true)
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
```

- [ ] **Step 2: 创建 results/.gitkeep 并配置 .gitignore**

```bash
mkdir -p wow-benchmarks/results
touch wow-benchmarks/results/.gitkeep
```

创建 `wow-benchmarks/.gitignore`：

```
results/latest.json
```

- [ ] **Step 3: 验证 Gradle 同步**

Run: `./gradlew :wow-benchmarks:tasks --group=benchmark`
Expected: 列出 `generateBenchmarkReport`、`benchmarkCompare`、`updateBaseline` 三个 task

- [ ] **Step 4: Commit**

```bash
git add wow-benchmarks/build.gradle.kts wow-benchmarks/results/.gitkeep wow-benchmarks/.gitignore
git commit -m "feat(benchmark): add report generation, comparison, and baseline tasks"
```

---

## Task 10: 更新 README.md 模板

**Files:**
- Modify: `wow-benchmarks/README.md`

- [ ] **Step 1: 替换 README 为报告模板说明**

将现有的手动粘贴结果替换为自动生成说明：

```markdown
<!-- 
  此文件由 ./gradlew :wow-benchmarks:generateBenchmarkReport 自动生成。
  请勿手动编辑 benchmark 结果。
  手动粘贴的结果已被自动报告替代。
-->

# Benchmark Report

> 运行 `./gradlew :wow-benchmarks:jmh :wow-benchmarks:generateBenchmarkReport` 生成最新报告。
>
> 对比基线：`./gradlew :wow-benchmarks:benchmarkCompare`
>
> 更新基线：`./gradlew :wow-benchmarks:updateBaseline`
```

- [ ] **Step 2: Commit**

```bash
git add wow-benchmarks/README.md
git commit -m "docs(benchmark): replace manual results with auto-generated report template"
```

---

## Task 11: Smoke 测试验证

**Files:**
- 无新文件

- [ ] **Step 1: 运行 benchmarkSmoke 确认所有 benchmark 编译且可执行**

Run: `./gradlew :wow-benchmarks:benchmarkSmoke`
Expected: `BUILD SUCCESSFUL`，所有 smoke benchmark 正常完成

- [ ] **Step 2: 检查 smoke 报告输出**

Run: `cat wow-benchmarks/build/reports/jmh/benchmark-smoke.json`
Expected: JSON 文件包含各 benchmark 的结果条目

- [ ] **Step 3: Final commit（如有遗留修改）**

```bash
git add -A wow-benchmarks/
git commit -m "chore(benchmark): verify smoke tests pass"
```
TASK9_EOF
echo "Done: $(wc -l /tmp/plan-tail.md)"
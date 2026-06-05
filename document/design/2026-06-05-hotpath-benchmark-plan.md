# Hot-Path Benchmark 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立三层热点路径 benchmark 体系（L1 微操作 / L2 环节独立 / L3 全链路），用于快速定位 Wow 框架性能瓶颈。

**Architecture:** 所有新 benchmark 放在 `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/` 包下，共享 `HotPathFixture.kt` 提供的 fixture。L1 benchmark 加入 CI 冒烟配置。所有 benchmark 使用 InMemory 后端，不依赖外部服务。

**Tech Stack:** Kotlin, JMH (Java Microbenchmark Harness), Jackson, CosId, Reactor

---

## File Structure

### 新增文件

| 文件 | 层 | 职责 |
|------|---|------|
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt` | 共享 | 所有 hot-path benchmark 的共享 fixture |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HeaderCreationBenchmark.kt` | L1 | DefaultHeader 创建 + 属性读写 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/MessageWrappingBenchmark.kt` | L1 | SimpleCommandMessage 包装 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateIdGenerationBenchmark.kt` | L1 | CosId ID 生成 + AggregateId 构建 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/ObjectMapperLookupBenchmark.kt` | L1 | Jackson ObjectMapper 序列化往返 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandValidationBenchmark.kt` | L2 | 命令验证耗时 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/IdempotencyBenchmark.kt` | L2 | 幂等检查完整流程 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateLoadingBenchmark.kt` | L2 | 聚合状态加载（参数化事件数） |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandHandlingBenchmark.kt` | L2 | 聚合命令处理 → 事件生成 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/EventPublishBenchmark.kt` | L2 | 事件发布到 InMemory bus |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/SnapshotSaveBenchmark.kt` | L2 | 快照策略评估 + 保存 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt` | L3 | 命令处理全链路 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `wow-benchmarks/build.gradle.kts` | 将 L1 benchmark 加入 `benchmarkSmokeIncludes` |

---

## Task 1: 创建 HotPathFixture + L1 微操作 Benchmark（4 个）

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HeaderCreationBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/MessageWrappingBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateIdGenerationBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/ObjectMapperLookupBenchmark.kt`
- Modify: `wow-benchmarks/build.gradle.kts` — 更新 `benchmarkSmokeIncludes`

- [ ] **Step 1: 创建 HotPathFixture.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt

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

package me.ahoo.wow.hotpath

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.command.createBloomFilterIdempotencyChecker
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.id.CosIdGlobalIdGeneratorFactory
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import java.time.Duration

object HotPathFixture {
    val namedAggregate = MaterializedNamedAggregate("example-service", "cart")
    val aggregateMetadata = cartAggregateMetadata
    val aggregateId = aggregateMetadata.aggregateId()

    init {
        DefaultIdGeneratorProvider.INSTANCE.set(
            CosIdGlobalIdGeneratorFactory.ID_NAME,
            ClockSyncCosIdGenerator(Radix62CosIdGenerator(0)),
        )
    }

    fun createHeader(): Header {
        return DefaultHeader()
    }

    fun createCommandMessage(): CommandMessage<AddCartItem> {
        return AddCartItem(productId = "productId").toCommandMessage(
            id = generateGlobalId(),
            requestId = generateGlobalId(),
            aggregateId = aggregateId.id,
            namedAggregate = namedAggregate,
        )
    }

    fun createEventStream(): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return listOf<Any>(event).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
        )
    }

    fun createBloomFilterIdempotencyChecker(): BloomFilterIdempotencyChecker {
        return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8),
                10_000_000,
                0.00001,
            )
        }
    }
}
```

- [ ] **Step 2: 创建 HeaderCreationBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HeaderCreationBenchmark.kt

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

package me.ahoo.wow.hotpath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class HeaderCreationBenchmark {
    private val fixture = HotPathFixture

    @Benchmark
    fun createEmptyHeader(blackhole: Blackhole) {
        val header = fixture.createHeader()
        blackhole.consume(header)
    }

    @Benchmark
    fun createAndMutateHeader(blackhole: Blackhole) {
        val header = fixture.createHeader()
        header["key1"] = "value1"
        header["key2"] = "value2"
        blackhole.consume(header)
    }

    @Benchmark
    fun headerReadAfterWrite(blackhole: Blackhole) {
        val header = fixture.createHeader()
        header["key"] = "value"
        val value = header["key"]
        blackhole.consume(value)
    }
}
```

- [ ] **Step 3: 创建 MessageWrappingBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/MessageWrappingBenchmark.kt

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

package me.ahoo.wow.hotpath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class MessageWrappingBenchmark {
    private val fixture = HotPathFixture

    @Benchmark
    fun createCommandMessage(blackhole: Blackhole) {
        val msg = fixture.createCommandMessage()
        blackhole.consume(msg)
    }

    @Benchmark
    fun readCommandMessageProperties(blackhole: Blackhole) {
        val msg = fixture.createCommandMessage()
        blackhole.consume(msg.id)
        blackhole.consume(msg.aggregateId)
        blackhole.consume(msg.requestId)
        blackhole.consume(msg.body)
    }
}
```

- [ ] **Step 4: 创建 AggregateIdGenerationBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateIdGenerationBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.DefaultAggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class AggregateIdGenerationBenchmark {
    private val fixture = HotPathFixture

    @Benchmark
    fun generateGlobalId(blackhole: Blackhole) {
        val id = generateGlobalId()
        blackhole.consume(id)
    }

    @Benchmark
    fun createAggregateId(blackhole: Blackhole) {
        val aggregateId = DefaultAggregateId(
            namedAggregate = fixture.namedAggregate,
            id = "test-id",
        )
        blackhole.consume(aggregateId)
    }

    @Benchmark
    fun generateIdAndCreateAggregateId(blackhole: Blackhole) {
        val id = generateGlobalId()
        val aggregateId = DefaultAggregateId(
            namedAggregate = fixture.namedAggregate,
            id = id,
        )
        blackhole.consume(aggregateId)
    }
}
```

- [ ] **Step 5: 创建 ObjectMapperLookupBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/ObjectMapperLookupBenchmark.kt

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License or distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.hotpath

import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

data class SmallPayload(val name: String = "test", val value: Int = 42)

@State(Scope.Benchmark)
open class ObjectMapperLookupBenchmark {
    private val payload = SmallPayload()
    private val preSerialized = payload.toJsonString()

    @Benchmark
    fun serialize(blackhole: Blackhole) {
        val json = payload.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun deserialize(blackhole: Blackhole) {
        val obj = preSerialized.toObject<SmallPayload>()
        blackhole.consume(obj)
    }

    @Benchmark
    fun roundTrip(blackhole: Blackhole) {
        val json = payload.toJsonString()
        val obj = json.toObject<SmallPayload>()
        blackhole.consume(obj)
    }

    @Benchmark
    fun serializeWithSharedMapper(blackhole: Blackhole) {
        val json = JsonSerializer.writeValueAsString(payload)
        blackhole.consume(json)
    }
}
```

- [ ] **Step 6: 更新 build.gradle.kts 的 benchmarkSmokeIncludes**

在 `build.gradle.kts` 的 `benchmarkSmokeIncludes` 中追加 L1 benchmark：

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

- [ ] **Step 7: 验证编译**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add wow-benchmarks/
git commit -m "feat(benchmark): add hot-path L1 micro benchmarks + fixture"
```

---

## Task 2: 创建 L2 环节独立 Benchmark（6 个）

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandValidationBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/IdempotencyBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateLoadingBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandHandlingBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/EventPublishBenchmark.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/SnapshotSaveBenchmark.kt`

- [ ] **Step 1: 创建 CommandValidationBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandValidationBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.test.validation.TestValidator
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandValidationBenchmark {
    private val commandMessage = HotPathFixture.createCommandMessage()

    @Benchmark
    fun validateCommand(blackhole: Blackhole) {
        val exchange: ServerCommandExchange<*> = SimpleServerCommandExchange(commandMessage)
        TestValidator.validate(commandMessage.body)
        blackhole.consume(exchange)
    }
}
```

- [ ] **Step 2: 创建 IdempotencyBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/IdempotencyBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.id.generateGlobalId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class IdempotencyBenchmark {
    private lateinit var idempotencyChecker: me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker

    @Setup
    fun setup() {
        idempotencyChecker = HotPathFixture.createBloomFilterIdempotencyChecker()
    }

    @Benchmark
    fun checkNewId(blackhole: Blackhole) {
        val result = idempotencyChecker.check(generateGlobalId()).block()
        blackhole.consume(result)
    }

    @Benchmark
    fun checkFixedId(blackhole: Blackhole) {
        val result = idempotencyChecker.check("known-request-id").block()
        blackhole.consume(result)
    }
}
```

- [ ] **Step 3: 创建 AggregateLoadingBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateLoadingBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class AggregateLoadingBenchmark {
    @Param("1", "10", "50")
    var eventCount: Int = 1

    private lateinit var repository: EventSourcingStateAggregateRepository
    private lateinit var eventStore: InMemoryEventStore

    @Setup
    fun setup() {
        eventStore = InMemoryEventStore()
        repository = EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            eventStore,
        )
    }

    @TearDown
    fun tearDown() {
        setup()
    }

    @Benchmark
    fun loadEmptyAggregate(blackhole: Blackhole) {
        val aggregate = repository.load(
            HotPathFixture.aggregateId,
            HotPathFixture.aggregateMetadata.state,
            Int.MAX_VALUE,
        ).block()
        blackhole.consume(aggregate)
    }
}
```

- [ ] **Step 4: 创建 CommandHandlingBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandHandlingBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandHandlingBenchmark {
    private val command = AddCartItem(productId = "product-1", quantity = 1)

    @Benchmark
    fun createAggregateAndHandle(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            HotPathFixture.aggregateId,
        )
        aggregate.onSourcing(HotPathFixture.createEventStream())
        blackhole.consume(aggregate)
    }

    @Benchmark
    fun createAggregateFromEmpty(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            HotPathFixture.aggregateId,
        )
        blackhole.consume(aggregate)
    }
}
```

- [ ] **Step 5: 创建 EventPublishBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/EventPublishBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.event.InMemoryDomainEventBus
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class EventPublishBenchmark {
    private lateinit var eventBus: InMemoryDomainEventBus
    private val eventStream = HotPathFixture.createEventStream()

    @Setup
    fun setup() {
        eventBus = InMemoryDomainEventBus()
        eventBus.receive(setOf(HotPathFixture.namedAggregate.materialize())).subscribe()
    }

    @TearDown
    fun tearDown() {
        eventBus.close()
    }

    @Benchmark
    fun publishEvent(blackhole: Blackhole) {
        val result = eventBus.send(eventStream).block()
        blackhole.consume(result)
    }
}
```

- [ ] **Step 6: 创建 SnapshotSaveBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/SnapshotSaveBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class SnapshotSaveBenchmark {
    private lateinit var snapshotRepository: InMemorySnapshotRepository
    private lateinit var snapshot: SimpleSnapshot<*>

    @Setup
    fun setup() {
        snapshotRepository = InMemorySnapshotRepository()
        val aggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            HotPathFixture.aggregateId,
        )
        snapshot = SimpleSnapshot(aggregate)
    }

    @Benchmark
    fun saveSnapshot(blackhole: Blackhole) {
        val result = snapshotRepository.save(snapshot).block()
        blackhole.consume(result)
    }
}
```

- [ ] **Step 7: 验证编译**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/
git commit -m "feat(benchmark): add hot-path L2 stage benchmarks"
```

---

## Task 3: 创建 L3 全链路 Benchmark

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt`

- [ ] **Step 1: 创建 CommandProcessingPipelineBenchmark.kt**

```kotlin
// wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt

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

package me.ahoo.wow.hotpath

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.createBloomFilterIdempotencyChecker
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.test.validation.TestValidator
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandProcessingPipelineBenchmark {
    private lateinit var commandGateway: CommandGateway
    private lateinit var commandDispatcher: CommandDispatcher

    @Setup
    fun setup() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        val commandBus: CommandBus = InMemoryCommandBus()
        val domainEventBus: DomainEventBus = InMemoryDomainEventBus()
        val stateEventBus = InMemoryStateEventBus()
        val eventStore = InMemoryEventStore()
        val snapshotRepository = InMemorySnapshotRepository()

        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                createBloomFilterIdempotencyChecker()
            },
            waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier = commandWaitNotifier,
        )

        val stateAggregateRepository: StateAggregateRepository =
            EventSourcingStateAggregateRepository(
                ConstructorStateAggregateFactory,
                snapshotRepository,
                eventStore,
            )
        val aggregateProcessorFactory = RetryableAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore),
        )

        val chain = FilterChainBuilder<ServerCommandExchange<*>>()
            .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
            .addFilter(SendDomainEventStreamFilter(domainEventBus))
            .addFilter(SendStateEventFilter(stateEventBus))
            .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
            .build()
        commandDispatcher = CommandDispatcher(
            commandBus = commandGateway,
            commandHandler = DefaultCommandHandler(chain),
        )
        commandDispatcher.start()
    }

    @TearDown
    fun tearDown() {
        commandDispatcher.stop()
    }

    @Benchmark
    fun sendAndWaitForProcessed(blackhole: Blackhole) {
        try {
            val result = commandGateway.sendAndWaitForProcessed(
                me.ahoo.wow.command.createCommandMessageForNewAggregate(),
            ).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun sendFireAndForget(blackhole: Blackhole) {
        try {
            val result = commandGateway.send(
                me.ahoo.wow.command.createCommandMessageForNewAggregate(),
            ).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew :wow-benchmarks:compileJmhKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt
git commit -m "feat(benchmark): add hot-path L3 full pipeline benchmark"
```

---

## Task 4: Smoke 测试验证

**Files:**
- 无新文件

- [ ] **Step 1: 运行 benchmarkSmoke 确认 L1 benchmark 正常**

Run: `./gradlew :wow-benchmarks:benchmarkSmoke`
Expected: `BUILD SUCCESSFUL`，输出包含 HeaderCreation、MessageWrapping、AggregateIdGeneration、ObjectMapperLookup 的结果

- [ ] **Step 2: 检查 smoke 报告包含新增 benchmark**

Run: `cat wow-benchmarks/build/reports/jmh/benchmark-smoke.json | grep "hotpath"`
Expected: 至少 4 个 L1 benchmark 结果

- [ ] **Step 3: Final commit（如有遗留修改）**

```bash
git add -A wow-benchmarks/
git commit -m "chore(benchmark): verify hot-path smoke tests pass"
```

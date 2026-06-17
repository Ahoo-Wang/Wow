# TypeId Convention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入派生式事件 TypeId，让领域事件反序列化优先按 `contextName + aggregateName + name + revision` 解析运行时类型，并在失败时继续 fallback 到 `bodyType`。

**Architecture:** 第一阶段不新增 `typeId` JSON 字段，也不改 Schema/OpenAPI/BI。新增一个小型事件类型身份和值对象注册表，运行时由框架从 `MetadataSearcher.metadata` 自动注册事件类型，业务代码不需要逐个事件手动注册。`DomainEventRecord` 在事件升级后先用派生 `EventTypeId + revision` 形成的 `EventTypeKey` 查注册表，精确匹配失败时再回退到现有 `bodyType -> Class.forName()` 路径。事件流中的单个事件通过 `StreamDomainEventRecord` 继承流级 `contextName` 和 `aggregateName`，因此同一解析逻辑可同时覆盖单事件和事件流。

**Tech Stack:** Kotlin 2.3.20、JUnit Jupiter、`me.ahoo.test:fluent-assert-core`、Jackson tools、Gradle `:wow-core:test`。

---

## 文件结构

- Create: `wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/EventTypeId.kt`
  - 定义 `EventTypeId`、`EventTypeKey`、`EventTypeDescriptor`、`EventTypeRegistry`。
  - 提供 `DomainEventRecord.toEventTypeId()` 扩展，统一从现有字段派生身份。
  - `resolve()` 首次调用时从 `MetadataSearcher.metadata` 自动加载事件类型。
  - 注册表使用 `ConcurrentHashMap`，重复 `EventTypeKey` 但不同 descriptor 时快速失败。
  - `Aggregate.events` 中的 package scope 条目不做类加载；不可链接的事件类安全跳过并保留 `bodyType` fallback。
- Create: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/event/EventTypeRegistryTest.kt`
  - 覆盖 TypeId 派生、注册解析、重复注册失败。
- Modify: `wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt`
  - 将 `toDomainEventObject()` 的类型解析从单一路径改为“派生 EventTypeKey 精确匹配优先，`bodyType` fallback”。
- Modify: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt`
  - 覆盖单事件反序列化优先使用派生 TypeId。
  - 覆盖事件流反序列化优先使用派生 TypeId。
  - 覆盖事件 JSON 不新增 `typeId` 字段。
- Existing tests: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerPolymorphicTest.kt`
  - 继续验证未知 `bodyType` 保留为 `JsonDomainEvent`。

---

### Task 1: 新增事件 TypeId 值对象和注册表

**Files:**
- Create: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/event/EventTypeRegistryTest.kt`
- Create: `wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/EventTypeId.kt`

- [ ] **Step 1: 写失败测试**

创建 `wow-core/src/test/kotlin/me/ahoo/wow/serialization/event/EventTypeRegistryTest.kt`：

```kotlin
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

package me.ahoo.wow.serialization.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.Aggregate
import me.ahoo.wow.configuration.BoundedContext
import me.ahoo.wow.configuration.WowMetadata
import me.ahoo.wow.event.FIXTURE_EVENT_NAME
import me.ahoo.wow.event.FIXTURE_EVENT_REVISION
import me.ahoo.wow.event.FixtureRevisedEvent
import me.ahoo.wow.event.annotation.eventMetadata
import org.junit.jupiter.api.Test

class EventTypeRegistryTest {

    @Test
    fun `event type id should render stable convention value`() {
        val typeId = EventTypeId(
            contextName = "sales",
            aggregateName = "Order",
            name = "order_created",
        )

        typeId.value.assert().isEqualTo("event://sales/Order/order_created")
        typeId.toString().assert().isEqualTo(typeId.value)
    }

    @Test
    fun `register should resolve descriptor by event type id`() {
        val metadata = eventMetadata<FixtureRevisedEvent>()
        val descriptor = EventTypeRegistry.register(
            contextName = "event",
            aggregateName = "fixture",
            metadata = metadata,
        )

        try {
            descriptor.typeId.assert().isEqualTo(
                EventTypeId("event", "fixture", FIXTURE_EVENT_NAME)
            )
            descriptor.key.assert().isEqualTo(
                EventTypeKey(descriptor.typeId, FIXTURE_EVENT_REVISION)
            )
            descriptor.eventType.assert().isEqualTo(FixtureRevisedEvent::class.java)
            descriptor.revision.assert().isEqualTo(FIXTURE_EVENT_REVISION)

            EventTypeRegistry.resolve(descriptor.typeId, FIXTURE_EVENT_REVISION)
                .assert().isEqualTo(FixtureRevisedEvent::class.java)
            EventTypeRegistry.resolve(descriptor.typeId, "older-revision")
                .assert().isNull()
        } finally {
            EventTypeRegistry.unregister(descriptor.typeId)
        }
    }

    @Test
    fun `register should load descriptors from wow metadata`() {
        val metadata = WowMetadata(
            contexts = mapOf(
                "event" to BoundedContext(
                    aggregates = mapOf(
                        "fixture" to Aggregate(
                            events = setOf(FixtureRevisedEvent::class.java.name)
                        )
                    )
                )
            )
        )
        val descriptors = EventTypeRegistry.register(metadata)
        val descriptor = descriptors.single()

        try {
            descriptor.typeId.assert().isEqualTo(
                EventTypeId("event", "fixture", FIXTURE_EVENT_NAME)
            )
            descriptor.eventType.assert().isEqualTo(FixtureRevisedEvent::class.java)
            descriptor.revision.assert().isEqualTo(FIXTURE_EVENT_REVISION)
        } finally {
            EventTypeRegistry.unregister(descriptor.typeId)
        }
    }

    @Test
    fun `register should reject conflicting descriptor`() {
        val typeId = EventTypeId("event", "fixture", FIXTURE_EVENT_NAME)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = FixtureRevisedEvent::class.java,
                revision = FIXTURE_EVENT_REVISION,
            )
        )

        try {
            val error = kotlin.runCatching {
                EventTypeRegistry.register(
                    EventTypeDescriptor(
                        typeId = typeId,
                        eventType = ConflictingFixtureEvent::class.java,
                        revision = FIXTURE_EVENT_REVISION,
                    )
                )
            }.exceptionOrNull()

            error.assert().isInstanceOf(IllegalStateException::class.java)
            error!!.message!!.assert().contains(typeId.value)
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }

    private data class ConflictingFixtureEvent(val value: String = "value")
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.event.EventTypeRegistryTest"
```

Expected: 编译失败，提示 `EventTypeId`、`EventTypeRegistry` 或 `EventTypeDescriptor` 未定义。

- [ ] **Step 3: 写最小实现**

创建 `wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/EventTypeId.kt`：

```kotlin
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

package me.ahoo.wow.serialization.event

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.configuration.WowMetadata
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.event.annotation.toEventMetadata
import me.ahoo.wow.event.metadata.EventMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class EventTypeId(
    val contextName: String,
    val aggregateName: String,
    val name: String
) {
    init {
        require(contextName.isNotBlank()) { "contextName must not be blank." }
        require(aggregateName.isNotBlank()) { "aggregateName must not be blank." }
        require(name.isNotBlank()) { "name must not be blank." }
    }

    val value: String = "event://$contextName/$aggregateName/$name"

    override fun toString(): String = value
}

data class EventTypeKey(
    val typeId: EventTypeId,
    val revision: String
) {
    init {
        require(revision.isNotBlank()) { "revision must not be blank." }
    }
}

data class EventTypeDescriptor(
    val typeId: EventTypeId,
    val eventType: Class<*>,
    val revision: String
) {
    val key: EventTypeKey = EventTypeKey(typeId, revision)
}

object EventTypeRegistry {
    private val log = KotlinLogging.logger {}
    private val keyDescriptors = ConcurrentHashMap<EventTypeKey, EventTypeDescriptor>()
    private val metadataLoaded = AtomicBoolean(false)

    fun register(
        contextName: String,
        aggregateName: String,
        metadata: EventMetadata<*>
    ): EventTypeDescriptor {
        return register(
            EventTypeDescriptor(
                typeId = EventTypeId(
                    contextName = contextName,
                    aggregateName = aggregateName,
                    name = metadata.name,
                ),
                eventType = metadata.eventType,
                revision = metadata.revision,
            )
        )
    }

    fun register(descriptor: EventTypeDescriptor): EventTypeDescriptor {
        register(keyDescriptors, descriptor.key, descriptor)
        return descriptor
    }

    fun register(metadata: WowMetadata): List<EventTypeDescriptor> {
        return metadata.contexts.flatMap { contextEntry ->
            val contextName = contextEntry.key
            contextEntry.value.aggregates.flatMap { aggregateEntry ->
                val aggregateName = aggregateEntry.key
                aggregateEntry.value.events.mapNotNull { eventTypeName ->
                    registerEventType(contextName, aggregateName, eventTypeName)
                }
            }
        }
    }

    private fun registerEventType(
        contextName: String,
        aggregateName: String,
        eventTypeName: String
    ): EventTypeDescriptor? {
        if (eventTypeName.isEventTypeName().not()) {
            return null
        }
        val eventType = try {
            Class.forName(eventTypeName)
        } catch (classNotFoundException: ClassNotFoundException) {
            log.warn(classNotFoundException) {
                "Event type[$eventTypeName] not found at current runtime, ignore registration."
            }
            return null
        } catch (linkageError: LinkageError) {
            log.warn(linkageError) {
                "Event type[$eventTypeName] can not be linked at current runtime, ignore registration."
            }
            return null
        }
        val metadata = eventType.toEventMetadataUnsafe()
        return register(
            contextName = contextName,
            aggregateName = aggregateName,
            metadata = metadata,
        )
    }

    private fun <K> register(
        registry: ConcurrentHashMap<K, EventTypeDescriptor>,
        key: K,
        descriptor: EventTypeDescriptor
    ) {
        val current = registry.putIfAbsent(key, descriptor)
        if (current == null || current == descriptor) {
            return
        }
        error(
            "EventType[${key}] is already registered to " +
                "[${current.eventType.name}] revision[${current.revision}], " +
                "cannot register [${descriptor.eventType.name}] revision[${descriptor.revision}]."
        )
    }

    fun resolve(typeId: EventTypeId, revision: String): Class<*>? {
        ensureMetadataLoaded()
        return keyDescriptors[EventTypeKey(typeId, revision)]?.eventType
    }

    private fun ensureMetadataLoaded() {
        if (metadataLoaded.compareAndSet(false, true)) {
            register(MetadataSearcher.metadata)
        }
    }

    internal fun unregister(typeId: EventTypeId) {
        keyDescriptors.keys.removeIf {
            it.typeId == typeId
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Class<*>.toEventMetadataUnsafe(): EventMetadata<*> {
    return (this as Class<Any>).toEventMetadata()
}

internal fun String.isEventTypeName(): Boolean {
    val typeName = substringAfterLast('.')
    return typeName.firstOrNull()?.isUpperCase() == true
}

fun DomainEventRecord.toEventTypeId(): EventTypeId {
    return EventTypeId(
        contextName = contextName,
        aggregateName = aggregateName,
        name = name,
    )
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.event.EventTypeRegistryTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/EventTypeId.kt \
  wow-core/src/test/kotlin/me/ahoo/wow/serialization/event/EventTypeRegistryTest.kt
git commit -m "feat(event): add derived event TypeId registry"
```

---

### Task 2: 单事件反序列化优先使用派生 TypeId

**Files:**
- Modify: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt`
- Modify: `wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt`

- [ ] **Step 1: 写失败测试**

在 `JsonSerializerEventTest` 中新增 imports：

```kotlin
import me.ahoo.wow.serialization.event.EventTypeDescriptor
import me.ahoo.wow.serialization.event.EventTypeId
import me.ahoo.wow.serialization.event.EventTypeRegistry
```

在 `domain event deserializer should rebuild known body types` 测试后添加：

```kotlin
    @Test
    fun `domain event deserializer should prefer derived event type over body type`() {
        val event = domainEvent(sequence = 1, isLast = true)
        val node = event.toJsonNode<ObjectNode>()
        val typeId = EventTypeId("sales", "Order", "OrderCreated")
        node.put(MessageRecords.BODY_TYPE, LegacyOrderCreated::class.java.name)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = OrderCreated::class.java,
                revision = "1",
            )
        )

        try {
            val decoded = node.toJsonString().toObject<DomainEvent<*>>()

            decoded.body.assert().isInstanceOf(OrderCreated::class.java)
            (decoded.body as OrderCreated).orderId.assert().isEqualTo("order-1")
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }
```

在文件末尾已有 `OrderCreated` 后新增：

```kotlin
    private data class LegacyOrderCreated(val legacyId: String)
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
```

Expected: 新增测试失败，因为当前 `DomainEventRecord` 仍直接使用 `bodyType` 解析类型。

- [ ] **Step 3: 修改 DomainEventRecord 解析逻辑**

在 `DomainEventRecord.kt` 中增加 import：

```kotlin
import tools.jackson.databind.JsonNode
```

将 `toDomainEventObject()` 替换为：

```kotlin
    private fun toDomainEventObject(): DomainEvent<Any> {
        val aggregateId = toAggregateId()
        val bodyType = resolveBodyType()
            ?: return toJsonDomainEvent(aggregateId, body) as DomainEvent<Any>
        return SimpleDomainEvent(
            id = id,
            header = toMessageHeader(),
            body = body.toObject(bodyType),
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            version = version,
            sequence = sequence,
            isLast = isLast,
            revision = revision,
            commandId = commandId,
            name = name,
            createTime = createTime,
        )
    }
```

在 `DomainEventRecord` interface 中 `toDomainEventObject()` 后新增：

```kotlin
    @Suppress("UNCHECKED_CAST")
    private fun resolveBodyType(): Class<Any>? {
        EventTypeRegistry.resolve(toEventTypeId(), revision)?.let {
            return it as Class<Any>
        }
        return try {
            bodyType.toType<Any>()
        } catch (classNotFoundException: ClassNotFoundException) {
            null
        }
    }

    private fun toJsonDomainEvent(aggregateId: AggregateId, body: JsonNode): JsonDomainEvent {
        return JsonDomainEvent(
            id = id,
            header = toMessageHeader(),
            bodyType = bodyType,
            body = body,
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            version = version,
            sequence = sequence,
            isLast = isLast,
            revision = revision,
            commandId = commandId,
            name = name,
            createTime = createTime,
        )
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt \
  wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt
git commit -m "feat(event): prefer derived TypeId during deserialization"
```

---

### Task 3: 覆盖事件流和“不新增 typeId 字段”约束

**Files:**
- Modify: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt`

- [ ] **Step 1: 写事件流测试**

在 `event stream deserializer should derive event sequence and last flag from body order` 测试后添加：

```kotlin
    @Test
    fun `event stream serializer should not write type id field`() {
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(domainEvent(sequence = 1, isLast = true)),
        )

        val node = stream.toJsonNode<ObjectNode>()
        val eventNode = node[MessageRecords.BODY][0] as ObjectNode

        (eventNode["typeId"] == null).assert().isTrue()
        eventNode[MessageRecords.BODY_TYPE].asString().assert().isEqualTo(OrderCreated::class.java.name)
    }

    @Test
    fun `event stream deserializer should prefer derived event type over body type`() {
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(domainEvent(sequence = 1, isLast = true)),
        )
        val node = stream.toJsonNode<ObjectNode>()
        val eventNode = node[MessageRecords.BODY][0] as ObjectNode
        val typeId = EventTypeId("sales", "Order", "OrderCreated")
        eventNode.put(MessageRecords.BODY_TYPE, LegacyOrderCreated::class.java.name)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = OrderCreated::class.java,
                revision = "1",
            )
        )

        try {
            val decoded = node.toJsonString().toObject<DomainEventStream>()

            decoded.first().body.assert().isInstanceOf(OrderCreated::class.java)
            (decoded.first().body as OrderCreated).orderId.assert().isEqualTo("order-1")
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }
```

- [ ] **Step 2: 运行测试确认行为**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
```

Expected: `BUILD SUCCESSFUL`。如果 Task 2 尚未完成，第二个新增测试会失败；完成 Task 2 后应通过。

- [ ] **Step 3: 提交**

```bash
git add wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt
git commit -m "test(event): cover TypeId convention for event streams"
```

---

### Task 4: 回归未知类型 fallback 和事件升级路径

**Files:**
- Existing: `wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerPolymorphicTest.kt`
- Existing: `wow-core/src/test/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactoryTest.kt`

- [ ] **Step 1: 运行未知类型 fallback 测试**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerPolymorphicTest"
```

Expected: `BUILD SUCCESSFUL`。这确认派生 TypeId 解析失败时仍保留 `JsonDomainEvent`，并且重新序列化仍保留原 `bodyType`。

- [ ] **Step 2: 运行事件升级测试**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.event.upgrader.EventUpgraderFactoryTest"
```

Expected: `BUILD SUCCESSFUL`。这确认 `EventUpgraderFactory.upgrade(this)` 仍在类型解析前执行，升级器仍可通过修改 `name`、`revision`、`bodyType`、`body` 影响最终解析。

- [ ] **Step 3: 如果任一测试失败，修复 DomainEventRecord 顺序**

确认 `DomainEventRecord.toDomainEvent()` 保持如下顺序：

```kotlin
    fun toDomainEvent(): DomainEvent<Any> {
        val upgradedRecord = EventUpgraderFactory.upgrade(this)
        return upgradedRecord.toDomainEventObject()
    }
```

Expected: 升级先发生，派生 TypeId 和 fallback `bodyType` 都基于升级后的 record。

- [ ] **Step 4: 提交**

如果没有代码变更，不创建提交。若修复了顺序问题，提交：

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt
git commit -m "fix(event): preserve upgrade order for TypeId resolution"
```

---

### Task 5: 窄范围验证和最终整理

**Files:**
- Verify only unless tests reveal a bug.

- [ ] **Step 1: 运行核心序列化相关测试**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行事件升级和 TypeId 注册测试**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.event.upgrader.EventUpgraderFactoryTest" \
  --tests "me.ahoo.wow.serialization.event.EventTypeRegistryTest"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 查看 diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: 只剩计划内文件变更；如果所有任务都已按步骤提交，则工作区应干净。

- [ ] **Step 4: 最终提交**

如果前面任务已逐项提交且工作区干净，此步骤不创建提交。若仍有未提交的测试或小修，提交：

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/EventTypeId.kt \
  wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt \
  wow-core/src/test/kotlin/me/ahoo/wow/serialization/event/EventTypeRegistryTest.kt \
  wow-core/src/test/kotlin/me/ahoo/wow/serialization/JsonSerializerEventTest.kt
git commit -m "feat(event): resolve events by derived TypeId"
```

---

## 自检

- 规格覆盖：计划覆盖了派生 `EventTypeId`、`EventTypeKey` 解析、`bodyType` fallback、不新增 `typeId` 字段、未知类型保留为 `JsonDomainEvent`、事件升级顺序、重复注册失败。
- 范围控制：第一阶段只改 `wow-core` 的事件反序列化核心和测试；不改 KSP、Schema、OpenAPI、BI、存储格式。
- 类型一致性：计划统一使用 `EventTypeId`、`EventTypeDescriptor`、`EventTypeRegistry`、`toEventTypeId()`；JSON 字段仍只有现有 `bodyType`。
- 验证命令：每个任务都有对应 Gradle 命令，最终验证限制在 `:wow-core:test` 的相关测试范围内。

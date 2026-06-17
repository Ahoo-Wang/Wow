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
    fun `event type name should reject package scope entries`() {
        FixtureRevisedEvent::class.java.name.isEventTypeName().assert().isTrue()
        "me.ahoo.sales.order.event".isEventTypeName().assert().isFalse()
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
    fun `register should ignore package scope entries from wow metadata`() {
        val metadata = WowMetadata(
            contexts = mapOf(
                "event" to BoundedContext(
                    aggregates = mapOf(
                        "fixture" to Aggregate(
                            events = setOf("me.ahoo.wow.event")
                        )
                    )
                )
            )
        )

        EventTypeRegistry.register(metadata).assert().isEmpty()
    }

    @Test
    fun `register should ignore event classes that fail linking`() {
        val metadata = WowMetadata(
            contexts = mapOf(
                "event" to BoundedContext(
                    aggregates = mapOf(
                        "fixture" to Aggregate(
                            events = setOf(LinkingFailedEvent::class.java.name)
                        )
                    )
                )
            )
        )

        EventTypeRegistry.register(metadata).assert().isEmpty()
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

    @Test
    fun `register should allow same event type id with different revisions`() {
        val typeId = EventTypeId("event", "fixture", FIXTURE_EVENT_NAME)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = FixtureRevisedEvent::class.java,
                revision = FIXTURE_EVENT_REVISION,
            )
        )
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = ConflictingFixtureEvent::class.java,
                revision = "next-revision",
            )
        )

        try {
            EventTypeRegistry.resolve(typeId, FIXTURE_EVENT_REVISION)
                .assert().isEqualTo(FixtureRevisedEvent::class.java)
            EventTypeRegistry.resolve(typeId, "next-revision")
                .assert().isEqualTo(ConflictingFixtureEvent::class.java)
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }

    private data class ConflictingFixtureEvent(val value: String = "value")

    private class LinkingFailedEvent private constructor(private val value: String = "value") {
        companion object {
            init {
                throw NoClassDefFoundError("missing.Dependency")
            }
        }
    }
}

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
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WowMetadata
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

    fun resolve(typeId: EventTypeId, revision: String): Class<*>? {
        ensureMetadataLoaded()
        return keyDescriptors[EventTypeKey(typeId, revision)]?.eventType
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

    private fun ensureMetadataLoaded() {
        if (metadataLoaded.compareAndSet(false, true)) {
            register(MetadataSearcher.metadata)
        }
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
            "EventType[$key] is already registered to " +
                "[${current.eventType.name}] revision[${current.revision}], " +
                "cannot register [${descriptor.eventType.name}] revision[${descriptor.revision}]."
        )
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

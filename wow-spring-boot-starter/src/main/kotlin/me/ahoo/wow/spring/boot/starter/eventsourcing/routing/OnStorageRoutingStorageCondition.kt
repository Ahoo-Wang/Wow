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
package me.ahoo.wow.spring.boot.starter.eventsourcing.routing

import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

class OnStorageRoutingStorageCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): ConditionOutcome {
        val eventStorage = metadata.storageAttribute(ConditionalOnEventStoreStorage::class.java.name)
        if (eventStorage != null) {
            return eventStoreStorageOutcome(context, eventStorage)
        }
        val snapshotStorage = metadata.storageAttribute(ConditionalOnSnapshotStoreStorage::class.java.name)
        if (snapshotStorage != null) {
            return snapshotStoreStorageOutcome(context, snapshotStorage)
        }
        return ConditionOutcome.noMatch("No storage routing condition annotation was found.")
    }

    private fun eventStoreStorageOutcome(context: ConditionContext, storage: StorageType): ConditionOutcome {
        val binder = Binder.get(context.environment)
        val eventStoreProperties = binder.bindEventStoreProperties()
        val storageRoutingProperties = binder.bindStorageRoutingProperties()
        val matched = eventStoreProperties.storage == storage ||
            storageRoutingProperties.aggregates.values.any { aggregateRoute ->
                aggregateRoute.event?.storage == storage
            }
        return outcome(matched, "event", storage)
    }

    private fun snapshotStoreStorageOutcome(context: ConditionContext, storage: StorageType): ConditionOutcome {
        val binder = Binder.get(context.environment)
        val snapshotProperties = binder.bindSnapshotProperties()
        if (!snapshotProperties.enabled) {
            return ConditionOutcome.noMatch("Snapshot storage routing is disabled.")
        }
        val storageRoutingProperties = binder.bindStorageRoutingProperties()
        val matched = snapshotProperties.storage == storage ||
            storageRoutingProperties.aggregates.values.any { aggregateRoute ->
                aggregateRoute.snapshot?.storage == storage
            }
        return outcome(matched, "snapshot", storage)
    }

    private fun outcome(matched: Boolean, channel: String, storage: StorageType): ConditionOutcome {
        val message = "Storage routing $channel storage matches $storage."
        return if (matched) {
            ConditionOutcome.match(message)
        } else {
            ConditionOutcome.noMatch(message)
        }
    }

    private fun AnnotatedTypeMetadata.storageAttribute(annotationName: String): StorageType? {
        val attributes = getAnnotationAttributes(annotationName) ?: return null
        return attributes[VALUE_ATTRIBUTE] as StorageType
    }

    private fun Binder.bindEventStoreProperties(): EventStoreProperties =
        bind(EventStoreProperties.PREFIX, EventStoreProperties::class.java)
            .let { result ->
                if (result.isBound) {
                    result.get()
                } else {
                    EventStoreProperties()
                }
            }

    private fun Binder.bindSnapshotProperties(): SnapshotProperties =
        bind(SnapshotProperties.PREFIX, SnapshotProperties::class.java)
            .let { result ->
                if (result.isBound) {
                    result.get()
                } else {
                    SnapshotProperties()
                }
            }

    private fun Binder.bindStorageRoutingProperties(): StorageRoutingProperties =
        bind(StorageRoutingProperties.PREFIX, StorageRoutingProperties::class.java)
            .let { result ->
                if (result.isBound) {
                    result.get()
                } else {
                    StorageRoutingProperties()
                }
            }

    companion object {
        private const val VALUE_ATTRIBUTE = "value"
    }
}

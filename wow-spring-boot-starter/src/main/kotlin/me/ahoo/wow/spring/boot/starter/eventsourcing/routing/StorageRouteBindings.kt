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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import java.util.Locale

data class EventStoreBinding(
    val name: String,
    val storage: StorageType?,
    val eventStore: EventStore
) {
    companion object {
        fun storage(storage: StorageType, eventStore: EventStore): EventStoreBinding =
            EventStoreBinding(
                name = storage.bindingPrefix() + "-event-store",
                storage = storage,
                eventStore = eventStore,
            )
    }
}

data class SnapshotStoreBinding(
    val name: String,
    val storage: StorageType?,
    val snapshotStore: SnapshotStore
) {
    companion object {
        fun storage(storage: StorageType, snapshotStore: SnapshotStore): SnapshotStoreBinding =
            SnapshotStoreBinding(
                name = storage.bindingPrefix() + "-snapshot-store",
                storage = storage,
                snapshotStore = snapshotStore,
            )
    }
}

data class EventStreamQueryServiceFactoryBinding(
    val name: String,
    val storage: StorageType?,
    val eventStreamQueryServiceFactory: EventStreamQueryServiceFactory
) {
    companion object {
        fun storage(
            storage: StorageType,
            eventStreamQueryServiceFactory: EventStreamQueryServiceFactory
        ): EventStreamQueryServiceFactoryBinding =
            EventStreamQueryServiceFactoryBinding(
                name = storage.bindingPrefix() + "-event-stream-query-service-factory",
                storage = storage,
                eventStreamQueryServiceFactory = eventStreamQueryServiceFactory,
            )
    }
}

data class SnapshotQueryServiceFactoryBinding(
    val name: String,
    val storage: StorageType?,
    val snapshotQueryServiceFactory: SnapshotQueryServiceFactory
) {
    companion object {
        fun storage(
            storage: StorageType,
            snapshotQueryServiceFactory: SnapshotQueryServiceFactory
        ): SnapshotQueryServiceFactoryBinding =
            SnapshotQueryServiceFactoryBinding(
                name = storage.bindingPrefix() + "-snapshot-query-service-factory",
                storage = storage,
                snapshotQueryServiceFactory = snapshotQueryServiceFactory,
            )
    }
}

private fun StorageType.bindingPrefix(): String =
    name.lowercase(Locale.ROOT).replace('_', '-')

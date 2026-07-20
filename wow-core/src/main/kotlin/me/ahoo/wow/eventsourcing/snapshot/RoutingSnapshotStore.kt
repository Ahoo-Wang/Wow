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
package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import reactor.core.publisher.Mono

open class RoutingSnapshotStore(
    protected val registry: AggregateSnapshotStoreRegistry
) : SnapshotStore {
    override val name: String
        get() = NAME

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
        registry.get(aggregateId.namedAggregate).load(aggregateId)

    override fun getVersion(aggregateId: AggregateId): Mono<Int> =
        registry.get(aggregateId.namedAggregate).getVersion(aggregateId)

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
        registry.get(snapshot.aggregateId.namedAggregate).save(snapshot)

    companion object {
        const val NAME = "routing"

        fun create(registry: AggregateSnapshotStoreRegistry): RoutingSnapshotStore =
            if (registry.supportsHistoricalCheckpoints) {
                VersionedRoutingSnapshotStore(registry)
            } else {
                RoutingSnapshotStore(registry)
            }
    }
}

private class VersionedRoutingSnapshotStore(
    registry: AggregateSnapshotStoreRegistry,
) : RoutingSnapshotStore(registry),
    VersionedSnapshotStore {

    override fun <S : Any> loadAtOrBefore(
        aggregateId: AggregateId,
        maxVersion: Int,
    ): Mono<Snapshot<S>> =
        versionedStore(aggregateId).loadAtOrBefore(aggregateId, maxVersion)

    override fun <S : Any> saveCheckpoint(snapshot: Snapshot<S>): Mono<Void> =
        versionedStore(snapshot.aggregateId).saveCheckpoint(snapshot)

    private fun versionedStore(aggregateId: AggregateId): VersionedSnapshotStore =
        registry.get(aggregateId.namedAggregate) as VersionedSnapshotStore
}

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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.naming.Named
import reactor.core.publisher.Mono

/**
 * Store for saving and loading snapshots of state aggregates.
 * Snapshots optimize aggregate loading by providing a recent state checkpoint.
 */
interface SnapshotStore :
    Named,
    AutoCloseable {
    /**
     * Closes this snapshot store and releases owned resources.
     *
     * Decorators should forward this call to their delegate. Stores without
     * owned resources may rely on this no-op implementation.
     */
    override fun close() = Unit

    /**
     * Loads the latest snapshot for the specified aggregate.
     *
     * @param S the type of the state
     * @param aggregateId the ID of the aggregate
     * @return a Mono emitting the snapshot or empty if none exists
     */
    fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>>

    /**
     * Gets the version of the latest snapshot for the specified aggregate.
     * Returns UNINITIALIZED_VERSION if no snapshot exists.
     *
     * @param aggregateId the ID of the aggregate
     * @return a Mono emitting the version
     */
    fun getVersion(aggregateId: AggregateId): Mono<Int> =
        load<Any>(aggregateId)
            .map {
                it.version
            }.defaultIfEmpty(Version.UNINITIALIZED_VERSION)

    /**
     * Saves a snapshot to the store.
     *
     * Implementations must keep the stored snapshot version monotonically non-decreasing.
     * The save must atomically compare and write for each aggregate: a candidate whose
     * version is greater than or equal to the stored version replaces it. A candidate
     * with a lower version is ignored and the operation completes normally.
     * The compared candidate version must come from the same materialized snapshot
     * representation that is written.
     *
     * @param S the type of the state
     * @param snapshot the snapshot to save
     * @return a Mono that completes when the save operation is done
     */
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>
}

/**
 * No-operation implementation of SnapshotStore that does nothing.
 * Useful for testing or when snapshots are not needed.
 */
object NoOpSnapshotStore : SnapshotStore {
    /**
     * The name of this store.
     */
    const val NAME = "no_op"

    /**
     * The name of this store.
     */
    override val name: String
        get() = NAME

    /**
     * Always returns empty, as this is a no-op store.
     */
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> = Mono.empty()

    /**
     * Does nothing, as this is a no-op store.
     */
    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> = Mono.empty()
}

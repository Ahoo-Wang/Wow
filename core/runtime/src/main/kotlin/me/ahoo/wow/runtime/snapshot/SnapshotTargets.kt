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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * Where an aggregate type's snapshots are written (decisions D172, D177): its [targets], the names of the stores that
 * each keep them, and its [primary], the one the write side reads as its cache and queries read.
 */
data class SnapshotPlacement(val targets: List<String>, val primary: String = targets.first()) {
    init {
        require(targets.isNotEmpty()) { "Snapshots have at least one target." }
        require(targets.none { it.isBlank() }) { "A snapshot target names its store." }
        require(targets.toSet().size == targets.size) { "Each snapshot target is listed once: $targets." }
        require(primary in targets) { "The primary snapshot target $primary is not one of the targets $targets." }
    }
}

/**
 * The snapshot targets of one bounded context (decision D172): its snapshot [stores] by name, and the [placement] of
 * each aggregate type, [SnapshotPlacement] of the [default] store alone for a type that has none.
 *
 * Each store has a built-in subscription of its own, with its own checkpoints, which writes the snapshots of the types
 * it is a target of ([subscriptionOf]): `snapshot` for the [default] store, as before targets, and `snapshot:<store>`
 * for each other one. A store named so keeps its subscription, and so its checkpoints, whatever the targets list:
 * adding a target to a type, switching its primary or dropping a target moves no checkpoint. The `snapshot` hop of a
 * wait is the primary target's subscription of the command's aggregate type.
 */
class SnapshotTargets(
    val default: String,
    val stores: Map<String, SnapshotStore>,
    placements: Map<AggregateType, SnapshotPlacement> = emptyMap(),
) {
    private val placements: Map<AggregateType, SnapshotPlacement> = placements.toMap()

    init {
        require(default in stores) { "The default snapshot store $default is not one of ${stores.keys}." }
        val missing = this.placements.values.flatMap { it.targets }.filter { it !in stores }.distinct()
        require(missing.isEmpty()) { "The snapshot targets $missing name no snapshot store of ${stores.keys}." }
    }

    /** Where [type]'s snapshots are written. */
    fun placementOf(type: AggregateType): SnapshotPlacement = placements[type] ?: SnapshotPlacement(listOf(default))

    /** The store [type]'s snapshots are read from: its primary target. */
    fun primaryOf(type: AggregateType): SnapshotStore = stores.getValue(placementOf(type).primary)

    /** The snapshots each type's primary target keeps: what the kernel and the state readers read. */
    val primary: SnapshotStore = SnapshotStoreRouter.of(
        stores.getValue(default),
        this.placements.mapValues { (_, placement) -> stores.getValue(placement.primary) },
    )

    /** The subscription of the store [store]: `snapshot` for the [default] store, `snapshot:<store>` for another. */
    fun subscriptionOf(store: String): String =
        if (store == default) Hop.SNAPSHOT_SUBSCRIPTION else "${Hop.SNAPSHOT_SUBSCRIPTION}:$store"

    /** The subscription of [type]'s primary target: the one whose handling ends a wait's `snapshot` hop. */
    fun primarySubscriptionOf(type: AggregateType): String = subscriptionOf(placementOf(type).primary)

    /** The stores that are a target of one of [types], each with the types it keeps, in the order of [stores]. */
    fun storesOf(types: Collection<AggregateType>): Map<String, Set<AggregateType>> {
        val byStore = LinkedHashMap<String, MutableSet<AggregateType>>()
        stores.keys.forEach { store ->
            types.filter { store in placementOf(it).targets }.takeIf { it.isNotEmpty() }?.let {
                byStore[store] = it.toCollection(LinkedHashSet())
            }
        }
        return byStore
    }

    companion object {
        /** The one snapshot [store] of a context, named [name]: every type's only target. */
        @JvmStatic
        fun single(store: SnapshotStore, name: String = DEFAULT_STORE): SnapshotTargets =
            SnapshotTargets(name, mapOf(name to store))

        /** The name of the default store when the host names none. */
        const val DEFAULT_STORE: String = "default"
    }
}

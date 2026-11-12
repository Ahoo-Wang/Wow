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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.subscribe.SystemSubscriptions
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * Where an aggregate type's snapshots are written: its [store], the one the write side reads as its cache and
 * its state reads read, and, when its queries read another, that store's [copy], written by a subscription of its own.
 */
@InternalWowApi
class SnapshotPlacement(val store: String, val copy: String? = null) {
    init {
        require(store.isNotBlank()) { "A snapshot placement names its store." }
        require(copy == null || copy.isNotBlank() && copy != store) {
            "A snapshot copy names a store other than the type's own, $store."
        }
    }

    /** The stores that keep the type's snapshots: its own, then its copy. */
    val targets: List<String> get() = listOfNotNull(store, copy)

    /** The store the type's snapshot queries read: its copy when it has one, else its own. */
    val query: String get() = copy ?: store

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotPlacement && store == other.store && copy == other.copy

    override fun hashCode(): Int = 31 * store.hashCode() + copy.hashCode()

    override fun toString(): String = "SnapshotPlacement(store=$store, copy=$copy)"
}

/**
 * The snapshot targets of one bounded context: its snapshot [stores] by name, and the [placement] of
 * each aggregate type, [fallback] for a type that has none: by default the [default] store alone.
 *
 * Each store has a built-in subscription, with its own checkpoints, which writes the snapshots of every type it is
 * a target of, as the type's own store or as its copy ([subscriptionOf]): `wow.snapshot` for the [default] store, and
 * `wow.snapshot:<store>` for each other one. A store keeps its subscription, and its checkpoints, whatever role it
 * plays for which type. The `snapshot` hop of a wait is the subscription of the type's own store; no caller spells a
 * store's subscription in a wait.
 */
@InternalWowApi
class SnapshotTargets(
    val default: String,
    val stores: Map<String, SnapshotStore>,
    placements: Map<AggregateType, SnapshotPlacement> = emptyMap(),
    val fallback: SnapshotPlacement = SnapshotPlacement(default),
) {
    private val placements: Map<AggregateType, SnapshotPlacement> = placements.toMap()

    init {
        require(default in stores) { "The default snapshot store $default is not one of ${stores.keys}." }
        val missing = (this.placements.values + fallback).flatMap { it.targets }.filter { it !in stores }.distinct()
        require(missing.isEmpty()) { "The snapshot targets $missing name no snapshot store of ${stores.keys}." }
    }

    /** Where [type]'s snapshots are written. */
    fun placementOf(type: AggregateType): SnapshotPlacement = placements[type] ?: fallback

    /** The store [type]'s snapshots are read from by its commands and its state reads: its own. */
    fun primaryOf(type: AggregateType): SnapshotStore = stores.getValue(placementOf(type).store)

    /** The snapshots each type's own store keeps: what the kernel and the state readers read. */
    val primary: SnapshotStore = SnapshotStoreRouter.of(
        stores.getValue(fallback.store),
        this.placements.mapValues { (_, placement) -> stores.getValue(placement.store) },
    )

    /**
     * The subscription that writes the snapshots [store] keeps, own or copied: `wow.snapshot` for the [default] store,
     * `wow.snapshot:<store>` for another.
     */
    fun subscriptionOf(store: String): String =
        if (store == default) SystemSubscriptions.SNAPSHOT else "${SystemSubscriptions.SNAPSHOT}:$store"

    /** The subscription of [type]'s own store: the one whose handling ends a wait's `snapshot` hop. */
    fun primarySubscriptionOf(type: AggregateType): String = subscriptionOf(placementOf(type).store)

    /** The subscription that writes the store [type]'s queries read: its copy's when it has one, else its own's. */
    fun querySubscriptionOf(type: AggregateType): String = subscriptionOf(placementOf(type).query)

    /**
     * The stores that are a target of one of [types], each with the types it keeps, own or copied, in the order of
     * [stores]: what the maintenance claims and rebuilds.
     */
    fun storesOf(types: Collection<AggregateType>): Map<String, Set<AggregateType>> {
        val byStore = LinkedHashMap<String, MutableSet<AggregateType>>()
        stores.keys.forEach { store ->
            types.filter { store in placementOf(it).targets }.takeIf { it.isNotEmpty() }?.let {
                byStore[store] = it.toCollection(LinkedHashSet())
            }
        }
        return byStore
    }

    @InternalWowApi
    companion object {
        /** The one snapshot [store] of a context, named [name]: every type's only target. */
        @JvmStatic
        fun single(store: SnapshotStore, name: String = DEFAULT_STORE): SnapshotTargets =
            SnapshotTargets(name, mapOf(name to store))

        /** The name of the default store when the host names none. */
        const val DEFAULT_STORE: String = "default"
    }
}

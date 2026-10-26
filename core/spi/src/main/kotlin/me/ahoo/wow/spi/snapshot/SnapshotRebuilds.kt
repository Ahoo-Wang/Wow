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

package me.ahoo.wow.spi.snapshot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The system command `wow.snapshot.Rebuild` (decision D172): rebuilds from the log the snapshots of the aggregates
 * [ids] of one aggregate type, in the tenant of the instance it is sent to: the aggregate `<context>.snapshot` whose id
 * is the name of that aggregate type. A snapshot written wrong is repaired this way; the rebuild runs in the background,
 * once the request is committed.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
@InternalWowApi
class RebuildSnapshot @InternalWowApi constructor(val ids: List<String>) {
    init {
        require(ids.isNotEmpty()) { "A rebuild names the aggregates whose snapshots it rebuilds." }
        require(ids.size <= SnapshotRebuilds.MAX_IDS) { "A rebuild names at most ${SnapshotRebuilds.MAX_IDS} ids." }
        require(ids.none { it.isBlank() }) { "An aggregate id is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RebuildSnapshot && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RebuildSnapshot(ids=$ids)"

    private fun valueFields(): List<Any?> = listOf(ids)

    companion object {
        /** A RebuildSnapshot of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(ids: List<String>): RebuildSnapshot = RebuildSnapshot(ids)
    }
}

/**
 * The system event `wow.SnapshotRebuildRequested`: the snapshots of the aggregates [ids] are to be rebuilt.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
@InternalWowApi
class SnapshotRebuildRequested @InternalWowApi constructor(val ids: List<String>) {
    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotRebuildRequested && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SnapshotRebuildRequested(ids=$ids)"

    private fun valueFields(): List<Any?> = listOf(ids)

    companion object {
        /** A SnapshotRebuildRequested of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(ids: List<String>): SnapshotRebuildRequested = SnapshotRebuildRequested(ids)
    }
}

/** The names of the snapshot rebuild aggregate, its system command and event (decision D172). */
@InternalWowApi
object SnapshotRebuilds {
    /**
     * The name of the built-in aggregate that takes a context's rebuild requests: `<context>.snapshot`, one instance
     * per aggregate type in each tenant, whose id is the aggregate type's name.
     */
    const val AGGREGATE: String = "snapshot"

    /** The component a rebuild runs as: `system:snapshot`. */
    const val COMPONENT: String = "snapshot"

    const val REBUILD: String = "wow.snapshot.Rebuild"
    const val REBUILD_REQUESTED: String = "wow.SnapshotRebuildRequested"

    /** The most ids one rebuild request names. */
    const val MAX_IDS: Int = 1_000

    /** The system commands, by their names. */
    @JvmField
    val COMMANDS: Map<String, Class<*>> = mapOf(REBUILD to RebuildSnapshot::class.java)

    /** The system events, by their names. */
    @JvmField
    val EVENTS: Map<String, Class<*>> = mapOf(REBUILD_REQUESTED to SnapshotRebuildRequested::class.java)

    private val NAMES: Map<Class<*>, String> = (COMMANDS + EVENTS).entries.associate { (name, type) -> type to name }

    /** The system name of [type], the rebuild command or event, or null when it is neither. */
    @JvmStatic
    fun nameOf(type: Class<*>): String? = NAMES[type]

    /** The rebuild aggregate of [context]. */
    @JvmStatic
    fun typeOf(context: String): AggregateType = AggregateType(context, AGGREGATE)
}

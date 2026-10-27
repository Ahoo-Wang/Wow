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

package me.ahoo.wow.migrate

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest

/**
 * [map] resolved against the V10 [manifest]: for each 9.x aggregate, the V10 aggregate type its streams go to and how
 * their commands, events and headers are named there. [MappingCheck] reports what does not resolve; the import runs
 * only on a plan whose check passed.
 */
internal class MigrationPlan(val map: MigrationMap, val manifest: Manifest) {
    /** The plans of the mapped 9.x aggregates. */
    val aggregates: Map<NineXAggregate, AggregatePlan> = map.aggregates.associate { mapping ->
        val dot = mapping.to.indexOf('.')
        val target = AggregateType(mapping.to.substring(0, dot), mapping.to.substring(dot + 1))
        mapping.source() to AggregatePlan(mapping, target, manifestOf(target), map.headers)
    }

    /**
     * The plans of the mapped 9.x `PrepareKey`s, by name: each with the aggregate whose instances own its keys.
     * A name mapped twice keeps its last mapping here; the mapping check reports it.
     */
    val reservations: Map<String, ReservationPlan> = aggregates.values.flatMap { aggregate ->
        aggregate.mapping.reservations.map { it.from to ReservationPlan(aggregate, it) }
    }.toMap()

    /** The 9.x compensation executions the map imports as V10 parked entries (.4). */
    val compensation: CompensationPlan = CompensationPlan(map.subscriptions, manifest, aggregates)

    /** The plan of [aggregate]; a [MigrationException] when the map does not name it. */
    fun of(aggregate: NineXAggregate): AggregatePlan =
        aggregates[aggregate] ?: throw MigrationException("migration-map.json does not map the 9.x aggregate $aggregate.")

    /** The V10 contexts the plan writes to. */
    val contexts: Set<String>
        get() = aggregates.values.map { it.target.context }.toSortedSet()

    private fun manifestOf(type: AggregateType): AggregateManifest? =
        manifest.contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }
}

/**
 * How the streams of one 9.x aggregate are imported as the V10 aggregate type [target]; [manifest] is its Manifest
 * entry, null when the Manifest has none (the check reports it).
 */
internal class AggregatePlan(
    val mapping: AggregateMapping,
    val target: AggregateType,
    val manifest: AggregateManifest?,
    val headers: Map<String, String>,
) {
    /** The 9.x aggregate. */
    val source: NineXAggregate = mapping.source()

    /** Who the aggregate belongs to besides its tenant in V10; a missing Manifest entry reads as OWNER. */
    val ownership: Ownership = when (manifest?.ownership) {
        me.ahoo.wow.manifest.Ownership.TENANT -> Ownership.TENANT
        else -> Ownership.OWNER
    }

    private val commands: Map<String, String> = mapping.commands.associate { it.from to fullName(it.to) }

    /** The mapping of a 9.x event by its name and revision: one of that exact revision first, then one of any. */
    fun eventOf(name: String, revision: String): EventMapping? =
        mapping.events.firstOrNull { it.from == name && it.fromRevision == revision }
            ?: mapping.events.firstOrNull { it.from == name && it.fromRevision == null }

    /**
     * The V10 command type of a stream whose 9.x command is [name] (null when it recorded none): the mapped name, or
     * the [AggregateMapping.defaultCommand] for a stream without one; null when neither applies.
     */
    fun commandTypeOf(name: String?): String? =
        if (name == null) mapping.defaultCommand?.let(::fullName) else commands[name]

    /** The full V10 name of a command or event the map names [name]: a simple name is in [target]'s namespace. */
    fun fullName(name: String): String = if ('.' in name) name else "$target.$name"

    /** Why the V10 command [name] the map gives is not in the Manifest; null when it is. */
    fun commandProblem(name: String): String? {
        val full = fullName(name)
        val manifest = manifest ?: return null
        if (full.startsWith(Lifecycle.NAMESPACE + ".")) {
            return if (full in manifest.lifecycle) null else "the lifecycle command $full is not enabled on $target"
        }
        val known = manifest.commands.any { command ->
            fullName(command.name) == full || command.aliases.any { fullName(it) == full }
        }
        return if (known) null else "the V10 command $full is not in the Manifest"
    }

    /**
     * Why the map's `retire` of an event under [name] does not hold: the aggregate does not list [name] among its
     * retired events; null when it does.
     */
    fun retiredProblem(name: String): String? {
        val full = fullName(name)
        val manifest = manifest ?: return null
        return if (manifest.retired.any { fullName(it) == full }) {
            null
        } else {
            "$full is retired by the map but not by $target: add it to @AggregateRoot(retiredEvents)"
        }
    }

    /**
     * Why the V10 event [name] of the revision [revision] the map gives is not readable; null when it is, or when it
     * is an event the aggregate retired (its records are skipped).
     */
    fun eventProblem(name: String, revision: Int): String? {
        val full = fullName(name)
        val manifest = manifest ?: return null
        // A record under a retired name is kept in the log and skipped when read.
        if (manifest.retired.any { fullName(it) == full }) {
            return null
        }
        if ('.' in name) {
            return when {
                full !in Lifecycle.EVENTS -> "the V10 event $full is not a system event"
                revision != 1 -> "the system event $full has revision 1 only, not $revision"
                else -> null
            }
        }
        val event = manifest.events.firstOrNull { event ->
            fullName(event.name) == full || event.aliases.any { fullName(it) == full }
        } ?: return "the V10 event $full is not in the Manifest"
        return if (revision > event.revision) {
            "the V10 event $full is at revision ${event.revision} in the Manifest, below the mapped revision $revision"
        } else {
            null
        }
    }
}

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

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.Lifecycle

/** A 9.x event type as streams record it: its name and 9.x revision. */
internal data class NineXEventType(val name: String, val revision: String) {
    override fun toString(): String = "$name@$revision"
}

/**
 * What the 9.x store holds of [aggregate], found by scanning it: how many [streams], the [events] of each type and the
 * [commands] of each name (the number of streams each produced), how many streams record no command name, and
 * [streamEventTypes]: the number of streams by the set of event types each holds, which tells the streams a mapping
 * would leave empty.
 */
internal data class NineXInventory(
    val aggregate: NineXAggregate,
    val streams: Long,
    val events: Map<NineXEventType, Long>,
    val commands: Map<String, Long>,
    val streamsWithoutCommand: Long = 0,
    val streamEventTypes: Map<Set<NineXEventType>, Long> = emptyMap(),
)

/**
 * The outcome of [MappingCheck]: [problems] stop the import (principle 5: one missing entry and it does
 * not start); [warnings] do not.
 */
internal data class MappingReport(
    val problems: List<String>,
    val warnings: List<String>,
    val inventories: List<NineXInventory>,
    val prepareKeys: Map<String, Long> = emptyMap(),
    val compensation: NineXCompensationInventory? = null,
) {
    /** Whether the import may start. */
    val passed: Boolean get() = problems.isEmpty()

    /** The report as text, for the command line. */
    fun render(): String = buildString {
        appendLine("9.x store:")
        if (inventories.isEmpty()) {
            appendLine("  no event streams")
        }
        inventories.forEach { inventory ->
            appendLine(
                "  ${inventory.aggregate}: ${inventory.streams} streams, " +
                    "${inventory.events.values.sum()} events of ${inventory.events.size} types",
            )
        }
        prepareKeys.forEach { (name, keys) -> appendLine("  PrepareKey $name: $keys keys") }
        compensation?.let { inventory ->
            appendLine(
                "  9.x compensation: ${inventory.unresolved.sumOf { it.executions }} unresolved executions of " +
                    "${inventory.unresolved.map { it.function }.distinct().size} functions, ${inventory.resolved} " +
                    "resolved (history, not imported)",
            )
        }
        problems.forEach { appendLine("PROBLEM $it") }
        warnings.forEach { appendLine("WARNING $it") }
        append(if (passed) "Mapping check passed." else "Mapping check failed: ${problems.size} problems.")
    }
}

/**
 * Checks `migration-map.json` against the event types the 9.x store actually holds and the V10 Manifest
 * (principle 5), before anything is imported:
 *
 * - every 9.x aggregate the store holds is mapped, and to a V10 aggregate type of the Manifest, no two to the same;
 * - every 9.x event (name and revision) the store holds has a mapping, which drops it, retires it under a name the
 *   aggregate retired, or names a V10 event the Manifest reads at the mapped revision (its current revision or an
 *   older one its upgrades read);
 * - no stream holds only dropped events, which would leave its version empty (map them with `retire` instead);
 * - every 9.x command name the store records maps to a V10 command of the aggregate, and streams that record none
 *   have a default command;
 * - every mapping target is in the Manifest, whether or not the store uses it;
 * - every 9.x `PrepareKey` the store holds ([prepareKeys], keys by name) is mapped to a V10 reservation namespace, once;
 * - every 9.x processor function with unresolved executions in the 9.x compensation service ([compensation]), of a
 *   context this map migrates, maps to a V10 subscription of the Manifest, each processor once (.4); an
 *   execution whose subscription cannot park its aggregate (not imported by this map, or not read from the log) is a
 *   warning: it is reported and not imported.
 *
 * Mappings the store does not use are warnings: the Skill may map an event no stream holds yet.
 */
internal class MappingCheck(private val plan: MigrationPlan) {
    fun check(
        inventories: List<NineXInventory>,
        prepareKeys: Map<String, Long> = emptyMap(),
        compensation: NineXCompensationInventory? = null,
    ): MappingReport {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        plan.aggregates.values.groupBy { it.target }.filterValues { it.size > 1 }.forEach { (target, plans) ->
            problems += "${plans.map { it.source }} all map to $target: their ids may collide"
        }
        plan.aggregates.values.forEach { aggregate -> problems += targetProblems(aggregate) }
        val held = inventories.associateBy { it.aggregate }
        inventories.filter { it.aggregate !in plan.aggregates }.forEach {
            problems += "the 9.x aggregate ${it.aggregate} (${it.streams} streams) is not mapped"
        }
        plan.aggregates.values.forEach { aggregate ->
            val inventory = held[aggregate.source]
            if (inventory == null) {
                warnings += "the 9.x store holds no stream of ${aggregate.source}"
            } else {
                storeProblems(aggregate, inventory, problems, warnings)
            }
        }
        reservationProblems(prepareKeys, problems, warnings)
        compensationProblems(compensation, problems, warnings)
        return MappingReport(problems, warnings, inventories, prepareKeys, compensation)
    }

    private fun compensationProblems(
        inventory: NineXCompensationInventory?,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val compensation = plan.compensation
        compensation.mappings.groupBy { it.source() }.filterValues { it.size > 1 }.keys
            .forEach { problems += "the 9.x processor $it is mapped twice" }
        compensation.mappings.forEach { mapping ->
            mapping.functions.keys.map(mapping::subscriptionOf).filter { compensation.manifestOf(it) == null }
                .forEach { problems += "${mapping.from}: the V10 subscription $it is not in the Manifest" }
        }
        if (inventory == null) {
            if (compensation.mappings.isNotEmpty()) {
                warnings += "the configuration names no 9.x compensation database: the subscription mappings are unused"
            }
            return
        }
        val groups = inventory.unresolved.filter { compensation.covers(it.function) }
        functionProblems(groups, problems, warnings)
        unusedSubscriptionMappings(groups.map { it.function }.toSet(), warnings)
    }

    /** What the unresolved executions of each 9.x function in [groups] lack to be imported. */
    private fun functionProblems(
        groups: List<NineXFailureGroup>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val compensation = plan.compensation
        groups.groupBy { it.function }.forEach { (function, onAggregates) ->
            val executions = onAggregates.sumOf { it.executions }
            val subscription = compensation.subscriptionOf(function)
            when {
                subscription == null ->
                    problems += "the 9.x processor function $function ($executions unresolved " +
                        "executions) has no subscription mapping: its failures would be lost"

                compensation.manifestOf(subscription) == null ->
                    problems += "the 9.x processor function $function " +
                        "($executions unresolved executions) maps to the V10 subscription $subscription, which is not in " +
                        "the Manifest"

                else -> onAggregates.forEach { group ->
                    compensation.problemOf(subscription, group.aggregate)?.let {
                        warnings += "${group.executions} unresolved executions of $function on ${group.aggregate} " +
                            "will not be imported: $it"
                    }
                }
            }
        }
    }

    /** The subscription mappings no unresolved execution of [functions] uses. */
    private fun unusedSubscriptionMappings(functions: Set<NineXFunction>, warnings: MutableList<String>) {
        plan.compensation.mappings.forEach { mapping ->
            val processor = mapping.source()
            if (functions.none { it.owner == processor }) {
                warnings += "no unresolved 9.x execution is of the processor $processor"
            } else {
                mapping.functions.keys.filter { NineXFunction(processor.context, processor.processor, it) !in functions }
                    .forEach { warnings += "no unresolved 9.x execution is of the processor function $processor.$it" }
            }
        }
    }

    private fun reservationProblems(
        prepareKeys: Map<String, Long>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val mappings = plan.aggregates.values.flatMap { aggregate -> aggregate.mapping.reservations.map { it.from } }
        mappings.groupBy { it }.filterValues { it.size > 1 }.keys
            .forEach { problems += "the 9.x PrepareKey $it is mapped twice" }
        prepareKeys.filterKeys { it !in mappings }.forEach { (name, keys) ->
            problems += "the 9.x PrepareKey $name ($keys keys) has no reservation mapping: its keys would be free in V10"
        }
        mappings.distinct().filter { it !in prepareKeys }
            .forEach { warnings += "the 9.x store holds no PrepareKey $it" }
    }

    private fun targetProblems(aggregate: AggregatePlan): List<String> {
        val subject = "${aggregate.source} -> ${aggregate.target}"
        if (aggregate.manifest == null) {
            return listOf("$subject: the V10 aggregate ${aggregate.target} is not in the Manifest")
        }
        val mapping = aggregate.mapping
        val duplicates = mapping.events.groupBy { it.from to it.fromRevision }.filterValues { it.size > 1 }.keys
            .map { (name, revision) -> "$subject: the 9.x event $name@${revision ?: "*"} is mapped twice" } +
            mapping.commands.groupBy { it.from }.filterValues { it.size > 1 }.keys
                .map { "$subject: the 9.x command $it is mapped twice" }
        val commands = (mapping.commands.map { it.to } + listOfNotNull(mapping.defaultCommand))
            .mapNotNull { aggregate.commandProblem(it) }
        val events = mapping.events.mapNotNull { event ->
            event.to?.let {
                if (event.retire) aggregate.retiredProblem(it) else aggregate.eventProblem(it, event.toRevision)
            }?.let { "${event.from}: $it" }
        } + mapping.events.filter {
            it.lifecycle?.to == Lifecycle.OWNER_TRANSFERRED && aggregate.ownership == Ownership.TENANT
        }.map { "${it.from}: writes ${Lifecycle.OWNER_TRANSFERRED}, but the V10 aggregate belongs to its tenant" }
        return duplicates + (commands + events).map { "$subject: $it" }
    }

    private fun storeProblems(
        aggregate: AggregatePlan,
        inventory: NineXInventory,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val subject = aggregate.source
        inventory.events.forEach { (type, count) ->
            if (aggregate.eventOf(type.name, type.revision) == null) {
                problems += "$subject: the 9.x event $type ($count events) has no mapping"
            }
        }
        inventory.commands.forEach { (name, count) ->
            if (aggregate.commandTypeOf(name) == null) {
                problems += "$subject: the 9.x command $name ($count streams) has no mapping"
            }
        }
        // A stream whose every event is dropped cannot be imported: its version would be empty.
        inventory.streamEventTypes
            .filterKeys { types ->
                types.isNotEmpty() && types.all { aggregate.eventOf(it.name, it.revision)?.drop == true }
            }
            .forEach { (types, count) ->
                problems += "$subject: $count streams hold only the dropped events " +
                    "${types.map { it.toString() }.sorted()}, so their versions would be empty: map them with " +
                    "retire to a name in the aggregate's retiredEvents"
            }
        if (inventory.streamsWithoutCommand > 0 && aggregate.commandTypeOf(null) == null) {
            problems += "$subject: ${inventory.streamsWithoutCommand} streams record no command and there is no " +
                "defaultCommand"
        }
        aggregate.mapping.events
            .filter { mapping ->
                inventory.events.keys.none { aggregate.eventOf(it.name, it.revision) == mapping }
            }
            .forEach { warnings += "$subject: no stream holds the 9.x event ${it.from}@${it.fromRevision ?: "*"}" }
        aggregate.mapping.commands.filter { it.from !in inventory.commands }
            .forEach { warnings += "$subject: no stream records the 9.x command ${it.from}" }
    }
}

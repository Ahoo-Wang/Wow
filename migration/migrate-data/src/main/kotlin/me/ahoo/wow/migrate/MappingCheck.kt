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

/** A 9.x event type as streams record it: its name and 9.x revision. */
data class NineXEventType(val name: String, val revision: String) {
    override fun toString(): String = "$name@$revision"
}

/**
 * What the 9.x store holds of [aggregate], found by scanning it: how many [streams], the [events] of each type and the
 * [commands] of each name (the number of streams each produced), and how many streams record no command name.
 */
data class NineXInventory(
    val aggregate: NineXAggregate,
    val streams: Long,
    val events: Map<NineXEventType, Long>,
    val commands: Map<String, Long>,
    val streamsWithoutCommand: Long = 0,
)

/**
 * The outcome of [MappingCheck]: [problems] stop the import (design 07 §1 principle 5: one missing entry and it does
 * not start); [warnings] do not.
 */
data class MappingReport(
    val problems: List<String>,
    val warnings: List<String>,
    val inventories: List<NineXInventory>,
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
        problems.forEach { appendLine("PROBLEM $it") }
        warnings.forEach { appendLine("WARNING $it") }
        append(if (passed) "Mapping check passed." else "Mapping check failed: ${problems.size} problems.")
    }
}

/**
 * Checks `migration-map.json` against the event types the 9.x store actually holds and the V10 Manifest (design 07
 * §1 principle 5), before anything is imported:
 *
 * - every 9.x aggregate the store holds is mapped, and to a V10 aggregate type of the Manifest, no two to the same;
 * - every 9.x event (name and revision) the store holds has a mapping, which drops it or names a V10 event the
 *   Manifest reads at the mapped revision (its current revision or an older one its upgrades read);
 * - every 9.x command name the store records maps to a V10 command of the aggregate, and streams that record none
 *   have a default command;
 * - every mapping target is in the Manifest, whether or not the store uses it.
 *
 * Mappings the store does not use are warnings: the Skill may map an event no stream holds yet.
 */
class MappingCheck(private val plan: MigrationPlan) {
    fun check(inventories: List<NineXInventory>): MappingReport {
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
        return MappingReport(problems, warnings, inventories)
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
            event.to?.let { aggregate.eventProblem(it, event.toRevision) }?.let { "${event.from}: $it" }
        }
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

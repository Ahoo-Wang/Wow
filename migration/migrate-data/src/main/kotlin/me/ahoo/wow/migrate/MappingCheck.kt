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
import me.ahoo.wow.manifest.AccessAttributeManifest

/** A 9.x event type as streams record it: its name and 9.x revision. */
internal data class NineXEventType(val name: String, val revision: String) {
    override fun toString(): String = "$name@$revision"
}

/**
 * What the 9.x store holds of [aggregate], found by scanning it: how many [streams], the [events] of each type and the
 * [commands] of each name (the number of streams each produced), how many streams record no command name,
 * [streamEventTypes]: the number of streams by the set of event types each holds, which tells the streams a mapping
 * would leave empty, [refusedIds]: the number of streams of each instance whose id V10 refuses ([RefusedIds]), and
 * [tenantless]: the number of streams of each 9.x aggregate id that record no tenant, which V10 refuses too.
 */
internal data class NineXInventory(
    val aggregate: NineXAggregate,
    val streams: Long,
    val events: Map<NineXEventType, Long>,
    val commands: Map<String, Long>,
    val streamsWithoutCommand: Long = 0,
    val streamEventTypes: Map<Set<NineXEventType>, Long> = emptyMap(),
    val refusedIds: Map<NineXAggregateId, Long> = emptyMap(),
    val tenantless: Map<String, Long> = emptyMap(),
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
 * - no instance the map imports has an id V10 refuses ([RefusedIds]), or streams that record no tenant: the problems
 *   list each, by the name the map excludes it by ([Exclusion.stream]);
 * - every mapping target is in the Manifest, whether or not the store uses it;
 * - every 9.x `PrepareKey` the store holds ([prepareKeys], keys by name) is mapped to a V10 reservation namespace, once;
 * - every 9.x processor function with unresolved executions in the 9.x compensation service ([compensation]), of a
 *   context this map migrates, maps to a V10 subscription of the Manifest, each processor once (.4); an
 *   execution whose subscription cannot park its aggregate (not imported by this map, or not read from the log) is a
 *   warning: it is reported and not imported.
 *
 * An aggregate whose 9.x snapshots mark instances deleted ([check]'s `deleted`) while no event of the map deletes in
 * V10 ([AggregatePlan.deletes]) is a warning: a deletion mapped otherwise, retired say, leaves them alive.
 *
 * A collection the tail reads by commit time without its index ([commitTimeIndexes], each with the command that
 * creates it) is a warning: the tail refuses to start without it, and creating it writes to the 9.x store, so it is
 * created on the 9.x side before the cut-over. A collection without the `(aggregateId, version)` index the import
 * walks it in ([walkIndexes]), which 9.x creates unique, is a problem: the import refuses it.
 *
 * Mappings the store does not use are warnings: the Skill may map an event no stream holds yet. An aggregate, an
 * instance's streams or a `PrepareKey` the map excludes ([MigrationMap.excluded]) is a warning with its reason instead
 * of a problem; one the map also maps, or excludes twice, is a problem, and one the store does not hold a warning, as
 * is an instance's streams of an aggregate the map does not import. Whether the store holds an instance whose id V10
 * takes is asked of [NineXEventSource.tenantsOf] ([check]'s `tenantsOf`).
 */
internal class MappingCheck(private val plan: MigrationPlan) {
    fun check(
        inventories: List<NineXInventory>,
        prepareKeys: Map<String, Long> = emptyMap(),
        compensation: NineXCompensationInventory? = null,
        tenantsOf: (NineXAggregate, String) -> List<String> = { _, _ -> emptyList() },
        commitTimeIndexes: Map<String, String> = emptyMap(),
        deleted: (NineXAggregate) -> Long = { 0 },
        walkIndexes: Map<String, String> = emptyMap(),
        tags: TagReader = TagReader.NONE,
    ): MappingReport {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        plan.aggregates.values.groupBy { it.target }.filterValues { it.size > 1 }.forEach { (target, plans) ->
            problems += "${plans.map { it.source }} all map to $target: their ids may collide"
        }
        plan.aggregates.values.forEach { aggregate -> problems += targetProblems(aggregate) }
        val held = inventories.associateBy { it.aggregate }
        val excluded = plan.map.excluded.associateBy { it.subject() }
        inventories.filter { it.aggregate !in plan.aggregates }.forEach {
            val subject = "9.x aggregate ${it.aggregate}"
            excluded[subject]?.let { exclusion ->
                warnings += "the $subject (${it.streams} streams) is excluded: ${exclusion.reason}"
            } ?: run { problems += "the $subject (${it.streams} streams) is not mapped" }
        }
        exclusionProblems(held, prepareKeys.keys, tenantsOf, problems, warnings)
        plan.aggregates.values.forEach { aggregate ->
            val inventory = held[aggregate.source]
            if (inventory == null) {
                warnings += "the 9.x store holds no stream of ${aggregate.source}"
            } else {
                storeProblems(aggregate, inventory, problems, warnings)
                deletionWarning(aggregate, deleted)?.let { warnings += it }
                TagCheck(aggregate, tags, problems, warnings).check()
            }
        }
        reservationProblems(prepareKeys, problems, warnings)
        compensationProblems(compensation, problems, warnings)
        plan.map.exceptions.forEach { warnings += "the draft's exception, for a person to resolve and delete: $it" }
        commitTimeIndexes.forEach { (collection, create) ->
            warnings += "the 9.x collection $collection has no (createTime, _id) index, which the tail reads it by and " +
                "refuses to start without: create it on the 9.x side before the cut-over, $create, or set " +
                "\"source\": { \"createCommitTimeIndex\": true } to let the tail create it, a write to the 9.x store"
        }
        walkIndexes.forEach { (collection, create) ->
            problems += "the 9.x collection $collection has no (aggregateId, version) index, which 9.x creates and " +
                "the import walks it in: create it on the 9.x side, $create"
        }
        return MappingReport(problems, warnings, inventories, prepareKeys, compensation)
    }

    /**
     * The warning of an [aggregate] whose 9.x snapshots mark instances [deleted] when the map deletes none
     * ([AggregatePlan.deletes]): V10 would hold them alive. Null when the map deletes, or no snapshot marks one.
     */
    /**
     * The check of one [aggregate]'s 9.x tags against its [AggregateMapping.tags], from what [reader] counts in the
     * store: every key the store holds is mapped or left out for a reason; each is mapped as its source (a key no tags
     * event wrote was derived); each attribute is declared, and holds the values the store has; the tags event writes
     * them ([EventMapping.tags]).
     */
    private class TagCheck(
        private val aggregate: AggregatePlan,
        private val reader: TagReader,
        private val problems: MutableList<String>,
        private val warnings: MutableList<String>,
    ) {
        private val subject = aggregate.source
        private val mapping = aggregate.mapping
        private val tags = reader.tags(subject)

        fun check() {
            tags.events.forEach { (name, count) -> eventProblem(name, count) }
            (tags.applied.keys + tags.snapshots.keys).forEach(::keyProblem)
            mapping.tags.forEach(::attributeProblem)
            mapping.tags.filter { it.key !in tags.applied && it.key !in tags.snapshots }.forEach {
                warnings += "$subject: no tags event or snapshot holds the 9.x tag key ${it.key}"
            }
        }

        /** The 9.x event [name], [count] of which hold tags: the tags event writes them, so maps with tags. */
        private fun eventProblem(name: String, count: Long) {
            val event = mapping.events.firstOrNull { it.from == name } ?: return // Its own problem: it has no mapping.
            when {
                event.tags -> Unit
                event.drop && mapping.tags.any { it.written } ->
                    problems += "$subject: the 9.x event $name ($count events) writes tags and is dropped, so the " +
                        "attributes ${mapping.tags.filter { it.written }.map { it.to }} would be lost: map it to the " +
                        "V10 event that sets them, with \"tags\": true"

                name == MapDrafter.TAGS_EVENT ->
                    problems += "$subject: the 9.x tags event $name ($count events) is mapped without \"tags\": true, " +
                        "so the tags it wrote would not reach the V10 attributes"

                else ->
                    warnings += "$subject: the 9.x event $name ($count events) holds a tags object: if it " +
                        "implements ResourceTagsApplied, map it with \"tags\": true"
            }
        }

        /** The 9.x tag [key] the store holds: mapped as where its values come from, or left out for a reason. */
        private fun keyProblem(key: String) {
            val applied = tags.applied[key]
            val held = tags.snapshots[key]
            val where = listOfNotNull(applied?.let { "${it.count} tags events" }, held?.let { "${it.count} snapshots" })
                .joinToString(" and ")
            val tag = mapping.tagOf(key)
            when {
                tag == null ->
                    problems += "$subject: the 9.x tag key $key ($where) has no tag mapping: map it to the " +
                        "V10 access attribute it becomes (source ${if (applied == null) TagSource.DERIVED else TagSource.APPLIED}), " +
                        "or leave it out with a reason"

                tag.to == null -> warnings += "$subject: the 9.x tag key $key ($where) is left out: ${tag.reason}"
                tag.source == TagSource.DERIVED && applied != null ->
                    problems += "$subject: the 9.x tag key $key is mapped as ${TagSource.DERIVED}, but ${applied.count} " +
                        "tags events wrote it: map it as ${TagSource.BOTH} (V10 derives it, and leaves the values they " +
                        "wrote out) or ${TagSource.APPLIED}"

                tag.source == TagSource.APPLIED && applied == null ->
                    problems += "$subject: the 9.x tag key $key is mapped as ${TagSource.APPLIED}, but no tags event " +
                        "wrote it: the ${held!!.count} snapshots that hold it had it derived by the 9.x state, so map it as " +
                        "${TagSource.DERIVED}"

                tag.source == TagSource.BOTH && applied != null ->
                    warnings += "$subject: the 9.x tag key $key is applied and derived: V10 derives ${tag.to} with an " +
                        "@Access function, and the values ${applied.count} tags events wrote are left out of their events"
            }
        }

        /**
         * The problems of [tag]'s attribute: declared by the V10 aggregate, and able to hold the values the store
         * holds. The tags events' values of an applied key are problems, since the import writes them. The snapshots'
         * values of a derived key are warnings: V10 derives the attribute and the import writes none of them, but
         * 9.x merged what its state derived into the tags it kept, so a snapshot can hold a key's former values
         * beside its current one, which compare then reports.
         */
        private fun attributeProblem(tag: TagMapping) {
            val to = tag.to ?: return
            val attribute = aggregate.accessOf(to)
            if (attribute == null) {
                problems += "$subject: the 9.x tag key ${tag.key} maps to $to, which ${aggregate.target} does not " +
                    "declare as an access attribute (@Access)"
                return
            }
            val snapshots = tag.source != TagSource.APPLIED
            val stats = (if (snapshots) tags.snapshots else tags.applied)[tag.key] ?: return
            val records = if (snapshots) "snapshots" else "tags events"
            TagValues.problems(attribute, stats).forEach { (problem, count) ->
                val samples = reader.samples(subject, tag.key, problem, snapshots)
                val kind = if (snapshots) "instances" else "streams"
                val found = "$subject: the 9.x tag key ${tag.key}, mapped to the ${describe(attribute)} attribute $to, " +
                    "holds ${valuesOf(problem)} in $count $records" +
                    (if (samples.isEmpty()) "" else " ($kind ${samples.joinToString()})")
                if (snapshots) {
                    warnings += "$found: V10 derives $to from the state, so the import writes none of them, and " +
                        "compare reports these instances as ${StateComparison.ACCESS}.$to differences"
                } else {
                    problems += found
                }
            }
        }

        private fun describe(attribute: AccessAttributeManifest): String =
            (if (attribute.multiple) "multiple " else "single ") + attribute.type.name.lowercase()

        private fun valuesOf(problem: TagValueProblem): String = when (problem) {
            TagValueProblem.MULTIPLE -> "two values or more"
            TagValueProblem.NOT_INTEGER -> "a value that is no integer"
            TagValueProblem.NOT_BOOLEAN -> "a value that is not true or false"
        }
    }

    private fun deletionWarning(aggregate: AggregatePlan, deleted: (NineXAggregate) -> Long): String? {
        if (aggregate.deletes) {
            return null
        }
        val count = deleted(aggregate.source).takeIf { it > 0 } ?: return null
        return "${aggregate.source}: $count 9.x snapshots mark their instance deleted, but no event of the map " +
            "deletes in V10, so V10 holds them alive: map the 9.x event that deleted them with \"lifecycle\": " +
            "{ \"to\": \"${Lifecycle.AGGREGATE_DELETED}\" }, or to ${Lifecycle.AGGREGATE_DELETED} when its class is gone"
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
            mapping.functions.keys.map(mapping::subscriptionOf).filterNot(compensation::declares)
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

                !compensation.declares(subscription) ->
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

    /**
     * The excluded entries the map maps too, or excludes twice, are problems; one the store does not hold is a
     * warning. An instance's streams are a warning whatever they are ([streamWarning]).
     */
    private fun exclusionProblems(
        held: Map<NineXAggregate, NineXInventory>,
        prepareKeys: Set<String>,
        tenantsOf: (NineXAggregate, String) -> List<String>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val excluded = plan.map.excluded
        excluded.groupBy { it.subject() }.filterValues { it.size > 1 }.keys
            .forEach { problems += "the $it is excluded twice" }
        val reserved = plan.aggregates.values.flatMap { aggregate -> aggregate.mapping.reservations.map { it.from } }
        excluded.distinctBy { it.subject() }.forEach { exclusion ->
            val stream = exclusion.excludedStream()
            if (stream != null) {
                warnings += streamWarning(exclusion, stream.first, RefusedIds.idOf(stream.second), held, tenantsOf)
                return@forEach
            }
            val aggregate = exclusion.source()
            val (mapped, holds) = if (aggregate != null) {
                (aggregate in plan.aggregates) to (aggregate in held.keys)
            } else {
                (exclusion.prepareKey in reserved) to (exclusion.prepareKey in prepareKeys)
            }
            when {
                mapped -> problems += "the ${exclusion.subject()} is both mapped and excluded"
                !holds -> warnings += "the 9.x store holds no ${exclusion.subject().removePrefix("9.x ")}, which " +
                    "the map excludes"
            }
        }
    }

    /**
     * The warning of [exclusion], the streams of [aggregate]'s instance [id]: excluded with its reason, with the
     * tenants that hold it; or why it leaves nothing out, the store holding no such instance or the map not importing
     * [aggregate]. The instances whose id V10 refuses are the inventory's; whether the store holds another is
     * [tenantsOf]'s.
     */
    private fun streamWarning(
        exclusion: Exclusion,
        aggregate: NineXAggregate,
        id: String,
        held: Map<NineXAggregate, NineXInventory>,
        tenantsOf: (NineXAggregate, String) -> List<String>,
    ): String {
        if (aggregate !in plan.aggregates) {
            return "the ${exclusion.subject()} is excluded, but the map does not import $aggregate"
        }
        val tenants = if (RefusedIds.reasonOf(id) != null) {
            held[aggregate]?.refusedIds.orEmpty().keys.filter { it.id == id }.map { it.tenantId }
        } else {
            tenantsOf(aggregate, id)
        }
        return if (tenants.isEmpty()) {
            "the 9.x store holds no ${exclusion.subject().removePrefix("9.x ")}, which the map excludes"
        } else {
            val held = RefusedIds.tenantsText(tenants)
            "the ${exclusion.subject()} of the tenants $held is excluded: ${exclusion.reason}"
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
        val excluded = plan.map.excluded.associateBy { it.subject() }
        prepareKeys.filterKeys { it !in mappings }.forEach { (name, keys) ->
            val exclusion = excluded["9.x PrepareKey $name"]
            exclusion?.let { warnings += "the 9.x PrepareKey $name ($keys keys) is excluded: ${it.reason}" }
                ?: run {
                    problems += "the 9.x PrepareKey $name ($keys keys) has no reservation mapping: its keys would be " +
                        "free in V10"
                }
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
        val refused = inventory.refusedIds.filterKeys { !plan.excludes(subject, it.id) }
        if (refused.isNotEmpty()) {
            problems += refusedProblem(subject, refused)
        }
        val tenantless = inventory.tenantless.filterKeys { !plan.excludes(subject, it) }
        if (tenantless.isNotEmpty()) {
            problems += tenantlessProblem(subject, tenantless)
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

    /**
     * The problem of the instances of [aggregate] whose id V10 refuses, with the number of streams of each, which the
     * map does not exclude: each id by the name the map excludes it by, so a person can copy it.
     */
    private fun refusedProblem(aggregate: NineXAggregate, refused: Map<NineXAggregateId, Long>): String {
        val listed = refused.entries.groupBy { it.key.id }.toSortedMap().map { (id, instances) ->
            val tenants = RefusedIds.tenantsText(instances.map { it.key.tenantId })
            "${RefusedIds.quoted(RefusedIds.nameOf(id))} (${RefusedIds.reasonOf(id)}: " +
                "${instances.sumOf { it.value }} streams of the tenants $tenants)"
        }
        return "$aggregate: V10 refuses these 9.x aggregate ids, so the streams of their instances cannot be " +
            "imported; exclude each with {\"aggregate\": \"$aggregate\", \"stream\": <the id as listed>, " +
            "\"reason\": \"…\"}: ${listed.joinToString(", ")}"
    }

    /**
     * The problem of the 9.x aggregate ids of [aggregate] with streams that record no tenant, the number of streams of
     * each, which the map does not exclude: each by the name the map excludes it by.
     */
    private fun tenantlessProblem(aggregate: NineXAggregate, tenantless: Map<String, Long>): String {
        val listed = tenantless.toSortedMap().map { (id, streams) ->
            val name = RefusedIds.quoted(RefusedIds.nameOf(id))
            "$name ($streams streams)"
        }
        return "$aggregate: streams of these 9.x aggregate ids record no tenant, which V10 refuses, so they cannot be " +
            "imported; exclude each with {\"aggregate\": \"$aggregate\", \"stream\": <the id as listed>, " +
            "\"reason\": \"…\"}: ${listed.joinToString(", ")}"
    }
}

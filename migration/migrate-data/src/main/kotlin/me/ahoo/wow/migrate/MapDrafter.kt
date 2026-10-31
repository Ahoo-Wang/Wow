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

import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.spi.json.WowJson

/**
 * Drafts `migration-map.json` from what the 9.x store holds and the V10 Manifest (`migrate-data draft-map`): every
 * 9.x aggregate, event and command whose V10 counterpart its name gives, and, as [MapDraft.exceptions] for a person,
 * everything it does not.
 *
 * Names are matched, most certain first:
 *
 * 1. **A 9.x system name** of the 9.x lifecycle ([SYSTEM_EVENTS], [SYSTEM_COMMANDS]) maps to its V10 lifecycle name.
 * 2. **Exactly**: the 9.x name is the V10 name, one of its aliases, its full name (`context.aggregate.Name`) or its
 *    class's qualified name, which the Manifest's schema reference gives.
 * 3. **By the words**: the 9.x name's last segment and the V10 name compared without case, `_`, `-` and `.`, so
 *    9.x's `snake_case` (`order_created`, its default) and `kebab-case` match the class's `OrderCreated`.
 *
 * At each step, one candidate is a match and several are ambiguous; none goes on to the next step. An event the V10
 * aggregate retired matches as a retired one (`retire`). A mounted command is no candidate: a stream records the
 * command it was turned into.
 *
 * A 9.x context matches the V10 context of its name, or of its name without a `service` suffix (`order-service` is
 * `order`), or the Manifest's only context. A 9.x aggregate matches the aggregate of its name there; one that does
 * not, renamed in V10, matches the aggregate whose events hold at least half of its own, when exactly one does best.
 *
 * Nothing it cannot match is guessed: the draft leaves it out, so `check` refuses the map until a person maps it, and
 * lists it as an exception.
 */
internal class MapDrafter(private val manifest: Manifest) {
    /** The draft for [inventories], the 9.x store's aggregates, and its [prepareKeys]. */
    fun draft(inventories: List<NineXInventory>, prepareKeys: Map<String, Long> = emptyMap()): MapDraft {
        val exceptions = mutableListOf<String>()
        val events = inventories.groupBy { it.aggregate }
            .mapValues { (_, held) -> held.flatMap { it.events.keys }.map { it.name }.toSet() }
        val targets = targetsOf(events.toSortedMap(compareBy { it.toString() }), exceptions)
        val aggregates = inventories.sortedBy { it.aggregate.toString() }.mapNotNull { inventory ->
            val target = targets[inventory.aggregate] ?: return@mapNotNull null
            AggregateDraft(inventory, target.first, target.second, exceptions).mapping()
        }
        val sourced = targets.values.map { (context, aggregate) -> "${context.name}.${aggregate.name}" }.toSet()
        manifest.contexts.forEach { context ->
            context.aggregates.filter { "${context.name}.${it.name}" !in sourced }.forEach {
                exceptions += "$UNSOURCED ${context.name}.${it.name}: no 9.x aggregate matches it"
            }
        }
        prepareKeys.toSortedMap().forEach { (name, keys) ->
            exceptions += "$UNMATCHED PrepareKey $name ($keys keys): write its reservation mapping (namespace, " +
                "tenancy, ownerId) under the aggregate whose instances its values name"
        }
        return MapDraft(aggregates, exceptions)
    }

    /**
     * The V10 context and aggregate of each 9.x aggregate of [sources] (with its event names) that matches one; the
     * others reported in [exceptions].
     */
    private fun targetsOf(
        sources: Map<NineXAggregate, Set<String>>,
        exceptions: MutableList<String>,
    ): Map<NineXAggregate, Pair<ContextManifest, AggregateManifest>> {
        val targets = LinkedHashMap<NineXAggregate, Pair<ContextManifest, AggregateManifest>>()
        sources.keys.groupBy { it.context }.forEach { (name, aggregates) ->
            val context = contextOf(name)
            if (context == null) {
                aggregates.forEach { exceptions += "$UNMATCHED $it: no V10 context matches the 9.x context $name" }
                return@forEach
            }
            if (context.name != name && words(context.name) != contextWords(name)) {
                exceptions += "$REVIEW the 9.x context $name maps to ${context.name}, the Manifest's only context"
            }
            val byName = aggregates.associateWith { source ->
                context.aggregates.singleOrNull { words(it.name) == words(source.name) }
            }
            byName.forEach { (source, aggregate) -> aggregate?.let { targets[source] = context to it } }
            val free = context.aggregates.filter { it !in byName.values }
            byName.filterValues { it == null }.keys.forEach { source ->
                renamed(source, sources.getValue(source), context, free, targets, exceptions)
            }
        }
        return targets
    }

    private fun contextOf(name: String): ContextManifest? {
        val contexts = manifest.contexts
        return contexts.firstOrNull { it.name == name }
            ?: contexts.singleOrNull { words(it.name) == contextWords(name) }
            ?: contexts.singleOrNull()
    }

    /**
     * Matches [source], whose name no V10 aggregate has, with the one of [free] whose events hold most of its [events]:
     * at least half, and more than any other's.
     */
    @Suppress("LongParameterList") // What a rename is matched from, and where it goes.
    private fun renamed(
        source: NineXAggregate,
        events: Set<String>,
        context: ContextManifest,
        free: List<AggregateManifest>,
        targets: MutableMap<NineXAggregate, Pair<ContextManifest, AggregateManifest>>,
        exceptions: MutableList<String>,
    ) {
        val scores = free.filter { candidate -> targets.values.none { it.second == candidate } }
            .associateWith { candidate -> events.count { event -> candidate.holdsEventNamed(event) } }
            .filterValues { it > 0 && it * 2 >= events.size }
        val best = scores.values.maxOrNull()
        val winners = scores.filterValues { it == best }.keys
        when {
            winners.size == 1 -> {
                val target = winners.single()
                targets[source] = context to target
                exceptions += "$RENAMED $source -> ${context.name}.${target.name}: matched by $best of its " +
                    "${events.size} events; confirm the aggregate was renamed"
            }

            winners.size > 1 ->
                exceptions += "$AMBIGUOUS $source: its events match " +
                    "${winners.map { "${context.name}.${it.name}" }.sorted()} alike"

            else -> exceptions += "$UNMATCHED $source: no V10 aggregate of ${context.name} matches it"
        }
    }

    private fun AggregateManifest.holdsEventNamed(name: String): Boolean =
        (events.flatMap { listOf(it.name) + it.aliases } + retired).any { words(it) == words(name) || it == name }

    /** The draft of one 9.x aggregate mapped to [aggregate] of [context]. */
    private inner class AggregateDraft(
        private val inventory: NineXInventory,
        private val context: ContextManifest,
        private val aggregate: AggregateManifest,
        private val exceptions: MutableList<String>,
    ) {
        private val source = inventory.aggregate
        private val target = "${context.name}.${aggregate.name}"

        fun mapping(): AggregateMapping {
            val events = inventory.events.keys.groupBy { it.name }.toSortedMap().mapNotNull { (name, types) ->
                if (types.size > 1) {
                    exceptions += "$REVIEW $source event $name: the store holds revisions " +
                        "${types.map { it.revision }.sorted()}, which one mapping covers; split it by fromRevision " +
                        "if their shapes differ"
                }
                eventOf(name)
            }
            val commands = inventory.commands.keys.sorted().mapNotNull(::commandOf)
            unsourced(events, commands)
            if (inventory.streamsWithoutCommand > 0) {
                exceptions += "$REVIEW $source: ${inventory.streamsWithoutCommand} streams record no command; set " +
                    "defaultCommand to the V10 command they stand for"
            }
            return AggregateMapping(from = source.toString(), to = target, commands = commands, events = events)
        }

        private fun eventOf(name: String): EventMapping? {
            SYSTEM_EVENTS[name]?.let { (to, fields) ->
                val owned = aggregate.ownership == Ownership.OWNER
                if (to != Lifecycle.OWNER_TRANSFERRED || owned) {
                    return EventMapping(from = name, to = to, fields = fields)
                }
            }
            val events = aggregate.events.map { Candidate(it.name, listOf(it.name) + it.aliases, it.schema) }
            val retired = aggregate.retired.map { Candidate(it, listOf(it), null) }
            return when (val match = match(name, events + retired.map { it.copy(retired = true) })) {
                null -> {
                    exceptions += "$UNMATCHED $source event $name: no V10 event of $target matches it"
                    null
                }

                else -> match.single(
                    name,
                    "event"
                )?.let { EventMapping(from = name, to = it.name, retire = it.retired) }
            }
        }

        private fun commandOf(name: String): CommandMapping? {
            SYSTEM_COMMANDS[name]?.takeIf { it in aggregate.lifecycle }?.let { return CommandMapping(name, it) }
            val decided = aggregate.domainCommands.filterNot { it.mounted }
                .map { Candidate(it.name, listOf(it.name) + it.aliases, it.schema) }
            val match = match(name, decided)
            if (match == null) {
                val mounted = aggregate.domainCommands.filter { it.mounted && words(it.name) == words(name) }
                exceptions += if (mounted.isEmpty()) {
                    "$UNMATCHED $source command $name: no V10 command of $target matches it"
                } else {
                    "$UNMATCHED $source command $name: it is mounted in V10 as ${mounted.single().name}; map it to " +
                        "the command it turns into, one of ${mounted.single().transformsTo.orEmpty()}"
                }
                return null
            }
            return match.single(name, "command")?.let { CommandMapping(name, it.name) }
        }

        /** The one candidate of [this], or null and the ambiguity reported. */
        private fun List<Candidate>.single(name: String, kind: String): Candidate? = singleOrNull() ?: run {
            exceptions += "$AMBIGUOUS $source $kind $name: ${map { it.name }.sorted()} all match it"
            null
        }

        /** The candidates [name] matches at the most certain step that has any; null when none does. */
        private fun match(name: String, candidates: List<Candidate>): List<Candidate>? {
            val exact = candidates.filter { candidate ->
                name in candidate.names || name == "$target.${candidate.name}" || name == candidate.qualifiedName
            }
            if (exact.isNotEmpty()) {
                return exact.distinctBy { it.name }
            }
            return candidates.filter { candidate ->
                (candidate.names + listOfNotNull(candidate.qualifiedName)).any { words(it) == words(name) }
            }.distinctBy { it.name }.ifEmpty { null }
        }

        private fun unsourced(events: List<EventMapping>, commands: List<CommandMapping>) {
            val mappedEvents = events.mapNotNull { it.to }.toSet()
            aggregate.events.filter { it.name !in mappedEvents }.forEach {
                exceptions += "$UNSOURCED $target event ${it.name}: no 9.x event of $source matches it"
            }
            val mappedCommands = commands.map { it.to }.toSet()
            aggregate.domainCommands.filter { !it.mounted && it.name !in mappedCommands }.forEach {
                exceptions += "$UNSOURCED $target command ${it.name}: no 9.x command of $source matches it"
            }
        }
    }

    /** A V10 event or command a 9.x name may match: its [names] (its name and aliases) and its schema reference. */
    private data class Candidate(
        val name: String,
        val names: List<String>,
        private val schema: String?,
        val retired: Boolean = false,
    ) {
        /** The class's qualified name, when the schema reference names its class. */
        val qualifiedName: String? = schema?.takeIf(Manifest::isSchemaRef)?.substringAfter(DEFS)
    }

    companion object {
        /** An exception's markers, at the start of its line: what a person resolves. */
        const val UNMATCHED = "UNMATCHED"
        const val AMBIGUOUS = "AMBIGUOUS"
        const val UNSOURCED = "UNSOURCED"
        const val RENAMED = "RENAMED"
        const val REVIEW = "REVIEW"

        private const val DEFS = "\$defs/"

        /**
         * The 9.x lifecycle's events, as 9.x names them (the snake_case of `DefaultAggregateDeleted`, ...), with their
         * V10 lifecycle events and the 9.x fields V10 renames.
         */
        val SYSTEM_EVENTS: Map<String, Pair<String, Map<String, String>>> = mapOf(
            "default_aggregate_deleted" to (Lifecycle.AGGREGATE_DELETED to emptyMap()),
            "default_aggregate_recovered" to (Lifecycle.AGGREGATE_RESTORED to emptyMap()),
            "owner_transferred" to (Lifecycle.OWNER_TRANSFERRED to mapOf("toOwnerId" to "ownerId")),
        )

        /** The 9.x lifecycle's commands, as 9.x names them, with their V10 lifecycle commands. */
        val SYSTEM_COMMANDS: Map<String, String> = mapOf(
            "default_delete_aggregate" to Lifecycle.DELETE_AGGREGATE,
            "default_recover_aggregate" to Lifecycle.RESTORE_AGGREGATE,
        )

        /** [name]'s last dotted segment without case, `_` and `-`: the words a 9.x and a V10 name share. */
        fun words(name: String): String = name.substringAfterLast('.').filter(Char::isLetterOrDigit).lowercase()

        /** A 9.x context name's words without a `service` suffix: `order-service` is `order`. */
        fun contextWords(name: String): String = words(name).removeSuffix("service").ifEmpty { words(name) }
    }
}

/** A drafted map: the [aggregates] matched, and the [exceptions] a person resolves, each a line with its marker. */
internal class MapDraft(val aggregates: List<AggregateMapping>, val exceptions: List<String>) {
    /** The draft as the map `check` and `import` read. */
    val map: MigrationMap get() = MigrationMap(MigrationMap.FORMAT, aggregates, exceptions = exceptions)

    /** The draft as `migration-map.json`: only what each mapping sets, its exceptions first. */
    fun render(): String {
        val tree = WowJson.mapper.createObjectNode().put("format", MigrationMap.FORMAT)
        tree.putArray("exceptions").apply { exceptions.forEach(::add) }
        val array = tree.putArray("aggregates")
        aggregates.forEach { aggregate ->
            val node = array.addObject().put("from", aggregate.from).put("to", aggregate.to)
            node.putArray("commands").apply {
                aggregate.commands.forEach { addObject().put("from", it.from).put("to", it.to) }
            }
            node.putArray("events").apply {
                aggregate.events.forEach { event ->
                    val entry = addObject().put("from", event.from).put("to", event.to)
                    if (event.retire) {
                        entry.put("retire", true)
                    }
                    if (event.fields.isNotEmpty()) {
                        entry.putObject("fields").apply { event.fields.forEach { (from, to) -> put(from, to) } }
                    }
                }
            }
        }
        return WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n"
    }

    /** What the draft holds, in one line. */
    fun summary(): String =
        "${aggregates.size} aggregates, ${aggregates.sumOf { it.events.size }} events and " +
            "${aggregates.sumOf { it.commands.size }} commands mapped; ${exceptions.size} exceptions for a person."
}

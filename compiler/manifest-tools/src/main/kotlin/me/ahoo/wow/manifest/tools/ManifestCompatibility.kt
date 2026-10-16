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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.SubscriptionManifest

/** Whether a difference between two Manifests keeps the contract (decision D76). */
enum class ChangeKind {
    /** Old clients, old records and old subscribers keep working: something was added or widened. */
    ADDITIVE,

    /** Something a client, a stored record or a subscriber relies on was removed, renamed or narrowed. */
    BREAKING,
}

/**
 * One difference between a baseline Manifest and the current one: where it is ([path], keyed by names, as
 * `contexts[shop].aggregates[cart].commands[AddItem].schema.items[].quantity`), whether it keeps the contract
 * ([kind]), and what changed.
 */
data class ManifestChange(val kind: ChangeKind, val path: String, val description: String) {
    override fun toString(): String = "$kind $path: $description"
}

/**
 * The comparison of a service's Manifest with its baseline, the Manifest of its last published version (decision
 * D76). A breaking change fails the gate within a major version; a new major version ([newMajor]) resets the
 * baseline, so its breaking changes are reported but do not fail it.
 */
data class CompatibilityReport(
    val baselineVersion: String?,
    val currentVersion: String?,
    val changes: List<ManifestChange>,
) {
    val breaking: List<ManifestChange> get() = changes.filter { it.kind == ChangeKind.BREAKING }

    val additive: List<ManifestChange> get() = changes.filter { it.kind == ChangeKind.ADDITIVE }

    /** Whether the current version is of a later major than the baseline's; unknown versions are the same major. */
    val newMajor: Boolean
        get() {
            val baseline = major(baselineVersion) ?: return false
            val current = major(currentVersion) ?: return false
            return current > baseline
        }

    /** Whether the gate fails: a breaking change within the baseline's major version. */
    val failed: Boolean get() = breaking.isNotEmpty() && !newMajor

    /** The report as text: a summary line, then each change on its own line, the breaking ones first. */
    fun render(): String = buildString {
        append(
            "Manifest ${currentVersion ?: "(no version)"} against the baseline ${baselineVersion ?: "(no version)"}: "
        )
        append("${breaking.size} breaking, ${additive.size} additive")
        if (newMajor && breaking.isNotEmpty()) append(" (a new major version: breaking changes are allowed)")
        append('\n')
        (breaking + additive).forEach { append(it).append('\n') }
    }

    private fun major(version: String?): Int? = version?.substringBefore('.')?.toIntOrNull()
}

/**
 * Compares two Manifests and classifies every difference of the wire contract as [ChangeKind.ADDITIVE] or
 * [ChangeKind.BREAKING] (decision D76). Entries are matched by name: a removed context, aggregate, command, event,
 * route, lifecycle command, query model, subscription, published stream or error code is breaking, an added one
 * additive. A command or an event renamed with its former name in its `aliases` keeps matching: the rename is additive,
 * though the routes the old name gave are reported removed.
 *
 * - Schemas follow [SchemaCompatibility]: a removed field, a narrowed type, a newly required field or a removed enum
 *   value break; an optional field or an enum value added does not. The state schema is the snapshot query's fields,
 *   so the same rules hold for it, and a field renamed with its old path in `x-wow-alias` is additive.
 * - A revision covers only the service's own storage (finding A4-5), never a contract others read. A state schema
 *   that breaks breaks the snapshot query's clients (its `state.*` fields) and the subscribers of the aggregate's
 *   published stream when it carries the state, whatever the state revision: raising it (`@AggregateRoot(
 *   stateRevision)`, decision D172) only rebuilds the stored snapshots from the log, so it keeps the contract only for
 *   an aggregate with neither. Likewise an event schema that breaks breaks the events query's clients and the
 *   published stream's subscribers, which read events at their current revision: raising the revision (with an
 *   `@Upgrade` from each earlier one, decision D82) only reads the service's own old records. A lowered revision
 *   breaks.
 * - Command creation that accepts more (`NONE` or `REQUIRED` to `ALLOWED`) is additive, any other change breaking; a
 *   produced event removed breaks a client waiting for it.
 * - Disabling snapshots, changing the ownership, a published stream's topic, or dropping its state breaks; so does a
 *   subscription changing its kind, function or source stream, no longer deriving a command, or an `@OnRejected` /
 *   `@OnCommitted` handler no longer taking a command's receipts; a removed `@OnCommitted` handler is a removed
 *   subscription, and an added one additive.
 * - Descriptions, the state fingerprint (derived from the schema), query indexes, schedules, retry budgets and start
 *   positions are not contract and are not compared.
 */
object ManifestCompatibility {
    fun compare(baseline: Manifest, current: Manifest): CompatibilityReport {
        val changes = Changes(SchemaCompatibility(baseline.defs, current.defs))
        changes.compareManifest(baseline, current)
        return CompatibilityReport(baseline.service?.version, current.service?.version, changes.list)
    }
}

private class Changes(val schemas: SchemaCompatibility) {
    val list = mutableListOf<ManifestChange>()

    fun breaking(path: String, description: String) {
        list += ManifestChange(ChangeKind.BREAKING, path, description)
    }

    fun additive(path: String, description: String) {
        list += ManifestChange(ChangeKind.ADDITIVE, path, description)
    }

    fun compareManifest(baseline: Manifest, current: Manifest) {
        if (baseline.format != current.format) {
            breaking("format", "the Manifest format changed from ${baseline.format} to ${current.format}")
        }
        val baselineService = baseline.service?.name
        val currentService = current.service?.name
        if (baselineService != null && currentService != null && baselineService != currentService) {
            breaking("service", "the service was renamed from $baselineService to $currentService")
        }
        compareErrors("errors", baseline.errors, current.errors)
        compareNamed("contexts", baseline.contexts, current.contexts, { it.name }, "context", ::compareContext)
    }

    /**
     * Matches [baseline] and [current] entries by [name] (and by the former names [aliases] gives a current entry),
     * reporting the removed ones as breaking and the added ones as additive, and [compare]s each matched pair.
     */
    fun <T> compareNamed(
        path: String,
        baseline: List<T>,
        current: List<T>,
        name: (T) -> String,
        noun: String,
        compare: (String, T, T) -> Unit,
        aliases: (T) -> List<String> = { emptyList() },
    ) {
        val matched = mutableSetOf<String>()
        baseline.forEach { old ->
            val oldName = name(old)
            val byName = current.firstOrNull { name(it) == oldName }
            val renamed = byName ?: current.firstOrNull { oldName in aliases(it) }
            val entryPath = "$path[$oldName]"
            when {
                byName != null -> compare(entryPath, old, byName)
                renamed != null -> {
                    additive(entryPath, "$noun renamed to ${name(renamed)}, which keeps $oldName as an alias")
                    compare("$path[${name(renamed)}]", old, renamed)
                }

                else -> breaking(entryPath, "$noun removed")
            }
            matched += name(renamed ?: return@forEach)
        }
        current.filter { name(it) !in matched }.forEach { additive("$path[${name(it)}]", "$noun added") }
    }

    /** Values of a set-like list: a removed one breaks, an added one is additive. */
    fun compareValues(path: String, baseline: Collection<String>, current: Collection<String>, noun: String) {
        (baseline - current.toSet()).forEach { breaking(path, "$noun $it removed") }
        (current - baseline.toSet()).forEach { additive(path, "$noun $it added") }
    }

    fun compareErrors(path: String, baseline: List<ErrorManifest>, current: List<ErrorManifest>) {
        compareNamed(path, baseline, current, { it.code }, "error code", { entry, old, new ->
            if (old.status != new.status) breaking(entry, "status changed from ${old.status} to ${new.status}")
        })
    }

    fun compareRoutes(path: String, baseline: List<RouteManifest>, current: List<RouteManifest>) {
        compareValues(path, baseline.map(::route), current.map(::route), "route")
    }

    private fun route(route: RouteManifest): String = "${route.method} ${route.path}"

    fun compareContext(path: String, baseline: ContextManifest, current: ContextManifest) {
        compareNamed(
            "$path.aggregates",
            baseline.aggregates,
            current.aggregates,
            { it.name },
            "aggregate",
            { entry, old, new -> compareAggregate(entry, old, new, Exposure.of(old.name, baseline, current)) },
        )
        compareNamed(
            "$path.subscriptions",
            baseline.subscriptions,
            current.subscriptions,
            { it.name },
            "subscription",
            ::compareSubscription,
        )
        compareNamed(
            "$path.published",
            baseline.published,
            current.published,
            { it.aggregate },
            "published stream",
            ::comparePublished,
        )
        compareErrors("$path.errors", baseline.errors, current.errors)
    }

    fun compareAggregate(path: String, baseline: AggregateManifest, current: AggregateManifest, exposure: Exposure) {
        compareState(path, baseline, current, exposure)
        if (baseline.snapshot.enabled != current.snapshot.enabled) {
            if (current.snapshot.enabled) {
                additive("$path.snapshot", "snapshots enabled")
            } else {
                breaking("$path.snapshot", "snapshots disabled")
            }
        }
        if (baseline.ownership != current.ownership) {
            breaking("$path.ownership", "ownership changed from ${baseline.ownership} to ${current.ownership}")
        }
        compareNamed(
            "$path.commands",
            baseline.commands,
            current.commands,
            { it.name },
            "command",
            ::compareCommand,
            { it.aliases },
        )
        compareNamed(
            "$path.events",
            baseline.events,
            current.events,
            { it.name },
            "event",
            { entry, old, new -> compareEvent(entry, old, new, exposure) },
            { it.aliases },
        )
        compareValues("$path.lifecycle", baseline.lifecycle, current.lifecycle, "lifecycle command")
        compareNamed("$path.queries", baseline.queries, current.queries, { it.model }, "query model", ::compareQuery)
    }

    /**
     * The state schema (decision D172, finding A4-5): a breaking change keeps the contract only when the aggregate's
     * state revision was raised, so the stored snapshots are rebuilt from the log, and no one else reads the state
     * ([Exposure.state]); a lowered revision breaks.
     */
    private fun compareState(path: String, baseline: AggregateManifest, current: AggregateManifest, exposure: Exposure) {
        val schemaChanges = schemas.compare(
            "$path.state",
            baseline.state.schema,
            current.state.schema,
            queryRoot = true
        )
        val old = baseline.snapshot.stateRevision
        val new = current.snapshot.stateRevision
        when {
            new < old -> {
                breaking("$path.snapshot.stateRevision", "state revision lowered from $old to $new")
                list += schemaChanges
            }

            new > old -> {
                additive("$path.snapshot.stateRevision", "state revision raised from $old to $new")
                val readers = exposure.state
                list += schemaChanges.map {
                    when {
                        it.kind != ChangeKind.BREAKING -> it
                        readers.isEmpty() -> it.copy(
                            kind = ChangeKind.ADDITIVE,
                            description = "${it.description} (snapshots rebuilt at state revision $new)",
                        )

                        else -> it.copy(
                            description = "${it.description}: snapshots are rebuilt at state revision $new, but " +
                                "${readers.joinToString(" and ")} read the state",
                        )
                    }
                }
            }

            else -> list += schemaChanges.map {
                if (it.kind == ChangeKind.BREAKING) {
                    it.copy(description = "${it.description} without a new state revision")
                } else {
                    it
                }
            }
        }
    }

    fun compareCommand(path: String, baseline: CommandManifest, current: CommandManifest) {
        compareValues("$path.aliases", baseline.aliases, current.aliases, "alias")
        list += schemas.compare("$path.schema", baseline.schema, current.schema)
        if (baseline.creation != current.creation) {
            val change = "creation changed from ${baseline.creation} to ${current.creation}"
            if (current.creation == Creation.ALLOWED) {
                additive("$path.creation", change)
            } else {
                breaking("$path.creation", change)
            }
        }
        val oldProduces = baseline.produces
        val newProduces = current.produces
        if (oldProduces != null && newProduces != null) {
            compareValues("$path.produces", oldProduces, newProduces, "produced event")
        } else if (oldProduces != newProduces) {
            additive(
                "$path.produces",
                if (newProduces == null) "produced events no longer declared" else "produced events declared"
            )
        }
        compareRoutes("$path.routes", baseline.routes, current.routes)
    }

    /**
     * An event (decision D82, finding A4-5): a breaking schema change keeps the contract only when its revision was
     * raised, so the service's own old records are upgraded, and no one else reads the event ([Exposure.events]).
     */
    fun compareEvent(path: String, baseline: EventManifest, current: EventManifest, exposure: Exposure) {
        compareValues("$path.aliases", baseline.aliases, current.aliases, "alias")
        val schemaChanges = schemas.compare("$path.schema", baseline.schema, current.schema)
        when {
            current.revision < baseline.revision -> {
                breaking("$path.revision", "revision lowered from ${baseline.revision} to ${current.revision}")
                list += schemaChanges
            }

            current.revision > baseline.revision -> {
                additive("$path.revision", "revision raised from ${baseline.revision} to ${current.revision}")
                // The compiler requires an @Upgrade from each earlier revision (D82): the old records still read.
                val readers = exposure.events
                list += schemaChanges.map {
                    when {
                        it.kind != ChangeKind.BREAKING -> it
                        readers.isEmpty() -> it.copy(
                            kind = ChangeKind.ADDITIVE,
                            description = "${it.description} (upgraded from revision ${baseline.revision})",
                        )

                        else -> it.copy(
                            description = "${it.description}: the service upgrades its records from revision " +
                                "${baseline.revision}, but ${readers.joinToString(" and ")} read the event at its " +
                                "current revision",
                        )
                    }
                }
            }

            else -> list += schemaChanges.map {
                if (it.kind == ChangeKind.BREAKING) {
                    it.copy(description = "${it.description} without a new revision and its @Upgrade")
                } else {
                    it
                }
            }
        }
    }

    fun compareQuery(path: String, baseline: QueryManifest, current: QueryManifest) {
        compareRoutes("$path.routes", baseline.routes, current.routes)
    }

    fun compareSubscription(path: String, baseline: SubscriptionManifest, current: SubscriptionManifest) {
        if (baseline.kind != current.kind) {
            breaking("$path.kind", "kind changed from ${baseline.kind} to ${current.kind}")
        }
        if (baseline.function != current.function) {
            breaking("$path.function", "function changed from ${baseline.function} to ${current.function}")
        }
        val oldSource = baseline.source
        val newSource = current.source
        if (oldSource.context != newSource.context || oldSource.aggregates.toSet() != newSource.aggregates.toSet()) {
            breaking(
                "$path.source",
                "source stream changed from ${oldSource.context}${oldSource.aggregates} to " +
                    "${newSource.context}${newSource.aggregates}: its checkpoint belongs to the old one",
            )
        }
        if (oldSource.events.toSet() != newSource.events.toSet()) {
            additive("$path.source.events", "events changed from ${oldSource.events} to ${newSource.events}")
        }
        val oldDerives = baseline.derives
        val newDerives = current.derives
        if (oldDerives != null && newDerives != null) {
            compareValues("$path.derives", oldDerives, newDerives, "derived command")
        } else if (oldDerives != newDerives) {
            additive(
                "$path.derives",
                if (newDerives == null) "derived commands no longer declared" else "derived commands declared"
            )
        }
        compareReceipts("$path.rejected", baseline.rejected, current.rejected, "rejections")
        compareReceipts("$path.committed", baseline.committed, current.committed, "commits")
    }

    /**
     * The commands an `@OnRejected` or `@OnCommitted` handler takes the receipts of (decisions D136, D192): null when
     * the handler takes none, empty for any command its saga derives. A handler that stops taking a kind of receipt,
     * or a command's, breaks; one that starts, or widens to any command, is additive.
     */
    private fun compareReceipts(path: String, baseline: List<String>?, current: List<String>?, receipts: String) {
        when {
            baseline == current -> Unit
            current == null -> breaking(path, "no longer handles $receipts")
            baseline == null -> additive(path, "handles $receipts")
            current.isEmpty() -> additive(path, "handles the $receipts of any derived command")
            baseline.isEmpty() -> breaking(path, "handles the $receipts of $current only, not of any derived command")
            else -> compareValues(path, baseline, current, "command with handled $receipts")
        }
    }

    /**
     * Who besides the service reads an aggregate's state and events, in the baseline or now (finding A4-5): the
     * clients of its snapshot and events query models, and the subscribers of its published stream (decisions D58,
     * D156), which carries its events and, unless it leaves it out, its state.
     */
    class Exposure(val state: List<String>, val events: List<String>) {
        companion object {
            fun of(aggregate: String, baseline: ContextManifest, current: ContextManifest): Exposure {
                val manifests = listOf(baseline, current)
                val queries = manifests.flatMap { context ->
                    context.aggregates.filter { it.name == aggregate }.flatMap { it.queries }.map { it.model }
                }.toSet()
                val published = manifests.flatMap { context -> context.published.filter { it.aggregate == aggregate } }
                val stream = "the subscribers of its published stream".takeIf { published.isNotEmpty() }
                val stateStream = stream.takeIf { published.any { it.includeState } }
                return Exposure(
                    state = listOfNotNull(
                        "the snapshot query's clients".takeIf { QueryRoutes.SNAPSHOT in queries },
                        stateStream,
                    ),
                    events = listOfNotNull(
                        "the events query's clients".takeIf { QueryRoutes.EVENTS in queries },
                        stream,
                    ),
                )
            }
        }
    }

    fun comparePublished(path: String, baseline: PublishedManifest, current: PublishedManifest) {
        if (baseline.topic != current.topic) {
            breaking("$path.topic", "topic changed from ${baseline.topic} to ${current.topic}")
        }
        if (baseline.includeState != current.includeState) {
            if (current.includeState) {
                additive("$path.includeState", "the state is now included")
            } else {
                breaking("$path.includeState", "the state is no longer included")
            }
        }
    }
}

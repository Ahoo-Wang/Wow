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

import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceRouteManifest

/** Whether a difference between two Manifests keeps the contract. */
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
 * The comparison of a service's Manifest with its baseline, the committed Manifest of its contract. A breaking change
 * fails the gate ([failed]); a change that breaks on purpose, for a new major version, is recorded as the new baseline
 * instead.
 */
data class CompatibilityReport(val changes: List<ManifestChange>) {
    val breaking: List<ManifestChange> get() = changes.filter { it.kind == ChangeKind.BREAKING }

    val additive: List<ManifestChange> get() = changes.filter { it.kind == ChangeKind.ADDITIVE }

    /** Whether the gate fails: a breaking change. */
    val failed: Boolean get() = breaking.isNotEmpty()

    /** The report as text: a summary line, then each change on its own line, the breaking ones first. */
    fun render(): String = buildString {
        append("Manifest against its baseline: ${breaking.size} breaking, ${additive.size} additive\n")
        (breaking + additive).forEach { append(it).append('\n') }
    }
}

/**
 * Compares two Manifests and classifies every difference of the wire contract as [ChangeKind.ADDITIVE] or
 * [ChangeKind.BREAKING]. Entries are matched by name: a removed context, aggregate, command, event,
 * route, lifecycle command, query model, subscription, published stream or error code is breaking, an added one
 * additive. A command or an event renamed with its former name in its `aliases` keeps matching: the rename is additive,
 * though the routes the old name gave are reported removed.
 *
 * - An event removed and listed in the aggregate's `retired` events (`@AggregateRoot(retiredEvents)`) is
 *   retired, which is additive, when each of its stored names (its name and aliases) is retired; one removed without
 *   that breaks the loading of its history. A name no longer retired breaks too; one newly retired is additive.
 *
 * - Schemas follow [SchemaCompatibility]: a removed field, a narrowed type, a newly required field or a removed enum
 *   value break; an optional field or an enum value added does not. A state and an event are outputs as well
 *   ([SchemaDirection.OUTPUT]): their readers (the query models' clients, the published stream's subscribers) read
 *   what the service writes now, so a field made optional or nullable, a type widened or an enum value added breaks
 *   an aggregate that has readers, whatever the revision, which only rebuilds or upgrades the service's own records. The state schema is the snapshot query's fields,
 *   so the same rules hold for it, and a field renamed with its old path in `x-wow-alias` is additive.
 * - A revision covers only the service's own storage, never a contract others read. A state schema
 *   that breaks breaks the snapshot query's clients (its `state.*` fields) and the subscribers of the aggregate's
 *   published stream when it carries the state, whatever the state revision: raising it (`@AggregateRoot
 *   (stateRevision)`) only rebuilds the stored snapshots from the log, so it keeps the contract only for
 *   an aggregate with neither. Likewise an event schema that breaks breaks the events query's clients and the
 *   published stream's subscribers, which read events at their current revision: raising the revision (with an
 *   `@Upgrade` from each earlier one) only reads the service's own old records. A lowered revision
 *   breaks.
 * - A service route ([ServiceRouteManifest]) is matched by its name: removing it, or changing its method or path,
 *   breaks; a parameter (named by where it is read from and its name, `query:limit`) removed, retyped or made required
 *   breaks, and so does a required one added; one moved between the path and the query is one removed and one added; its
 *   request is an input and its response an output ([SchemaDirection]); a route that no longer takes or answers a
 *   body, or newly does, breaks. Its refusal codes are documentation for a client that handles unknown codes, so a
 *   code added or removed is additive.
 * - Command creation that accepts more (`NONE` or `REQUIRED` to `ALLOWED`) is additive, any other change breaking; a
 *   produced event removed breaks a client waiting for it.
 * - A command made internal (`@InternalCommand`) breaks: the public port no longer serves its routes, which its public
 *   clients send. One made public again is additive: the internal port, and the services that call it, keep its
 *   routes either way.
 * - Changing an aggregate's id source (`@AggregateRoot(idSource)`) either way breaks: a route by no id, the caller's own
 *   aggregate's for one whose id is its owner's, means another aggregate, and the ids its clients send or keep change
 *   meaning. The routes by no id the change adds or removes are part of it, reported once with it; any other route
 *   follows the rules above.
 * - An aggregate's access attribute (`@Access`) removed or renamed breaks the access policies and the stored records
 *   that name it, and so does one whose type changes or that changes between one value and several; one added is
 *   additive.
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
        return CompatibilityReport(changes.list)
    }
}

internal class Changes(val schemas: SchemaCompatibility) {
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
        removed: (String, T) -> Unit = { entryPath, _ -> breaking(entryPath, "$noun removed") },
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

                else -> removed(entryPath, old)
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

    fun route(route: RouteManifest): String = "${route.method} ${route.path}"

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
            this::compareSubscription,
        ) { entryPath, old -> removedSubscription(entryPath, old, baseline.subscriptions, current.subscriptions) }
        compareNamed(
            "$path.published",
            baseline.published,
            current.published,
            { it.aggregate },
            "published stream",
            this::comparePublished,
        )
        compareErrors("$path.errors", baseline.errors, current.errors)
        compareNamed(
            "$path.routes",
            baseline.routes,
            current.routes,
            { it.name },
            "service route",
            { entry, old, new -> compareServiceRoute(entry, old, new) },
        )
    }
}

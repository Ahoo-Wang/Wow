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

package me.ahoo.wow.manifest

/**
 * Assembles a service's Manifest from its modules' fragments (decision D74). The build merges them into the
 * service's `manifest.json`; the runtime merges the same fragments when it starts. Both refuse a conflict, so a
 * name the runtime resolves is never ambiguous:
 *
 * - a bounded context is declared by one fragment only (and once in it);
 * - an aggregate type `context.aggregate` is declared once;
 * - a command type, and an event type, `context.aggregate.Name` is declared once, counting former names (`@Alias`)
 *   as names: an alias without a dot is within its aggregate, one with dots a full type name;
 * - a subscription name is declared once within its context;
 * - a schema key of `$defs` declared by two fragments holds the same schema in both (a type shared by two modules).
 *
 * The result lists contexts by name and `$defs` by key, so the same fragments always give the same Manifest.
 */
object ManifestMerger {
    fun merge(fragments: List<ManifestFragment>, service: ServiceInfo? = null): Manifest {
        val conflicts = fragments.filter { it.manifest.format != Manifest.FORMAT }.map {
            "${it.source} is of format ${it.manifest.format}, not ${Manifest.FORMAT}"
        }.toMutableList()
        val contexts = fragments.flatMap { fragment -> fragment.manifest.contexts.map { fragment.source to it } }
        contexts.groupBy({ it.second.name }, { it.first }).filterValues { it.size > 1 }.forEach { (name, sources) ->
            conflicts += "context $name is declared by ${sources.joinToString(" and ")}"
        }
        contexts.forEach { (source, context) -> conflicts += conflictsWithin(source, context) }
        conflicts += typeNameConflicts(contexts, "command") { aggregate -> aggregate.commands.map { it.name to it.aliases } }
        conflicts += typeNameConflicts(contexts, "event") { aggregate -> aggregate.events.map { it.name to it.aliases } }

        val defs = sortedMapOf<String, Any?>()
        val defSources = mutableMapOf<String, String>()
        fragments.forEach { fragment ->
            fragment.manifest.defs.forEach { (key, schema) ->
                val previous = defSources.putIfAbsent(key, fragment.source)
                if (previous == null) {
                    defs[key] = schema
                } else if (defs[key] != schema) {
                    conflicts += "schema $key differs between $previous and ${fragment.source}"
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            throw ManifestConflictException(conflicts)
        }
        return Manifest(
            service = service,
            contexts = contexts.map { it.second }.sortedBy { it.name },
            defs = defs,
        )
    }

    /** Aggregates and subscriptions declared twice within one context. */
    private fun conflictsWithin(source: String, context: ContextManifest): List<String> =
        context.aggregates.groupBy { it.name }.filterValues { it.size > 1 }.keys.map {
            "aggregate ${context.name}.$it is declared twice in $source"
        } + context.subscriptions.groupBy { it.name }.filterValues { it.size > 1 }.keys.map {
            "subscription $it of context ${context.name} is declared twice in $source"
        }

    /** Full type names, names and former names, that more than one command (or event) declares. */
    private fun typeNameConflicts(
        contexts: List<Pair<String, ContextManifest>>,
        kind: String,
        names: (AggregateManifest) -> List<Pair<String, List<String>>>,
    ): List<String> {
        val declared = contexts.flatMap { (source, context) ->
            context.aggregates.flatMap { aggregate ->
                val type = "${context.name}.${aggregate.name}"
                names(aggregate).flatMap { (name, aliases) ->
                    (listOf(name) + aliases).map { fullName(type, it) to "$type.$name ($source)" }
                }
            }
        }
        return declared.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.map { (name, owners) ->
            "$kind type $name is declared by ${owners.joinToString(" and ")}"
        }
    }

    private fun fullName(aggregateType: String, name: String): String =
        if ('.' in name) name else "$aggregateType.$name"
}

/** The fragments of a service contradict each other; [conflicts] lists every contradiction found. */
class ManifestConflictException(val conflicts: List<String>) :
    IllegalStateException("The Manifest fragments conflict:\n" + conflicts.joinToString("\n") { "- $it" })

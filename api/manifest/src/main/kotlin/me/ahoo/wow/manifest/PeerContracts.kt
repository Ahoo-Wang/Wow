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
 * The peer contracts of a context (decision D234): what another service needs to know of a context to send it
 * commands, read its published streams and follow a wait chain through it, carried by the jar of the context's api
 * module, so a service that depends on that api knows the context with no configuration.
 *
 * A contract is a [Manifest] without a service: the domain module's completed fragment filtered by [contractOf] (the
 * Gradle plugin's `generateWowContract`), and the contracts of the api's contexts merged into one by [assemble] (its
 * `assembleWowContract`, in the api module). Each jar holds one, at [RESOURCE], which a reader finds on a class path
 * with `ClassLoader.getResources(RESOURCE)`, one URL per jar or directory: no directory is listed.
 *
 * A shaded (uber) jar that merges its libraries' entries into one keeps only one [RESOURCE] unless it merges the files:
 * one JSON object whose `contexts` are those of every file and whose `$defs` and `errors` are their union. An
 * executable Spring Boot jar keeps each library jar whole and needs nothing.
 */
object PeerContracts {
    /** Where a jar carries the contract of the contexts of its api module. */
    const val RESOURCE: String = "META-INF/wow/contract.json"

    /**
     * The contract of the contexts of [fragments], the completed Manifest fragments of a domain module: per context its
     * name, description, aggregates (state, snapshot, the commands no edge mounts with their names, aliases, schemas,
     * creation and produced events, and the events), subscriptions (kind, source, function, and the commands they
     * derive, handle the rejections of and the commits of: what a wait chain follows), published streams and errors;
     * the framework's errors; and the `$defs` these refer to, transitively. Routes, queries, schedules, retries, jobs
     * and the service are left out.
     */
    fun contractOf(fragments: List<Manifest>): Manifest {
        val contexts = fragments.flatMap { it.contexts }.sortedBy { it.name }.map(::contractOf)
        val defs = fragments.fold(emptyMap<String, Any?>()) { all, fragment -> all + fragment.defs }
        val errors = fragments.flatMap { it.errors }.distinctBy { it.code }
        val contract = Manifest(contexts = contexts, errors = errors)
        return contract.withDefs(referenced(contract.schemaRefs, defs))
    }

    private fun contractOf(context: ContextManifest): ContextManifest = ContextManifest(
        name = context.name,
        description = context.description,
        aggregates = context.aggregates.map { aggregate ->
            AggregateManifest(
                name = aggregate.name,
                description = aggregate.description,
                state = aggregate.state,
                snapshot = aggregate.snapshot,
                commands = aggregate.commands.filterNot { it.mounted }.map {
                    CommandManifest(
                        name = it.name,
                        aliases = it.aliases,
                        schema = it.schema,
                        creation = it.creation,
                        produces = it.produces,
                        description = it.description,
                    )
                },
                events = aggregate.events,
            )
        },
        subscriptions = context.subscriptions.map {
            SubscriptionManifest(
                name = it.name,
                kind = it.kind,
                source = it.source,
                function = it.function,
                derives = it.derives,
                rejected = it.rejected,
                committed = it.committed,
            )
        },
        published = context.published,
        errors = context.errors,
    )

    /** The [defs] the schema references [refs] point to, and those their schemas point to, by key. */
    private fun referenced(refs: List<String>, defs: Map<String, Any?>): Map<String, Any?> {
        val reached = sortedMapOf<String, Any?>()
        fun visit(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach(::visit)
                is List<*> -> node.forEach(::visit)
                is String -> if (node.startsWith(DEFS_PREFIX)) {
                    val key = Manifest.schemaKey(node)
                    if (key in defs && key !in reached) {
                        reached[key] = defs[key]
                        visit(defs[key])
                    }
                }
            }
        }
        refs.forEach(::visit)
        return reached
    }

    /** The binary names of the classes of [context]'s commands and events, the classes its api module holds. */
    fun classesOf(context: ContextManifest): List<String> = context.aggregates.flatMap { aggregate ->
        aggregate.commands.map { it.schema } + aggregate.events.map { it.schema }
    }.filter { it.startsWith(DEFS_PREFIX) }.map(Manifest::schemaKey).distinct()

    /**
     * The contract the api module [owner] carries: of the contexts of the domain modules' [contracts], those whose
     * command and event classes are its own ([owns], by binary name). A context with none of its classes is another
     * api module's, and is left out; a context with some of them and not others fails, as does a context two
     * contracts declare. Null when no context is the api module's.
     *
     * @throws IllegalStateException listing every problem.
     */
    fun assemble(owner: String, contracts: List<ManifestFragment>, owns: (String) -> Boolean): Manifest? {
        val problems = mutableListOf<String>()
        val kept = contracts.flatMap { contract ->
            contract.manifest.contexts.mapNotNull { context ->
                val classes = classesOf(context)
                val foreign = classes.filterNot(owns)
                when {
                    foreign.isEmpty() -> contract to context
                    foreign.size == classes.size -> null
                    else -> {
                        problems += "context ${context.name} of ${contract.source} has classes that are not in $owner: " +
                            "${foreign.joinToString()}; a context's commands and events are in its api module"
                        null
                    }
                }
            }
        }
        kept.groupBy({ it.second.name }, { it.first.source }).forEach { (context, sources) ->
            if (sources.size > 1) {
                problems += "context $context of $owner is declared by ${sources.joinToString(" and ")}"
            }
        }
        val defs = LinkedHashMap<String, Any?>()
        kept.map { it.first }.distinct().forEach { contract ->
            contract.manifest.defs.forEach { (key, schema) ->
                val known = defs.putIfAbsent(key, schema)
                if (known != null && known != schema) {
                    problems += "schema $key of ${contract.source} differs from that of another contract of $owner"
                }
            }
        }
        check(problems.isEmpty()) {
            "The contract of $owner cannot be assembled:\n" + problems.joinToString("\n") { "- $it" }
        }
        if (kept.isEmpty()) {
            return null
        }
        val contract = Manifest(
            contexts = kept.map { it.second }.sortedBy { it.name },
            errors = kept.flatMap { it.first.manifest.errors }.distinctBy { it.code },
        )
        return contract.withDefs(referenced(contract.schemaRefs, defs))
    }

    /**
     * The [contracts] a node reads on its class path, as the node that serves the contexts [served] sees them (DX gap
     * G21): each contract without the contexts the node serves, and without the contracts left with none. The node's
     * own api is on its class path, and services that share one class path carry each other's: the node's own class
     * path decides the contexts it serves, never such a contract. Identical copies count once.
     *
     * What is left still has its [conflicts]: two contracts that declare one context.
     */
    fun outside(served: Collection<String>, contracts: List<ManifestFragment>): List<ManifestFragment> {
        val own = served.toSet()
        return contracts.mapNotNull { contract ->
            val contexts = contract.manifest.contexts.filterNot { it.name in own }
            when {
                contexts.size == contract.manifest.contexts.size -> contract
                contexts.isEmpty() -> null
                else -> ManifestFragment(contract.source, contract.manifest.withContexts(contexts))
            }
        }.distinctBy { it.manifest }
    }

    /** Every context two of the [contracts] declare: a context is one api module's. */
    fun conflicts(contracts: List<ManifestFragment>): List<String> =
        contracts.flatMap { contract -> contract.manifest.contexts.map { it.name to contract.source } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (context, sources) -> "context $context is declared by the contracts ${sources.joinToString(" and ")}" }

    private const val DEFS_PREFIX = "#/\$defs/"
}

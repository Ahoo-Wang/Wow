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

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Assembles a service's Manifest from its modules' fragments. The build merges them into the
 * service's `manifest.json`; the runtime merges the same fragments when it starts. Both refuse a conflict, so a
 * name the runtime resolves is never ambiguous:
 *
 * - a bounded context is declared by one fragment only (and once in it); another fragment may add service routes to
 *   it, with the error codes they refuse with, and nothing else ([ContextManifest.routes]: the server module that
 *   holds the handlers adds them to the context its domain module declares);
 * - an aggregate type `context.aggregate` is declared once;
 * - a command type, and an event type, `context.aggregate.Name` is declared once, counting former names (`@Alias`)
 *   as names: an alias without a dot is within its aggregate, one with dots a full type name;
 * - a subscription name is declared once within its context;
 * - an aggregate's system commands are not names it declares: every aggregate may list the same ones (the lifecycle
 *   commands, the snapshot rebuild), and an aggregate keeps them as its one fragment declares them; a domain
 *   command never takes a system name, nor a system command a domain one;
 * - a schema key of `$defs` declared by two fragments holds the same schema in both (a type shared by two modules);
 * - a route, a method and a path, belongs to one command, one query model or one service route only, an aggregate
 *   lists a query model once, a context names a service route once, and no service route takes a reserved segment;
 * - a context publishes the state events of one of its own aggregates, each once, and a topic carries one aggregate's
 *   state events only, across all contexts;
 * - only a saga's subscription handles receipts, of rejections or of commits;
 * - no context takes the name of the framework's endpoints, and no command route ends in a segment the framework
 *   keeps for itself ([CommandRoutes]);
 * - a context documents an error code once, and not a framework (`wow.*`) one; a fragment's top-level errors, if
 *   any, are the framework's as [FrameworkErrors] defines them.
 *
 * Every problem is reported, not just the first. The result lists contexts by name and `$defs` by key, so the same
 * fragments always give the same Manifest, and carries the framework's error table ([FrameworkErrors.ALL]).
 */
object ManifestMerger {
    fun merge(fragments: List<ManifestFragment>, service: ServiceInfo? = null): Manifest {
        val conflicts = fragments.filter { it.manifest.format != Manifest.FORMAT }.map {
            "${it.source} is of format ${it.manifest.format}, not ${Manifest.FORMAT}"
        }.toMutableList()
        val declared = fragments.flatMap { fragment -> fragment.manifest.contexts.map { fragment.source to it } }
        declared.filterNot { it.second.routesOnly }.groupBy(
            { it.second.name },
            { it.first }
        ).filterValues { it.size > 1 }
            .forEach { (name, sources) -> conflicts += "context $name is declared by ${sources.joinToString(" and ")}" }
        val contexts = withRoutesAdded(declared, conflicts)
        contexts.forEach { (source, context) -> conflicts += conflictsWithin(source, context) }
        contexts.forEach { (source, context) -> conflicts += undeclaredRefusals(source, context) }
        conflicts += typeNameConflicts(contexts, "command") { aggregate ->
            aggregate.domainCommands.map { it.name to it.aliases }
        }
        conflicts += typeNameConflicts(contexts, "event") { aggregate -> aggregate.events.map { it.name to it.aliases } }
        conflicts += routeConflicts(contexts)
        conflicts += topicConflicts(contexts)
        conflicts += fragments.flatMap(::frameworkErrorConflicts)

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
            errors = FrameworkErrors.ALL,
            defs = defs,
            // A reader must understand what any fragment requires.
            requires = fragments.flatMap { it.manifest.requires }.distinct().sorted(),
        )
    }

    /**
     * The contexts of [declared] with the service routes, and their error codes, that other fragments add to them: one
     * entry per context, from the fragment that declares it (or the first that adds routes to a context none
     * declares), its source naming every fragment that contributed.
     */
    private fun withRoutesAdded(
        declared: List<Pair<String, ContextManifest>>,
        conflicts: MutableList<String>,
    ): List<Pair<String, ContextManifest>> = declared.groupBy { it.second.name }.values.flatMap { entries ->
        val owner = entries.firstOrNull { !it.second.routesOnly } ?: entries.first()
        // A context declared twice stays twice, so each declaration's conflicts are reported.
        val added = entries.filter { it !== owner && it.second.routesOnly }
        val others = entries.filter { it !== owner && !it.second.routesOnly }
        if (added.isEmpty()) return@flatMap listOf(owner) + others
        added.flatMap { it.second.errors }.filter { error -> owner.second.errors.any { it.code == error.code } }
            .forEach { conflicts += "error ${it.code} of context ${owner.second.name} is declared by more than one fragment" }
        val context = owner.second.copyWith(
            errors = owner.second.errors + added.flatMap { it.second.errors },
            routes = owner.second.routes + added.flatMap { it.second.routes },
        )
        listOf((listOf(owner) + added).joinToString(" and ") { it.first } to context) + others
    }

    /** The codes a service route refuses with that its context, every fragment's errors merged, does not declare. */
    private fun undeclaredRefusals(source: String, context: ContextManifest): List<String> {
        val codes = context.errors.map { it.code }.toSet()
        return context.routes.flatMap { route ->
            (route.errors - codes).map {
                "service route ${context.name}.${route.name} ($source) refuses with $it, which is not an error of " +
                    "the context"
            }
        }
    }

    /** Whether this context, as a fragment declares it, only adds service routes (and their errors) to it. */
    private val ContextManifest.routesOnly: Boolean
        get() = routes.isNotEmpty() && aggregates.isEmpty() && subscriptions.isEmpty() && published.isEmpty() &&
            commands.isEmpty()

    /**
     * Within one context: a reserved name, aggregates, subscriptions and error codes declared twice, and error codes
     * that take the framework's prefix.
     */
    private fun conflictsWithin(source: String, context: ContextManifest): List<String> = buildList {
        if (context.name == CommandRoutes.RESERVED_CONTEXT) {
            add("context ${context.name} of $source takes a name the framework reserves")
        }
        context.aggregates.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            add("aggregate ${context.name}.$it is declared twice in $source")
        }
        context.subscriptions.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            add("subscription $it of context ${context.name} is declared twice in $source")
        }
        context.aggregates.forEach { aggregate ->
            aggregate.queries.groupBy { it.model }.filterValues { it.size > 1 }.keys.forEach {
                add("query model $it of aggregate ${context.name}.${aggregate.name} is declared twice in $source")
            }
        }
        context.subscriptions.filter { it.isReceiptHandler && it.kind != SubscriptionKind.SAGA }.forEach {
            add("subscription ${it.name} of context ${context.name} in $source handles receipts but is a ${it.kind}")
        }
        addAll(publishedConflicts(source, context))
        addAll(jobConflicts(source, context))
        context.errors.groupBy { it.code }.filterValues { it.size > 1 }.keys.forEach {
            add("error $it of context ${context.name} is declared twice in $source")
        }
        context.routes.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            add("service route $it of context ${context.name} is declared twice in $source")
        }
        context.errors.filter { it.code.startsWith(FrameworkErrors.PREFIX) }.forEach {
            add("error ${it.code} of context ${context.name} in $source takes the framework's prefix")
        }
    }

    /**
     * Jobs of one name that differ, and an aggregate named `schedule` in a context with jobs: the
     * name of its built-in job aggregate.
     */
    private fun jobConflicts(source: String, context: ContextManifest): List<String> = buildList {
        val jobs = context.subscriptions.mapNotNull { it.job }
        jobs.groupBy { it.name }.filterValues { it.distinct().size > 1 }.keys.forEach {
            add("job $it of context ${context.name} is declared twice, differently, in $source")
        }
        if (jobs.isNotEmpty() && context.aggregates.any { it.name == JOB_AGGREGATE }) {
            add(
                "context ${context.name} in $source has jobs and an aggregate named $JOB_AGGREGATE, the name of its " +
                    "built-in job aggregate",
            )
        }
    }

    /** The name of a context's built-in job aggregate. */
    private const val JOB_AGGREGATE = "schedule"

    /** A published stream of an aggregate the context does not declare, and an aggregate published twice. */
    private fun publishedConflicts(source: String, context: ContextManifest): List<String> = buildList {
        val aggregates = context.aggregates.map { it.name }.toSet()
        context.published.filter { it.aggregate !in aggregates }.forEach {
            add("context ${context.name} in $source publishes ${it.aggregate}, which is not one of its aggregates")
        }
        context.published.groupBy { it.aggregate }.filterValues { it.size > 1 }.keys.forEach {
            add("aggregate ${context.name}.$it is published twice in $source")
        }
    }

    /** Topics that more than one published stream is written to. */
    private fun topicConflicts(contexts: List<Pair<String, ContextManifest>>): List<String> =
        contexts.flatMap { (source, context) ->
            context.published.map { it.topic to "${context.name}.${it.aggregate} ($source)" }
        }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.map { (topic, owners) ->
            "topic $topic is published by ${owners.joinToString(" and ")}"
        }

    /**
     * Routes that more than one command or query model declares (a domain command's route equal to a system command's
     * among them), a domain command named or routed in the framework's reserved names, and a system command that is
     * not.
     */
    private fun routeConflicts(contexts: List<Pair<String, ContextManifest>>): List<String> {
        val commands = contexts.flatMap { (source, context) ->
            context.commands.map { Triple(it, context.name, source) } +
                context.aggregates.flatMap { aggregate ->
                    aggregate.commands.map { Triple(it, "${context.name}.${aggregate.name}", source) }
                }
        }
        val commandRoutes = commands.flatMap { (command, owner, source) ->
            command.routes.map { it to "$owner.${command.name} ($source)" }
        }
        val names = commands.mapNotNull { (command, owner, source) ->
            val reservedName = command.name.startsWith(CommandRoutes.SYSTEM_SEGMENT_PREFIX)
            when {
                command.system && !reservedName ->
                    "system command $owner.${command.name} of $source is not named in the system namespace " +
                        "(${CommandRoutes.SYSTEM_SEGMENT_PREFIX}*)"

                !command.system && reservedName ->
                    "command $owner.${command.name} of $source takes a name the framework reserves " +
                        "(${CommandRoutes.SYSTEM_SEGMENT_PREFIX}*)"

                else -> null
            }
        }
        val queryRoutes = contexts.flatMap { (source, context) ->
            context.aggregates.flatMap { aggregate ->
                aggregate.queries.flatMap { query ->
                    query.routes.map { it to "query ${context.name}.${aggregate.name}.${query.model} ($source)" }
                }
            }
        }
        val serviceRoutes = contexts.flatMap { (source, context) ->
            context.routes.map { it.route to "service route ${context.name}.${it.name} ($source)" }
        }
        // Below its context, a service route's first segment is neither an aggregate's nor the jobs', so the routes
        // of the context's aggregates and jobs stay theirs; no segment is one the framework keeps for itself.
        val reservedServiceRoutes = contexts.flatMap { (source, context) ->
            val taken = context.aggregates.map { it.name }.toSet() + CommandRoutes.JOBS
            context.routes.filter { route ->
                val segments = route.route.path.removePrefix("/${context.name}/").split('/')
                segments.first() in taken || segments.any(CommandRoutes::isReserved)
            }.map {
                "service route ${context.name}.${it.name} ($source) takes a segment an aggregate, the jobs or the " +
                    "framework reserves: ${it.route.method} ${it.route.path}"
            }
        }
        val reserved = commands.filterNot { (command) -> command.system }.flatMap { (command, owner, source) ->
            command.routes.filter { CommandRoutes.isReserved(it.path.substringAfterLast('/')) }.map {
                "route ${it.method} ${it.path} of $owner.${command.name} ($source) ends in a segment the framework " +
                    "reserves"
            }
        }
        val shared = (commandRoutes + queryRoutes + serviceRoutes).groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (route, owners) ->
                "route ${route.method} ${route.path} is declared by ${owners.joinToString(" and ")}"
            }
        return names + reserved + reservedServiceRoutes + shared
    }

    /** A fragment's top-level errors that are not the framework's as [FrameworkErrors] defines them. */
    private fun frameworkErrorConflicts(fragment: ManifestFragment): List<String> =
        fragment.manifest.errors.filter { FrameworkErrors.BY_CODE[it.code] != it }.map {
            "error ${it.code} of ${fragment.source} is not the framework's; a context lists its domain's errors"
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
class ManifestConflictException @InternalWowApi constructor(val conflicts: List<String>) :
    IllegalStateException("The Manifest fragments conflict:\n" + conflicts.joinToString("\n") { "- $it" })

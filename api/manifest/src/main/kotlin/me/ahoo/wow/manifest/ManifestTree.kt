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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.wire.WireNames

/**
 * The JSON shape of a [Manifest], as a tree of plain values: a JSON object is a `Map<String, Any?>` (insertion
 * ordered when written), an array a `List<Any?>`, and a scalar a `String`, `Number`, `Boolean` or null. Any JSON
 * library writes and reads such a tree, so the Manifest's field names are defined here once and `wow-manifest` needs
 * none.
 *
 * Absent optional values (a null description, unknown `produces`, `derives`, `schedules` or `transformsTo`, a fragment's
 * service, a subscription's `retry` or `job`) are left out, not written as null, and so are a subscription source's
 * `nullableState` and a command's `mounted`, `system` and `internal` when false, an aggregate's `ownership` when it is `OWNER`, the default,
 * its `idSource` when it is `ASSIGNED`, the default, its `retired` events and its `access` attributes when it has none, an access
 * attribute's `multiple` when false, and the Manifest's `requires` when it has none.
 */
object ManifestTree {
    private const val DEFS = "\$defs"

    fun write(manifest: Manifest): Map<String, Any?> = obj(
        "format" to manifest.format,
        "requires" to manifest.requires.ifEmpty { null },
        "service" to manifest.service?.let { obj("name" to it.name, "version" to it.version) },
        "contexts" to manifest.contexts.map(::writeContext),
        "errors" to manifest.errors.map(::writeError),
        DEFS to manifest.defs,
    )

    private fun writeContext(context: ContextManifest): Map<String, Any?> = obj(
        "name" to context.name,
        "description" to context.description,
        "aggregates" to context.aggregates.map(::writeAggregate),
        "subscriptions" to context.subscriptions.map(::writeSubscription),
        "published" to context.published.map {
            obj("aggregate" to it.aggregate, "topic" to it.topic, "includeState" to it.includeState)
        },
        "errors" to context.errors.map(::writeError),
        "commands" to context.commands.map(::writeCommand).ifEmpty { null },
        "routes" to context.routes.map(::writeServiceRoute).ifEmpty { null },
    )

    private fun writeServiceRoute(route: ServiceRouteManifest): Map<String, Any?> = obj(
        "name" to route.name,
        "route" to writeRoute(route.route),
        "parameters" to route.parameters.map {
            obj(
                "name" to it.name,
                "in" to WireNames.of(it.location),
                "schema" to it.schema,
                "required" to it.required,
            )
        }.ifEmpty { null },
        "request" to route.request,
        "response" to route.response,
        "responseOptional" to route.responseOptional.takeIf { it },
        "errors" to route.errors.ifEmpty { null },
        "description" to route.description,
        "deprecated" to route.deprecated.takeIf { it },
    )

    private fun writeCommand(command: CommandManifest): Map<String, Any?> = obj(
        "name" to command.name,
        "aliases" to command.aliases,
        "schema" to command.schema,
        "creation" to WireNames.of(command.creation),
        "produces" to command.produces,
        "routes" to command.routes.map(::writeRoute),
        "mounted" to command.mounted.takeIf { it },
        "system" to command.system.takeIf { it },
        "internal" to command.internal.takeIf { it },
        "transformsTo" to command.transformsTo,
        "idProperty" to command.idProperty,
        "description" to command.description,
    )

    private fun writeError(error: ErrorManifest): Map<String, Any?> =
        obj(
            "code" to error.code,
            "status" to error.status,
            "description" to error.description,
            "retry" to error.retry.takeUnless { it == Retry.NONE }?.let(WireNames::of),
            "retryAfter" to error.retryAfter,
        )

    private fun writeAggregate(aggregate: AggregateManifest): Map<String, Any?> = obj(
        "name" to aggregate.name,
        "description" to aggregate.description,
        "state" to obj(
            "schema" to aggregate.state.schema,
            "fingerprint" to aggregate.state.fingerprint,
            "view" to aggregate.state.view,
        ),
        "snapshot" to obj(
            "enabled" to aggregate.snapshot.enabled,
            // Written only when raised, so a Manifest of an aggregate that never raised it reads as before.
            "stateRevision" to aggregate.snapshot.stateRevision.takeIf { it != 1 },
        ),
        "commands" to aggregate.commands.map(::writeCommand),
        "events" to aggregate.events.map {
            obj(
                "name" to it.name,
                "aliases" to it.aliases,
                "revision" to it.revision,
                "schema" to it.schema,
                "description" to it.description,
            )
        },
        "queries" to aggregate.queries.map {
            obj("model" to it.model, "routes" to it.routes.map(::writeRoute))
        },
        "schedules" to aggregate.schedules,
        "ownership" to aggregate.ownership.takeIf { it != Ownership.OWNER }?.let(WireNames::of),
        "retired" to aggregate.retired.ifEmpty { null },
        "idSource" to aggregate.idSource.takeIf { it != AggregateIdSource.ASSIGNED }?.let(WireNames::of),
        "access" to aggregate.access.ifEmpty { null }?.map {
            obj("name" to it.name, "type" to WireNames.of(it.type), "multiple" to it.multiple.takeIf { multiple -> multiple })
        },
    )

    private fun writeRoute(route: RouteManifest): Map<String, Any?> =
        obj("method" to route.method, "path" to route.path)

    private fun writeSubscription(subscription: SubscriptionManifest): Map<String, Any?> = obj(
        "name" to subscription.name,
        "kind" to WireNames.of(subscription.kind),
        "source" to obj(
            "context" to subscription.source.context,
            "aggregates" to subscription.source.aggregates,
            "events" to subscription.source.events,
            "eventSchemas" to subscription.source.eventSchemas.ifEmpty { null },
            "nullableState" to subscription.source.nullableState.takeIf { it },
        ),
        "function" to obj(
            "kind" to WireNames.of(subscription.function.kind),
            "context" to subscription.function.context,
            "processor" to subscription.function.processor,
            "name" to subscription.function.name,
        ),
        "derives" to subscription.derives,
        "rejected" to subscription.rejected,
        "committed" to subscription.committed,
        "calls" to subscription.calls,
        "start" to subscription.start?.let(WireNames::of),
        "schedules" to subscription.schedules,
        "retry" to subscription.retry?.let {
            obj(
                "maxAttempts" to it.maxAttempts,
                "initialBackoff" to it.initialBackoff,
                "maxBackoff" to it.maxBackoff,
                "timeout" to it.timeout,
                "recoverable" to it.recoverable,
                "unrecoverable" to it.unrecoverable,
                "systemic" to it.systemic,
                "multiplier" to it.multiplier,
            )
        },
        "job" to subscription.job?.let {
            obj(
                "name" to it.name,
                "cron" to it.cron,
                "zone" to it.zone,
                "scope" to WireNames.of(it.scope),
                "misfire" to WireNames.of(it.misfire),
            )
        },
    )

    /** A JSON object of the non-null [entries], in their order. */
    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> =
        entries.filter { it.second != null }.toMap(LinkedHashMap())

    /**
     * Reads [tree], the JSON object of a Manifest; fails on a missing field, another [Manifest.FORMAT], a marker of
     * `requires` this reader does not understand ([ManifestFeatures]), a context whose name breaks [ContextName], or an
     * access attribute whose name breaks [AccessAttributeName].
     *
     * A value it does not know of an enumeration, which a newer writer may have added, leaves out what it belongs to,
     * told to [warn]: a command of an unknown `creation`, an aggregate of an unknown `ownership`, `idSource` or access
     * attribute `type`, a subscription of an unknown `kind`, function `kind` (a built-in one too), `start`, or job
     * `scope` or `misfire`. Each of them decides behaviour (how a command routes, whom an aggregate is scoped to and
     * who may see it, how a subscription runs), so a reader never guesses it: it reads what it understands and leaves
     * out what it does not. No enumeration of the format is merely descriptive.
     */
    fun read(tree: Map<*, *>, warn: (String) -> Unit = {}): Manifest {
        val root = Node(tree, "manifest", warn)
        val format = root.int("format")
        require(format == Manifest.FORMAT) {
            "Manifest format $format is not supported; this reader reads format ${Manifest.FORMAT}."
        }
        val requires = root.stringsOrNull("requires").orEmpty()
        (requires - ManifestFeatures.KNOWN).takeIf { it.isNotEmpty() }?.let { unknown ->
            throw IllegalArgumentException(
                "The Manifest requires $unknown, which this reader does not understand: read it with a newer Wow."
            )
        }
        return Manifest(
            format = format,
            requires = requires,
            service = root.objOrNull("service")?.let { ServiceInfo(it.string("name"), it.stringOrNull("version")) },
            contexts = root.objects("contexts").map(::readContext),
            errors = root.objects("errors").map(::readError),
            defs = root.objOrNull(DEFS)?.map.orEmpty().entries.associate { (key, value) -> key.toString() to value },
        )
    }

    private fun readContext(node: Node): ContextManifest = ContextManifest(
        name = node.string("name").also { name ->
            ContextName.problem(name)?.let { throw IllegalArgumentException("${node.path}.name: $it") }
        },
        description = node.stringOrNull("description"),
        aggregates = node.objects("aggregates").mapNotNull(::readAggregate),
        subscriptions = node.objects("subscriptions").mapNotNull(::readSubscription),
        published = node.objects("published").map {
            PublishedManifest(
                aggregate = it.string("aggregate"),
                topic = it.string("topic"),
                includeState = it.booleanOrNull("includeState") ?: true,
            )
        },
        errors = node.objects("errors").map(::readError),
        commands = if (node.has("commands")) node.objects("commands").mapNotNull(::readCommand) else emptyList(),
        routes = node.objects("routes").mapNotNull(::readServiceRoute),
    )

    /** The service route of [node], or null, told, when a parameter's location is unknown to this reader. */
    private fun readServiceRoute(node: Node): ServiceRouteManifest? {
        val parameters = node.objects("parameters").map { parameter ->
            val location = parameter.enum("in", RouteParameterManifest.Location.entries) ?: return node.leftOut()
            RouteParameterManifest(
                name = parameter.string("name"),
                location = location,
                schema = parameter.obj("schema").map.entries.associate { (key, value) -> key.toString() to value },
                required = parameter.booleanOrNull("required") ?: (location == RouteParameterManifest.Location.PATH),
            )
        }
        return ServiceRouteManifest(
            name = node.string("name"),
            route = readRoute(node.obj("route")),
            parameters = parameters,
            request = node.objOrNull("request")?.map?.entries?.associate { (key, value) -> key.toString() to value },
            response = node.objOrNull("response")?.map?.entries?.associate { (key, value) -> key.toString() to value },
            responseOptional = node.booleanOrNull("responseOptional") ?: false,
            errors = node.strings("errors"),
            description = node.stringOrNull("description"),
            deprecated = node.booleanOrNull("deprecated") ?: false,
        )
    }

    /** The command of [node], or null, told, when its creation is unknown to this reader. */
    private fun readCommand(node: Node): CommandManifest? = CommandManifest(
        name = node.string("name"),
        aliases = node.strings("aliases"),
        schema = node.string("schema"),
        creation = node.enum("creation", Creation.entries) ?: return node.leftOut(),
        produces = if (node.has("produces")) node.strings("produces") else null,
        routes = node.objects("routes").map(::readRoute),
        mounted = node.booleanOrNull("mounted") ?: false,
        transformsTo = if (node.has("transformsTo")) node.strings("transformsTo") else null,
        description = node.stringOrNull("description"),
        system = node.booleanOrNull("system") ?: false,
        idProperty = node.stringOrNull("idProperty"),
        internal = node.booleanOrNull("internal") ?: false,
    )

    private fun readRoute(node: Node): RouteManifest = RouteManifest(node.string("method"), node.string("path"))

    private fun readError(node: Node): ErrorManifest =
        node.enumOrNull("retry", Retry.entries).let { retry ->
            // A retry this reader does not know is none: it never retries what it cannot tell is safe to.
            ErrorManifest(
                node.string("code"),
                node.int("status"),
                node.stringOrNull("description"),
                retry ?: Retry.NONE,
                node.intOrNull("retryAfter").takeIf { retry != null },
            )
        }

    /**
     * The aggregate of [node], or null, told, when its ownership, its id source or the type of one of its access
     * attributes is unknown to this reader.
     */
    private fun readAggregate(node: Node): AggregateManifest? {
        val state = node.obj("state")
        val ownership = if (node.has("ownership")) {
            node.enumOrNull("ownership", Ownership.entries) ?: return node.leftOut()
        } else {
            Ownership.OWNER
        }
        val idSource = if (node.has("idSource")) {
            node.enumOrNull("idSource", AggregateIdSource.entries) ?: return node.leftOut()
        } else {
            AggregateIdSource.ASSIGNED
        }
        val access = readAccess(node) ?: return node.leftOut()
        return AggregateManifest(
            name = node.string("name"),
            description = node.stringOrNull("description"),
            state = StateManifest(
                state.string("schema"),
                state.stringOrNull("fingerprint"),
                state.stringOrNull("view")
            ),
            snapshot = node.objOrNull("snapshot").let { snapshot ->
                SnapshotManifest(
                    snapshot?.booleanOrNull("enabled") ?: true,
                    snapshot?.intOrNull("stateRevision") ?: 1,
                )
            },
            commands = node.objects("commands").mapNotNull(::readCommand),
            events = node.objects("events").map {
                EventManifest(
                    name = it.string("name"),
                    aliases = it.strings("aliases"),
                    revision = it.int("revision"),
                    schema = it.string("schema"),
                    description = it.stringOrNull("description"),
                )
            },
            queries = node.objects("queries").map {
                QueryManifest(it.string("model"), it.objects("routes").map(::readRoute))
            },
            schedules = node.stringsOrNull("schedules"),
            ownership = ownership,
            retired = if (node.has("retired")) node.strings("retired") else emptyList(),
            idSource = idSource,
            access = access,
        )
    }

    /**
     * The access attributes of the aggregate [node], none when it lists none; null when the type of one is unknown to
     * this reader. A name that breaks [AccessAttributeName] fails.
     */
    private fun readAccess(node: Node): List<AccessAttributeManifest>? = node.objects("access").map { attribute ->
        val name = attribute.string("name")
        AccessAttributeName.problem(name)?.let { throw IllegalArgumentException(it) }
        AccessAttributeManifest(
            name,
            attribute.enum("type", AccessAttributeManifest.Type.entries) ?: return null,
            attribute.booleanOrNull("multiple") ?: false,
        )
    }

    /** A subscription's `retry`, each list read only when it is written. */
    private fun readRetry(node: Node): RetryManifest = RetryManifest(
        maxAttempts = node.intOrNull("maxAttempts"),
        initialBackoff = node.stringOrNull("initialBackoff"),
        maxBackoff = node.stringOrNull("maxBackoff"),
        timeout = node.stringOrNull("timeout"),
        recoverable = if (node.has("recoverable")) node.strings("recoverable") else null,
        unrecoverable = if (node.has("unrecoverable")) node.strings("unrecoverable") else null,
        multiplier = node.doubleOrNull("multiplier"),
        systemic = if (node.has("systemic")) node.strings("systemic") else null,
    )

    /** The subscription of [node], or null, told, when one of its enumerations has a value this reader does not know. */
    private fun readSubscription(node: Node): SubscriptionManifest? {
        val source = node.obj("source")
        val function = node.obj("function")
        val kind = node.enum("kind", SubscriptionKind.entries)
        val functionKind = function.enum("kind", FunctionManifest.KINDS)
        val start = node.enumOrNull("start", SubscriptionStart.entries)
        val unknownStart = node.has("start") && start == null
        if (kind == null || functionKind == null || unknownStart) {
            return node.leftOut()
        }
        val job = node.objOrNull("job")?.let { readJob(it) ?: return node.leftOut() }
        return SubscriptionManifest(
            name = node.string("name"),
            kind = kind,
            source = SubscriptionSource(
                context = source.stringOrNull("context"),
                aggregates = source.strings("aggregates"),
                events = source.strings("events"),
                eventSchemas = source.stringsOrNull("eventSchemas").orEmpty(),
                nullableState = source.booleanOrNull("nullableState") ?: false,
            ),
            function = FunctionManifest(
                kind = functionKind,
                context = function.string("context"),
                processor = function.string("processor"),
                name = function.string("name"),
            ),
            derives = node.stringsOrNull("derives"),
            rejected = node.stringsOrNull("rejected"),
            committed = node.stringsOrNull("committed"),
            calls = node.stringsOrNull("calls"),
            start = start,
            schedules = node.stringsOrNull("schedules"),
            retry = node.objOrNull("retry")?.let(::readRetry),
            job = job,
        )
    }

    /** The job of [node], or null, told, when its `scope` or `misfire` has a value this reader does not know. */
    private fun readJob(node: Node): JobManifest? {
        val scope = if (node.has("scope")) node.enum("scope", JobScope.entries) ?: return null else JobScope.SYSTEM
        val misfire =
            if (node.has("misfire")) node.enum("misfire", Misfire.entries) ?: return null else Misfire.COALESCE
        return JobManifest(
            name = node.string("name"),
            cron = node.string("cron"),
            zone = node.stringOrNull("zone") ?: "UTC",
            scope = scope,
            misfire = misfire,
        )
    }

    /** A JSON object at [path], read with messages that say where a field is missing or of the wrong type. */
    private class Node(val map: Map<*, *>, val path: String, val warn: (String) -> Unit) {
        fun has(field: String): Boolean = map[field] != null

        /** Null, telling [warn] that this object, named by its `name`, is left out. */
        fun <T> leftOut(): T? {
            warn("$path (${stringOrNull("name")}) is left out: a value of it is unknown to this reader.")
            return null
        }

        /** The required enumeration [field] of [entries], or null, told to [warn], when its value is unknown. */
        fun <E : Enum<E>> enum(field: String, entries: List<E>): E? = string(field).let { known(field, it, entries) }

        /** The enumeration [field] of [entries]; null when it is absent, or, told to [warn], when it is unknown. */
        fun <E : Enum<E>> enumOrNull(field: String, entries: List<E>): E? =
            stringOrNull(field)?.let { known(field, it, entries) }

        private fun <E : Enum<E>> known(field: String, value: String, entries: List<E>): E? =
            entries.firstOrNull { WireNames.of(it) == value }
                ?: null.also { warn("$path.$field: $value is unknown to this reader, which reads it leniently.") }

        /** The string list [field], or null when it is absent. */
        fun stringsOrNull(field: String): List<String>? = if (has(field)) strings(field) else null

        private inline fun <reified T> typed(field: String, type: String): T? {
            val value = map[field] ?: return null
            require(value is T) { "$path.$field is not $type: $value" }
            return value
        }

        private fun <T> required(field: String, value: T?): T =
            requireNotNull(value) { "$path.$field is missing." }

        fun stringOrNull(field: String): String? = typed<String>(field, "a string")
        fun string(field: String): String = required(field, stringOrNull(field))
        fun intOrNull(field: String): Int? = typed<Number>(field, "a number")?.toInt()
        fun doubleOrNull(field: String): Double? = typed<Number>(field, "a number")?.toDouble()
        fun int(field: String): Int = required(field, intOrNull(field))
        fun booleanOrNull(field: String): Boolean? = typed<Boolean>(field, "a boolean")
        fun objOrNull(field: String): Node? =
            typed<Map<*, *>>(field, "an object")?.let { Node(it, "$path.$field", warn) }
        fun obj(field: String): Node = required(field, objOrNull(field))

        private fun list(field: String): List<*> = typed<List<*>>(field, "an array").orEmpty()

        fun strings(field: String): List<String> = list(field).mapIndexed { index, value ->
            require(value is String) { "$path.$field[$index] is not a string: $value" }
            value
        }

        fun objects(field: String): List<Node> = list(field).mapIndexed { index, value ->
            require(value is Map<*, *>) { "$path.$field[$index] is not an object: $value" }
            Node(value, "$path.$field[$index]", warn)
        }
    }
}

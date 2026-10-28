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
 * The JSON shape of a [Manifest], as a tree of plain values: a JSON object is a `Map<String, Any?>` (insertion
 * ordered when written), an array a `List<Any?>`, and a scalar a `String`, `Number`, `Boolean` or null. Any JSON
 * library writes and reads such a tree, so the Manifest's field names are defined here once and `wow-manifest` needs
 * none.
 *
 * Absent optional values (a null description, unknown `produces`, `derives`, `schedules` or `transformsTo`, a fragment's
 * service, a subscription's `retry` or `job`) are left out, not written as null, and so are a subscription source's
 * `nullableState` and a command's `mounted` when false, an aggregate's `ownership` when it is `OWNER`, the default,
 * its `retired` events when it has none, and the Manifest's `requires` when it has none.
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
    )

    private fun writeCommand(command: CommandManifest): Map<String, Any?> = obj(
        "name" to command.name,
        "aliases" to command.aliases,
        "schema" to command.schema,
        "creation" to command.creation.name,
        "produces" to command.produces,
        "routes" to command.routes.map(::writeRoute),
        "mounted" to command.mounted.takeIf { it },
        "system" to command.system.takeIf { it },
        "transformsTo" to command.transformsTo,
        "description" to command.description,
    )

    private fun writeError(error: ErrorManifest): Map<String, Any?> =
        obj("code" to error.code, "status" to error.status, "description" to error.description)

    private fun writeAggregate(aggregate: AggregateManifest): Map<String, Any?> = obj(
        "name" to aggregate.name,
        "description" to aggregate.description,
        "state" to obj("schema" to aggregate.state.schema, "fingerprint" to aggregate.state.fingerprint),
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
        "ownership" to aggregate.ownership.takeIf { it != Ownership.OWNER }?.name,
        "retired" to aggregate.retired.ifEmpty { null },
    )

    private fun writeRoute(route: RouteManifest): Map<String, Any?> =
        obj("method" to route.method, "path" to route.path)

    private fun writeSubscription(subscription: SubscriptionManifest): Map<String, Any?> = obj(
        "name" to subscription.name,
        "kind" to subscription.kind.name,
        "source" to obj(
            "context" to subscription.source.context,
            "aggregates" to subscription.source.aggregates,
            "events" to subscription.source.events,
            "eventSchemas" to subscription.source.eventSchemas.ifEmpty { null },
            "nullableState" to subscription.source.nullableState.takeIf { it },
        ),
        "function" to obj(
            "kind" to subscription.function.kind.name,
            "context" to subscription.function.context,
            "processor" to subscription.function.processor,
            "name" to subscription.function.name,
        ),
        "derives" to subscription.derives,
        "rejected" to subscription.rejected,
        "committed" to subscription.committed,
        "calls" to subscription.calls,
        "start" to subscription.start?.name,
        "schedules" to subscription.schedules,
        "retry" to subscription.retry?.let {
            obj(
                "maxAttempts" to it.maxAttempts,
                "initialBackoff" to it.initialBackoff,
                "maxBackoff" to it.maxBackoff,
                "timeout" to it.timeout,
                "recoverable" to it.recoverable,
                "unrecoverable" to it.unrecoverable,
                "multiplier" to it.multiplier,
            )
        },
        "job" to subscription.job?.let {
            obj(
                "name" to it.name,
                "cron" to it.cron,
                "zone" to it.zone,
                "scope" to it.scope,
                "sharded" to it.sharded,
                "misfire" to it.misfire,
            )
        },
    )

    /** A JSON object of the non-null [entries], in their order. */
    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> =
        entries.filter { it.second != null }.toMap(LinkedHashMap())

    /**
     * Reads [tree], the JSON object of a Manifest; fails on a missing field, another [Manifest.FORMAT], a marker of
     * `requires` this reader does not understand ([ManifestFeatures]), or a context whose name breaks [ContextName].
     *
     * A value it does not know of an enumeration, which a newer writer may have added, is read leniently and told to
     * [warn]: a command's `creation` as `NONE`, an aggregate's `ownership` as `OWNER`, and a subscription of an
     * unknown `kind`, function `kind` or `start` is left out. A reader then still reads what it understands.
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
        aggregates = node.objects("aggregates").map(::readAggregate),
        subscriptions = node.objects("subscriptions").mapNotNull(::readSubscription),
        published = node.objects("published").map {
            PublishedManifest(
                aggregate = it.string("aggregate"),
                topic = it.string("topic"),
                includeState = it.booleanOrNull("includeState") ?: true,
            )
        },
        errors = node.objects("errors").map(::readError),
        commands = if (node.has("commands")) node.objects("commands").map(::readCommand) else emptyList(),
    )

    private fun readCommand(node: Node): CommandManifest = CommandManifest(
        name = node.string("name"),
        aliases = node.strings("aliases"),
        schema = node.string("schema"),
        creation = node.enum("creation", Creation.entries) ?: Creation.NONE,
        produces = if (node.has("produces")) node.strings("produces") else null,
        routes = node.objects("routes").map(::readRoute),
        mounted = node.booleanOrNull("mounted") ?: false,
        transformsTo = if (node.has("transformsTo")) node.strings("transformsTo") else null,
        description = node.stringOrNull("description"),
        system = node.booleanOrNull("system") ?: false,
    )

    private fun readRoute(node: Node): RouteManifest = RouteManifest(node.string("method"), node.string("path"))

    private fun readError(node: Node): ErrorManifest =
        ErrorManifest(node.string("code"), node.int("status"), node.stringOrNull("description"))

    private fun readAggregate(node: Node): AggregateManifest {
        val state = node.obj("state")
        return AggregateManifest(
            name = node.string("name"),
            description = node.stringOrNull("description"),
            state = StateManifest(state.string("schema"), state.stringOrNull("fingerprint")),
            snapshot = node.objOrNull("snapshot").let { snapshot ->
                SnapshotManifest(
                    snapshot?.booleanOrNull("enabled") ?: true,
                    snapshot?.intOrNull("stateRevision") ?: 1,
                )
            },
            commands = node.objects("commands").map(::readCommand),
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
            ownership = node.enumOrNull("ownership", Ownership.entries) ?: Ownership.OWNER,
            retired = if (node.has("retired")) node.strings("retired") else emptyList(),
        )
    }

    /** The subscription of [node], or null, told, when one of its enumerations has a value this reader does not know. */
    private fun readSubscription(node: Node): SubscriptionManifest? {
        val source = node.obj("source")
        val function = node.obj("function")
        val kind = node.enum("kind", SubscriptionKind.entries)
        val functionKind = function.enum("kind", FunctionKind.entries)
        val start = node.enumOrNull("start", SubscriptionStart.entries)
        val unknownStart = node.has("start") && start == null
        if (kind == null || functionKind == null || unknownStart) {
            node.warn(
                "${node.path} (${node.stringOrNull("name")}) is left out: a value of it is unknown to this reader."
            )
            return null
        }
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
            retry = node.objOrNull("retry")?.let {
                RetryManifest(
                    maxAttempts = it.intOrNull("maxAttempts"),
                    initialBackoff = it.stringOrNull("initialBackoff"),
                    maxBackoff = it.stringOrNull("maxBackoff"),
                    timeout = it.stringOrNull("timeout"),
                    recoverable = if (it.has("recoverable")) it.strings("recoverable") else null,
                    unrecoverable = if (it.has("unrecoverable")) it.strings("unrecoverable") else null,
                    multiplier = it.doubleOrNull("multiplier"),
                )
            },
            job = node.objOrNull("job")?.let {
                JobManifest(
                    name = it.string("name"),
                    cron = it.string("cron"),
                    zone = it.stringOrNull("zone") ?: "UTC",
                    scope = it.stringOrNull("scope") ?: "SYSTEM",
                    sharded = it.booleanOrNull("sharded") ?: false,
                    misfire = it.stringOrNull("misfire") ?: "COALESCE",
                )
            },
        )
    }

    /** A JSON object at [path], read with messages that say where a field is missing or of the wrong type. */
    private class Node(val map: Map<*, *>, val path: String, val warn: (String) -> Unit) {
        fun has(field: String): Boolean = map[field] != null

        /** The required enumeration [field] of [entries], or null, told to [warn], when its value is unknown. */
        fun <E : Enum<E>> enum(field: String, entries: List<E>): E? = string(field).let { known(field, it, entries) }

        /** The enumeration [field] of [entries]; null when it is absent, or, told to [warn], when it is unknown. */
        fun <E : Enum<E>> enumOrNull(field: String, entries: List<E>): E? =
            stringOrNull(field)?.let { known(field, it, entries) }

        private fun <E : Enum<E>> known(field: String, value: String, entries: List<E>): E? =
            entries.firstOrNull { it.name == value }
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

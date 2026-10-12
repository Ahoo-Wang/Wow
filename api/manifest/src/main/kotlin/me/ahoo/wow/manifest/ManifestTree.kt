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
 * Absent optional values (a null description, unknown `produces`, a fragment's service) are left out, not written as
 * null.
 */
object ManifestTree {
    private const val DEFS = "\$defs"

    fun write(manifest: Manifest): Map<String, Any?> = obj(
        "format" to manifest.format,
        "service" to manifest.service?.let { obj("name" to it.name, "version" to it.version) },
        "contexts" to manifest.contexts.map(::writeContext),
        DEFS to manifest.defs,
    )

    private fun writeContext(context: ContextManifest): Map<String, Any?> = obj(
        "name" to context.name,
        "description" to context.description,
        "aggregates" to context.aggregates.map(::writeAggregate),
        "subscriptions" to context.subscriptions.map(::writeSubscription),
    )

    private fun writeAggregate(aggregate: AggregateManifest): Map<String, Any?> = obj(
        "name" to aggregate.name,
        "description" to aggregate.description,
        "state" to obj("schema" to aggregate.state.schema, "fingerprint" to aggregate.state.fingerprint),
        "snapshot" to obj("enabled" to aggregate.snapshot.enabled),
        "commands" to aggregate.commands.map {
            obj(
                "name" to it.name,
                "aliases" to it.aliases,
                "schema" to it.schema,
                "creation" to it.creation.name,
                "produces" to it.produces,
            )
        },
        "events" to aggregate.events.map {
            obj("name" to it.name, "aliases" to it.aliases, "revision" to it.revision, "schema" to it.schema)
        },
    )

    private fun writeSubscription(subscription: SubscriptionManifest): Map<String, Any?> = obj(
        "name" to subscription.name,
        "kind" to subscription.kind.name,
        "source" to obj(
            "context" to subscription.source.context,
            "aggregates" to subscription.source.aggregates,
            "events" to subscription.source.events,
        ),
        "function" to obj(
            "kind" to subscription.function.kind.name,
            "context" to subscription.function.context,
            "processor" to subscription.function.processor,
            "name" to subscription.function.name,
        ),
    )

    /** A JSON object of the non-null [entries], in their order. */
    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> =
        entries.filter { it.second != null }.toMap(LinkedHashMap())

    /** Reads [tree], the JSON object of a Manifest; fails on a missing field or another [Manifest.FORMAT]. */
    fun read(tree: Map<*, *>): Manifest {
        val root = Node(tree, "manifest")
        val format = root.int("format")
        require(format == Manifest.FORMAT) {
            "Manifest format $format is not supported; this reader reads format ${Manifest.FORMAT}."
        }
        return Manifest(
            format = format,
            service = root.objOrNull("service")?.let { ServiceInfo(it.string("name"), it.stringOrNull("version")) },
            contexts = root.objects("contexts").map(::readContext),
            defs = root.objOrNull(DEFS)?.map.orEmpty().entries.associate { (key, value) -> key.toString() to value },
        )
    }

    private fun readContext(node: Node): ContextManifest = ContextManifest(
        name = node.string("name"),
        description = node.stringOrNull("description"),
        aggregates = node.objects("aggregates").map(::readAggregate),
        subscriptions = node.objects("subscriptions").map(::readSubscription),
    )

    private fun readAggregate(node: Node): AggregateManifest {
        val state = node.obj("state")
        return AggregateManifest(
            name = node.string("name"),
            description = node.stringOrNull("description"),
            state = StateManifest(state.string("schema"), state.stringOrNull("fingerprint")),
            snapshot = SnapshotManifest(node.objOrNull("snapshot")?.booleanOrNull("enabled") ?: true),
            commands = node.objects("commands").map {
                CommandManifest(
                    name = it.string("name"),
                    aliases = it.strings("aliases"),
                    schema = it.string("schema"),
                    creation = Creation.valueOf(it.string("creation")),
                    produces = if (it.has("produces")) it.strings("produces") else null,
                )
            },
            events = node.objects("events").map {
                EventManifest(
                    name = it.string("name"),
                    aliases = it.strings("aliases"),
                    revision = it.int("revision"),
                    schema = it.string("schema"),
                )
            },
        )
    }

    private fun readSubscription(node: Node): SubscriptionManifest {
        val source = node.obj("source")
        val function = node.obj("function")
        return SubscriptionManifest(
            name = node.string("name"),
            kind = SubscriptionKind.valueOf(node.string("kind")),
            source = SubscriptionSource(
                context = source.stringOrNull("context"),
                aggregates = source.strings("aggregates"),
                events = source.strings("events"),
            ),
            function = FunctionManifest(
                kind = FunctionKind.valueOf(function.string("kind")),
                context = function.string("context"),
                processor = function.string("processor"),
                name = function.string("name"),
            ),
        )
    }

    /** A JSON object at [path], read with messages that say where a field is missing or of the wrong type. */
    private class Node(val map: Map<*, *>, val path: String) {
        fun has(field: String): Boolean = map[field] != null

        private inline fun <reified T> typed(field: String, type: String): T? {
            val value = map[field] ?: return null
            require(value is T) { "$path.$field is not $type: $value" }
            return value
        }

        private fun <T> required(field: String, value: T?): T =
            requireNotNull(value) { "$path.$field is missing." }

        fun stringOrNull(field: String): String? = typed<String>(field, "a string")
        fun string(field: String): String = required(field, stringOrNull(field))
        fun int(field: String): Int = required(field, typed<Number>(field, "a number")).toInt()
        fun booleanOrNull(field: String): Boolean? = typed<Boolean>(field, "a boolean")
        fun objOrNull(field: String): Node? = typed<Map<*, *>>(field, "an object")?.let { Node(it, "$path.$field") }
        fun obj(field: String): Node = required(field, objOrNull(field))

        private fun list(field: String): List<*> = typed<List<*>>(field, "an array").orEmpty()

        fun strings(field: String): List<String> = list(field).mapIndexed { index, value ->
            require(value is String) { "$path.$field[$index] is not a string: $value" }
            value
        }

        fun objects(field: String): List<Node> = list(field).mapIndexed { index, value ->
            require(value is Map<*, *>) { "$path.$field[$index] is not an object: $value" }
            Node(value, "$path.$field[$index]")
        }
    }
}

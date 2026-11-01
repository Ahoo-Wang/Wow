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

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** A 9.x aggregate as its streams name it: `contextName.aggregateName`. */
internal data class NineXAggregate(val context: String, val name: String) {
    init {
        require(context.isNotBlank()) { "A 9.x aggregate needs a context name." }
        require(name.isNotBlank()) { "A 9.x aggregate needs an aggregate name." }
    }

    override fun toString(): String = "$context.$name"
}

/** One 9.x aggregate instance: its id within its tenant. */
internal data class NineXAggregateId(val id: String, val tenantId: String) : Comparable<NineXAggregateId> {
    override fun compareTo(other: NineXAggregateId): Int = compareValuesBy(this, other, { it.id }, { it.tenantId })

    override fun toString(): String = "$tenantId/$id"
}

/** One event of a 9.x stream: its [name] and 9.x [revision] (`0.0.1` unless `@Event` said otherwise), and [body]. */
internal data class NineXEvent(val id: String, val name: String, val revision: String, val body: JsonNode)

/**
 * One 9.x event stream, as the 9.x `EventStreamJsonSerializer` writes it and its event stores keep it: a MongoDB
 * document of `<aggregate>_event_stream` (its `id` stored as `_id`), or a Redis sorted-set member.
 *
 * Read by its storage format only, never through 9.x classes. [ownerId] and [spaceId] are blank when
 * 9.x recorded none.
 */
internal data class NineXStream(
    val id: String,
    val aggregate: NineXAggregate,
    val aggregateId: NineXAggregateId,
    val ownerId: String,
    val spaceId: String,
    val commandId: String,
    val requestId: String,
    val version: Int,
    val header: Map<String, String>,
    val events: List<NineXEvent>,
    val createTime: Long,
) {
    /**
     * The 9.x name of the command that produced it, the `upstream_name` header (an older 9.x's `upstream.name` or
     * `command.name`, [NineXHeaders.canonical]); null when it recorded none.
     */
    val commandName: String?
        get() = header[NineXHeaders.UPSTREAM_NAME]?.takeIf { it.isNotBlank() }

    companion object {
        /** Reads the JSON [text] of a stream. */
        fun parse(text: String): NineXStream = parse(WowJson.mapper.readTree(text))

        /**
         * Reads a stream from its JSON [node]; its id is `id`, or `_id` in a MongoDB document. A field the stream
         * needs and lacks fails with a [MigrationException] naming it.
         */
        fun parse(node: JsonNode): NineXStream {
            require(node is ObjectNode) { "A 9.x event stream is a JSON object." }
            val id = node.textOf("_id") ?: node.requiredText("id")
            return NineXStream(
                id = id,
                aggregate = NineXAggregate(node.requiredText("contextName"), node.requiredText("aggregateName")),
                aggregateId = NineXAggregateId(node.requiredText("aggregateId"), node.requiredText("tenantId")),
                ownerId = node.textOf("ownerId").orEmpty(),
                spaceId = node.textOf("spaceId").orEmpty(),
                commandId = node.requiredText("commandId"),
                requestId = node.textOf("requestId").orEmpty(),
                version = node.requiredNumber("version").asInt(),
                header = NineXHeaders.canonical(headerOf(node)),
                events = eventsOf(node, id),
                createTime = node.requiredNumber("createTime").asLong(),
            )
        }

        private fun eventsOf(node: JsonNode, id: String): List<NineXEvent> {
            val events = node.get("body")?.takeIf { it.isArray && it.size() > 0 }
                ?: throw MigrationException("9.x stream $id has no events.")
            return events.toList().map { event ->
                NineXEvent(
                    id = event.textOf("id").orEmpty(),
                    name = event.textOf("name") ?: throw MigrationException("An event of 9.x stream $id has no name."),
                    revision = event.textOf("revision") ?: DEFAULT_REVISION,
                    body = event.get("body") ?: WowJson.mapper.createObjectNode(),
                )
            }
        }

        /** The revision of a 9.x event declared without one (9.x `DEFAULT_REVISION`). */
        const val DEFAULT_REVISION: String = "0.0.1"

        private fun headerOf(node: JsonNode): Map<String, String> =
            node.get("header")?.properties()?.associate { (key, value) -> key to value.asString() }.orEmpty()

        private fun JsonNode.textOf(field: String): String? = get(field)?.takeIf { it.isString }?.asString()

        private fun JsonNode.requiredText(field: String): String = textOf(field)?.takeIf { it.isNotBlank() }
            ?: throw MigrationException("9.x stream ${textOf("_id") ?: textOf("id")} has no $field.")

        private fun JsonNode.requiredNumber(field: String): JsonNode = get(field)?.takeIf { it.isIntegralNumber }
            ?: throw MigrationException("9.x stream ${textOf("_id") ?: textOf("id")} has no $field.")
    }
}

/** The 9.x framework headers of a stream: none is a request fact. */
internal object NineXHeaders {
    /** The id of the root command of the chain (9.x `TraceMessagePropagator`): the V10 correlation id. */
    const val TRACE_ID: String = "trace_id"

    /** The id of the message the stream came from; on a stream, its command's id. */
    const val UPSTREAM_ID: String = "upstream_id"

    /** The name of the command the stream came from. */
    const val UPSTREAM_NAME: String = "upstream_name"

    /** The wait plan of the command (9.x `command_wait_*`): transport, dropped. */
    const val WAIT_PREFIX: String = "command_wait_"

    /** A 9.x local-first delivery mark: transport, dropped. */
    const val LOCAL_FIRST: String = "local_first"

    /** The framework headers besides the wait plan's. */
    val FRAMEWORK: Set<String> = setOf(TRACE_ID, UPSTREAM_ID, UPSTREAM_NAME, LOCAL_FIRST, NineXOperators.HEADER)

    /** The name of the command in a stream of an older 9.x that wrote it alone, without `upstream.name`. */
    const val COMMAND_NAME: String = "command.name"

    /** [UPSTREAM_NAME] as an older 9.x wrote it. */
    const val DOTTED_UPSTREAM_NAME: String = "upstream.name"

    /** The framework headers an older 9.x wrote with dots, by the name they have now. */
    private val DOTTED: Map<String, String> = mapOf(
        "trace.id" to TRACE_ID,
        "upstream.id" to UPSTREAM_ID,
        DOTTED_UPSTREAM_NAME to UPSTREAM_NAME,
        "command.operator" to NineXOperators.HEADER,
    )
    private const val DOTTED_WAIT_PREFIX = "command.wait."

    /** The name [key] has now: an older 9.x's dotted framework header by its underscore name, any other as it is. */
    fun nameOf(key: String): String = DOTTED[key]
        ?: if (key.startsWith(DOTTED_WAIT_PREFIX)) WAIT_PREFIX + key.removePrefix(DOTTED_WAIT_PREFIX) else key

    /** Whether [key] is a 9.x framework header, in either form. */
    fun isFramework(key: String): Boolean =
        key == COMMAND_NAME || nameOf(key).let { it in FRAMEWORK || it.startsWith(WAIT_PREFIX) }

    /**
     * [header] with its framework headers by the names they have now ([nameOf]); a header written in both forms keeps
     * the underscore one, and [COMMAND_NAME] names the command only where neither `upstream` form does.
     */
    fun canonical(header: Map<String, String>): Map<String, String> {
        val (current, older) = header.entries.filterNot { it.key == COMMAND_NAME }
            .partition { nameOf(it.key) == it.key }
        val canonical = current.associateTo(LinkedHashMap()) { it.key to it.value }
        older.forEach { canonical.putIfAbsent(nameOf(it.key), it.value) }
        header[COMMAND_NAME]?.let { canonical.putIfAbsent(UPSTREAM_NAME, it) }
        return canonical
    }
}

/** The migration cannot go on: a mapping, a stream or the target is not what the import needs. */
internal class MigrationException(message: String) : IllegalStateException(message)

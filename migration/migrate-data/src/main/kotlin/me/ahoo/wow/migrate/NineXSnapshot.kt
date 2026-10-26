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

/**
 * A 9.x snapshot, read by its storage format (decision D85: no 9.x module), as the 9.x `SnapshotSerializer` writes
 * it: `contextName`, `aggregateName`, `aggregateId` (`_id` in a MongoDB document), `tenantId`, `ownerId`, `spaceId`,
 * `version`, `eventId`, `firstOperator`, `operator`, `firstEventTime`, `eventTime`, `state`, `snapshotTime`, `tags`
 * and `deleted`. The comparison reads the identity, the version, the scope, the tags, the deleted mark and the state.
 */
internal data class NineXSnapshot(
    val aggregate: NineXAggregate,
    val aggregateId: NineXAggregateId,
    val version: Int,
    val ownerId: String,
    val spaceId: String,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
    val state: JsonNode,
) {
    companion object {
        /** Reads the JSON [text] of a snapshot. */
        fun parse(text: String): NineXSnapshot = parse(WowJson.mapper.readTree(text))

        /**
         * Reads a snapshot from its JSON [node]; its aggregate id is `aggregateId`, or `_id` in a MongoDB document. A
         * field it needs and lacks fails with a [MigrationException] naming it.
         */
        fun parse(node: JsonNode): NineXSnapshot {
            require(node is ObjectNode) { "A 9.x snapshot is a JSON object." }
            val id = node.text("_id") ?: node.text("aggregateId")
            val subject = id?.let { "9.x snapshot $it" } ?: "A 9.x snapshot"
            fun <T : Any> required(field: String, value: T?): T =
                value ?: throw MigrationException("$subject has no $field.")
            fun text(field: String): String = required(field, node.text(field)?.takeIf { it.isNotBlank() })
            return NineXSnapshot(
                aggregate = NineXAggregate(text("contextName"), text("aggregateName")),
                aggregateId = NineXAggregateId(required("aggregateId", id), text("tenantId")),
                version = required("version", node.get("version")?.takeIf { it.isIntegralNumber }?.asInt()),
                ownerId = node.text("ownerId").orEmpty(),
                spaceId = node.text("spaceId").orEmpty(),
                deleted = node.get("deleted")?.asBoolean() ?: false,
                tags = tagsOf(node.get("tags")),
                state = required("state", node.get("state")?.takeIf { !it.isNull }),
            )
        }

        private fun tagsOf(node: JsonNode?): Map<String, List<String>> =
            node?.takeIf { it.isObject }?.properties()?.associate { (key, values) ->
                key to values.toList().map { it.asString() }
            }.orEmpty()

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()
    }
}

/**
 * The 9.x snapshots, read by the state comparison (design 07 §3 step 4). The MongoDB store is the only 9.x source
 * (decision D213): [MongoNineXSnapshotSource].
 */
internal interface NineXSnapshotSource {
    /**
     * At most [limit] snapshots of [aggregate] after the instance [after] (from the first when null), in aggregate id
     * order.
     */
    fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot>

    /** How many instances of [aggregate] the 9.x event store holds: the base of the share that has a snapshot. */
    fun instances(aggregate: NineXAggregate): Long
}

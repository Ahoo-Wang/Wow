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
 * A 9.x snapshot, read by its storage format (no 9.x module), as the 9.x `SnapshotSerializer` writes
 * it: `contextName`, `aggregateName`, `aggregateId` (`_id` in a MongoDB document), `tenantId`, `ownerId`, `spaceId`,
 * `version`, `eventId`, `firstOperator`, `operator`, `firstEventTime`, `eventTime`, `state`, `snapshotTime`, `tags`
 * and `deleted`. The comparison reads the identity, the version, the scope, the tags, the deleted mark and the state;
 * a comparison again also the [snapshotTime], when the 9.x node that took it wrote it, by its clock (null when the
 * document has none).
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
    val snapshotTime: Long? = null,
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
                aggregateId = NineXAggregateId(required("aggregateId", id), node.text("tenantId").orEmpty()),
                version = required("version", node.get("version")?.takeIf { it.isIntegralNumber }?.asInt()),
                ownerId = node.text("ownerId").orEmpty(),
                spaceId = node.text("spaceId").orEmpty(),
                deleted = node.get("deleted")?.asBoolean() ?: false,
                tags = NineXTags.of(node.get("tags")).orEmpty(),
                state = required("state", node.get("state")?.takeIf { !it.isNull }),
                snapshotTime = node.get("snapshotTime")?.takeIf { it.isIntegralNumber }?.asLong(),
            )
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()
    }
}

/**
 * The 9.x snapshots, read by the state comparison (step 4). The MongoDB store is the only 9.x source:
 * [MongoNineXSnapshotSource].
 */
internal interface NineXSnapshotSource {
    /**
     * At most [limit] snapshots of [aggregate] after the instance [after] (from the first when null), in aggregate id
     * order.
     */
    fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot>

    /** The snapshot of [aggregate]'s instance [id], or null when 9.x has none. */
    fun snapshot(aggregate: NineXAggregate, id: String): NineXSnapshot?

    /**
     * At most [limit] snapshots of [aggregate] after the instance [after] (from the first when null), in aggregate id
     * order, that 9.x took at or after [from] by their `snapshotTime`, or that have none: what a comparison again reads
     * of the 9.x side.
     */
    fun takenSince(aggregate: NineXAggregate, from: Long, after: String?, limit: Int): List<NineXSnapshot>

    /** How many instances of [aggregate] the 9.x event store holds: the base of the share that has a snapshot. */
    fun instances(aggregate: NineXAggregate): Long

    /** How many snapshots of [aggregate] mark their instance deleted. */
    fun deleted(aggregate: NineXAggregate): Long

    /**
     * The 9.x tag keys [aggregate]'s snapshots hold ([NineXSnapshot.tags]), each with its values' counts: the tags
     * 9.x applied, then derived. A store counts them in one scan; this one reads every snapshot.
     */
    fun tags(aggregate: NineXAggregate): Map<String, TagValueStats> {
        val tags = LinkedHashMap<String, TagValueStats>()
        snapshotPages(aggregate) { snapshot ->
            snapshot.tags.forEach { (key, values) -> tags.merge(key, TagValueStats.of(values), TagValueStats::plus) }
            true
        }
        return tags
    }

    /** At most [limit] ids of [aggregate]'s instances whose snapshot holds the values of [key] [problem] names. */
    fun tagSamples(aggregate: NineXAggregate, key: String, problem: TagValueProblem, limit: Int): List<String> {
        val ids = ArrayList<String>()
        snapshotPages(aggregate) { snapshot ->
            snapshot.tags[key]?.takeIf { TagValues.holds(problem, it) }?.let { ids += snapshot.aggregateId.id }
            ids.size < limit
        }
        return ids
    }

    /** Hands [aggregate]'s snapshots to [each], in id order, until it returns false. */
    private fun snapshotPages(aggregate: NineXAggregate, each: (NineXSnapshot) -> Boolean) {
        var after: NineXAggregateId? = null
        while (true) {
            val page = snapshots(aggregate, after, SNAPSHOT_PAGE)
            if (page.isEmpty() || !page.all(each)) {
                return
            }
            after = page.last().aggregateId
        }
    }
}

/** The snapshots [NineXSnapshotSource]'s own tag counts read at once. */
private const val SNAPSHOT_PAGE = 500

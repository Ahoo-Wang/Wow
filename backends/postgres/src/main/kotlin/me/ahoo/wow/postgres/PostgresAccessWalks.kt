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

package me.ahoo.wow.postgres

import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.json.WowJson

/**
 * The registry's `access_walk` column ([PostgresEventLogTables.REGISTRY]): a walk as one JSON object, so an operator
 * reads how far it is in place: `{"schema": …, "heads": {"0": 41, …}, "cursor": …, "walked": 12000,
 * "completed": false}`.
 */
internal object PostgresAccessWalks {
    private val json = WowJson.mapper

    fun textOf(walk: AccessWalk): String {
        val node = json.createObjectNode().put(SCHEMA, walk.schema)
        val heads = node.putObject(HEADS)
        walk.heads.toSortedMap().forEach { (partition, head) -> heads.put(partition.toString(), head) }
        node.put(CURSOR, walk.cursor).put(WALKED, walk.walked).put(COMPLETED, walk.completed)
        return json.writeValueAsString(node)
    }

    /** The progress [PostgresEventLogTables.advanceWalk] merges into a walk. */
    fun progressOf(cursor: String?, walked: Long, completed: Boolean): String {
        val node = json.createObjectNode().put(CURSOR, cursor).put(WALKED, walked).put(COMPLETED, completed)
        return json.writeValueAsString(node)
    }

    fun walkOf(text: String): AccessWalk {
        val node = json.readTree(text)
        return AccessWalk(
            schema = node.get(SCHEMA).asString(),
            heads = node.get(HEADS).properties().associate { (partition, head) -> partition.toInt() to head.asLong() },
            cursor = node.get(CURSOR)?.takeUnless { it.isNull }?.asString(),
            walked = node.get(WALKED)?.asLong() ?: 0,
            completed = node.get(COMPLETED)?.asBoolean() ?: false,
        )
    }

    private const val SCHEMA = "schema"
    private const val HEADS = "heads"
    private const val CURSOR = "cursor"
    private const val WALKED = "walked"
    private const val COMPLETED = "completed"
}

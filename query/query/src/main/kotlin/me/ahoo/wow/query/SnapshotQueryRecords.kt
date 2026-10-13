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

@file:JvmName("SnapshotQueryRecords")

package me.ahoo.wow.query

import me.ahoo.wow.query.schema.SnapshotFields
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.node.ObjectNode

/**
 * This snapshot as the record a snapshot query reads and returns (decision D128): the [SnapshotFields] in their
 * order, an absent owner or space as `null`, and the state parsed as JSON under [SnapshotFields.STATE]. Every snapshot
 * backend returns records of this shape, whatever its storage keeps.
 */
fun SnapshotRecord.toQueryRecord(): ObjectNode = newRecord().apply {
    put(SnapshotFields.TENANT_ID, ref.tenantId)
    put(SnapshotFields.AGGREGATE_ID, ref.id)
    put(SnapshotFields.VERSION, version)
    put(SnapshotFields.OWNER_ID, scope.ownerId)
    put(SnapshotFields.SPACE_ID, scope.spaceId)
    put(SnapshotFields.DELETED, deleted)
    set(
        SnapshotFields.TAGS,
        newRecord().also { tags ->
            this@toQueryRecord.tags.forEach { (name, values) ->
                tags.set(name, jsonNodes.arrayNode().also { array -> values.forEach(array::add) })
            }
        },
    )
    put(SnapshotFields.FIRST_EVENT_TIME, firstEventTime)
    put(SnapshotFields.LAST_EVENT_TIME, lastEventTime)
    set(SnapshotFields.STATE, WowJson.mapper.readTree(state))
}

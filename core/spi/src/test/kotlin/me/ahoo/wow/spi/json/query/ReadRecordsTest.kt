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

package me.ahoo.wow.spi.json.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** A snapshot as the record a snapshot query and a state read answer, and back. */
class ReadRecordsTest {
    private val type = AggregateType("shop", "cart")
    private val ref = type.ref("acme", "c-1")
    private val state: JsonNode = WowJson.mapper.createObjectNode().put("items", 2)

    @Test
    fun `a snapshot should be its record and read back, an anonymous operator as null`() {
        val snapshot = Snapshot.of(
            ref,
            3,
            Scope("alice", null),
            mapOf("region" to listOf("east")),
            false,
            null,
            10,
            20,
            state,
            Operator.of("creator", "acme"),
            mapOf("remind" to ScheduleRule.At.of(5)),
        )
        val record = snapshot.toRecord()
        record[SnapshotFields.OPERATOR].isNull.assert().isTrue()
        record[SnapshotFields.FIRST_OPERATOR][OperatorFields.ID].asString().assert().isEqualTo("creator")
        record[SnapshotFields.SPACE_ID].isNull.assert().isTrue()
        record.toSnapshot(type, JsonNode::class.java).assert().isEqualTo(snapshot)
    }

    @Test
    fun `a record without operators or schedules should read as none`() {
        val record = WowJson.mapper.createObjectNode()
            .put(SnapshotFields.TENANT_ID, "acme")
            .put(SnapshotFields.AGGREGATE_ID, "c-1")
            .put(SnapshotFields.VERSION, 1)
            .put(SnapshotFields.DELETED, false)
            .put(SnapshotFields.FIRST_EVENT_TIME, 1)
            .put(SnapshotFields.LAST_EVENT_TIME, 2)
        record.set(SnapshotFields.STATE, state)
        val read = record.toSnapshot(type, JsonNode::class.java)
        read.operator.assert().isNull()
        read.firstOperator.assert().isNull()
        read.schedules.assert().isEmpty()
        read.tags.assert().isEmpty()
        read.scope.assert().isEqualTo(Scope.NONE)
        // An explicit null too.
        record.putNull(SnapshotFields.SCHEDULES)
        record.toSnapshot(type, JsonNode::class.java).schedules.assert().isEmpty()
    }
}

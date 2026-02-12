/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License,Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.r2dbc

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test

internal class SimpleSnapshotSchemaTest {
    private val namedAggregate = MaterializedNamedAggregate("", "test")
    private val streamSchema = SimpleSnapshotSchema()

    @Test
    fun load() {
        streamSchema.load(namedAggregate.aggregateId("")).assert().isEqualTo(
            "select * from test_snapshot where aggregate_id=? order by version desc limit 1"
        )
    }

    @Test
    fun loadVersion() {
        streamSchema.loadByVersion(namedAggregate.aggregateId("")).assert().isEqualTo(
            "select * from test_snapshot where aggregate_id=? and version=?"
        )
    }

    @Test
    fun save() {
        streamSchema.save(namedAggregate.aggregateId("")).assert().isEqualTo(
            """
     replace into test_snapshot
     (aggregate_id,tenant_id,owner_id,space_id,version,state_type,state,event_id,first_operator,operator,first_event_time,event_time,snapshot_time,deleted)
     values 
     (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
     """.trim(),
        )
    }
}

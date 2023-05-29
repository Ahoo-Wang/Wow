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

import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class SimpleSnapshotSchemaTest {
    private val namedAggregate = MaterializedNamedAggregate("", "test")
    private val streamSchema = SimpleSnapshotSchema()

    @Test
    fun load() {
        assertThat(
            streamSchema.load(namedAggregate.asAggregateId("")),
            equalTo("select * from test_snapshot where aggregate_id=? order by version desc limit 1"),
        )
    }

    @Test
    fun loadVersion() {
        assertThat(
            streamSchema.loadByVersion(namedAggregate.asAggregateId("")),
            equalTo("select * from test_snapshot where aggregate_id=? and version=?"),
        )
    }

    @Test
    fun save() {
        assertThat(
            streamSchema.save(namedAggregate.asAggregateId("")),
            equalTo(
                """
     replace into test_snapshot
     (aggregate_id,tenant_id,version,state_type,state,last_event_id,last_event_time,snapshot_time,deleted)
     values 
     (?,?,?,?,?,?,?,?,?)
     """.trim(),
            ),
        )
    }
}

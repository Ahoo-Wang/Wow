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

internal class SimpleEventStreamSchemaTest {
    private val namedAggregate = MaterializedNamedAggregate("test", "test")
    private val eventStreamSchema = SimpleEventStreamSchema()

    @Test
    fun loadEventStream() {
        eventStreamSchema.load(namedAggregate.aggregateId("")).assert().isEqualTo(
            "select * from test_event_stream where aggregate_id=? and version between ? and ? order by version"
        )
    }

    @Test
    fun appendEventStream() {
        eventStreamSchema.append(namedAggregate.aggregateId("")).assert().isEqualTo(
            """
        insert into test_event_stream (id,aggregate_id,tenant_id,owner_id,request_id,command_id,version,header,body,size,create_time) 
        values
        (?,?,?,?,?,?,?,?,?,?,?)
    """.trim(),
        )
    }
}

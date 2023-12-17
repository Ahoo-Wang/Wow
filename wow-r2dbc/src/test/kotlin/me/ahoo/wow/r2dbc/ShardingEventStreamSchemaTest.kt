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

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.sharding.CosIdShardingDecorator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class ShardingEventStreamSchemaTest {

    private val namedAggregate = MaterializedNamedAggregate("test", "ShardingEventStreamSchemaTest")
    private val eventStreamSharding =
        CosIdShardingDecorator(ModCycle(4, "test_event_stream_"))

    private val eventStreamSchema = ShardingEventStreamSchema(eventStreamSharding)

    @Test
    fun load() {
        assertThat(
            eventStreamSchema.load(namedAggregate.aggregateId("0TEC7cEx0001001")),
            equalTo("select * from test_event_stream_1 where aggregate_id=? and version between ? and ?"),
        )
    }

    @Test
    fun append() {
        assertThat(
            eventStreamSchema.append(namedAggregate.aggregateId("0TEC7cEx0001002")),
            equalTo(
                """
        insert into test_event_stream_2 (id,aggregate_id,tenant_id,request_id,command_id,version,header,body,size,create_time) 
        values
        (?,?,?,?,?,?,?,?,?,?)
    """.trim(),
            ),
        )
    }
}

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

package me.ahoo.wow.r2dbc

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.sharding.AggregateIdSharding
import java.util.concurrent.ConcurrentHashMap

const val EVENT_STREAM_TABLE = "event_stream"
const val EVENT_STREAM_LOGIC_NAME_PREFIX = EVENT_STREAM_TABLE + "_"

data class EventStreamStatement(
    val load: String,
    val loadByEventTime: String,
    val append: String,
    val last: String
)

object EventStreamStatementGenerator {
    private val statements = ConcurrentHashMap<String, EventStreamStatement>()

    fun generate(namedAggregate: NamedAggregate): EventStreamStatement {
        val tableName = "${namedAggregate.aggregateName}_$EVENT_STREAM_TABLE"
        return generate(tableName)
    }

    private fun loadByVersionSql(tableName: String): String {
        return "select * from $tableName where aggregate_id=? and version between ? and ? order by version"
    }

    private fun loadByEventTimeSql(tableName: String): String {
        return "select * from $tableName where aggregate_id=? and create_time between ? and ? order by version"
    }

    private fun appendSql(tableName: String): String {
        return """
        insert into $tableName (id,aggregate_id,tenant_id,owner_id,space_id,request_id,command_id,version,header,body,size,create_time) 
        values
        (?,?,?,?,?,?,?,?,?,?,?,?)
    """.trim()
    }

    private fun lastSql(tableName: String): String {
        return "select * from $tableName where aggregate_id=? order by version desc limit 1"
    }

    fun generate(tableName: String): EventStreamStatement {
        return statements.computeIfAbsent(tableName) {
            EventStreamStatement(
                load = loadByVersionSql(tableName),
                loadByEventTime = loadByEventTimeSql(tableName),
                append = appendSql(tableName),
                last = lastSql(tableName)
            )
        }
    }
}

interface EventStreamSchema {
    val aggregateIdVersionUniqueIndexName: String
        get() = "u_idx_aggregate_id_version"
    val requestIdUniqueIndexName: String
        get() = "u_idx_request_id"

    fun load(aggregateId: AggregateId): String
    fun loadByEventTime(aggregateId: AggregateId): String

    fun append(aggregateId: AggregateId): String

    fun last(aggregateId: AggregateId): String
}

class SimpleEventStreamSchema : EventStreamSchema {

    override fun load(aggregateId: AggregateId): String =
        EventStreamStatementGenerator.generate(aggregateId).load

    override fun loadByEventTime(aggregateId: AggregateId): String {
        return EventStreamStatementGenerator.generate(aggregateId).loadByEventTime
    }

    override fun append(aggregateId: AggregateId): String =
        EventStreamStatementGenerator.generate(aggregateId).append

    override fun last(aggregateId: AggregateId): String =
        EventStreamStatementGenerator.generate(aggregateId).last
}

class ShardingEventStreamSchema(private val sharding: AggregateIdSharding) :
    EventStreamSchema {

    override fun load(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return EventStreamStatementGenerator.generate(tableName).load
    }

    override fun loadByEventTime(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return EventStreamStatementGenerator.generate(tableName).loadByEventTime
    }

    override fun append(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return EventStreamStatementGenerator.generate(tableName).append
    }

    override fun last(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return EventStreamStatementGenerator.generate(tableName).last
    }
}

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

const val SNAPSHOT_TABLE = "snapshot"

data class SnapshotStatement(
    val load: String,
    val loadByVersion: String,
    val save: String,
    val scan: String
)

object SnapshotStatementGenerator {
    private val statements = ConcurrentHashMap<String, SnapshotStatement>()

    fun generate(namedAggregate: NamedAggregate): SnapshotStatement {
        val tableName = "${namedAggregate.aggregateName}_$SNAPSHOT_TABLE"
        return generate(tableName)
    }

    fun generate(tableName: String): SnapshotStatement {
        return statements.computeIfAbsent(tableName) {
            val loadStatement = "select * from $tableName where aggregate_id=? order by version desc limit 1"
            val loadByVersionStatement = "select * from $tableName where aggregate_id=? and version=?"
            val saveStatement = """
     replace into $tableName
     (aggregate_id,tenant_id,owner_id,space_id,version,state_type,state,event_id,first_operator,operator,first_event_time,event_time,snapshot_time,deleted)
     values 
     (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
     """.trim()
            val scanStatement =
                "select aggregate_id,tenant_id from $tableName where aggregate_id > ? order by aggregate_id asc limit ?"
            SnapshotStatement(
                load = loadStatement,
                loadByVersion = loadByVersionStatement,
                save = saveStatement,
                scan = scanStatement
            )
        }
    }
}

interface SnapshotSchema {
    fun load(aggregateId: AggregateId): String
    fun loadByVersion(aggregateId: AggregateId): String
    fun save(aggregateId: AggregateId): String
    fun scan(aggregateId: AggregateId): String
}

class SimpleSnapshotSchema : SnapshotSchema {

    override fun load(aggregateId: AggregateId): String {
        return SnapshotStatementGenerator.generate(aggregateId.namedAggregate).load
    }

    override fun loadByVersion(aggregateId: AggregateId): String {
        return SnapshotStatementGenerator.generate(aggregateId.namedAggregate).loadByVersion
    }

    override fun save(aggregateId: AggregateId): String {
        return SnapshotStatementGenerator.generate(aggregateId.namedAggregate).save
    }

    override fun scan(aggregateId: AggregateId): String {
        return SnapshotStatementGenerator.generate(aggregateId.namedAggregate).scan
    }
}

class ShardingSnapshotSchema(private val sharding: AggregateIdSharding) :
    SnapshotSchema {

    override fun load(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return SnapshotStatementGenerator.generate(tableName).load
    }

    override fun loadByVersion(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return SnapshotStatementGenerator.generate(tableName).loadByVersion
    }

    override fun save(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return SnapshotStatementGenerator.generate(tableName).save
    }

    override fun scan(aggregateId: AggregateId): String {
        val tableName = sharding.sharding(aggregateId)
        return SnapshotStatementGenerator.generate(tableName).scan
    }
}

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

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.sharding.AggregateIdSharding
import org.reactivestreams.Publisher

interface ConnectionFactoryRegistrar : Map<String, ConnectionFactory>

class SimpleConnectionFactoryRegistrar(
    private val connectionFactories: Map<String, ConnectionFactory>
) : ConnectionFactoryRegistrar, Map<String, ConnectionFactory> by connectionFactories

interface Database {
    fun createConnection(aggregateId: AggregateId): Publisher<out Connection>
}

class SimpleDatabase(private val connectionFactory: ConnectionFactory) : EventStreamDatabase, SnapshotDatabase {

    override fun createConnection(aggregateId: AggregateId): Publisher<out Connection> {
        return connectionFactory.create()
    }
}

interface EventStreamDatabase : Database

interface SnapshotDatabase : Database

class ShardingDatabase(
    private val registrar: ConnectionFactoryRegistrar,
    private val aggregateIdSharding: AggregateIdSharding
) : Database, EventStreamDatabase, SnapshotDatabase {

    override fun createConnection(aggregateId: AggregateId): Publisher<out Connection> {
        return sharding(aggregateId).create()
    }

    fun sharding(aggregateId: AggregateId): ConnectionFactory {
        val logicName = aggregateIdSharding.sharding(aggregateId)
        return registrar[logicName]!!
    }
}

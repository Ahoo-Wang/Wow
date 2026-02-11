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

package me.ahoo.wow.spring.boot.starter.r2dbc.eventstore

import io.r2dbc.spi.ConnectionFactory
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.r2dbc.ConnectionFactoryRegistrar
import me.ahoo.wow.r2dbc.EventStreamDatabase
import me.ahoo.wow.r2dbc.EventStreamSchema
import me.ahoo.wow.r2dbc.R2dbcEventStore
import me.ahoo.wow.r2dbc.ShardingDatabase
import me.ahoo.wow.r2dbc.ShardingEventStreamSchema
import me.ahoo.wow.r2dbc.SimpleDatabase
import me.ahoo.wow.r2dbc.SimpleEventStreamSchema
import me.ahoo.wow.sharding.ShardingRegistrar
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.r2dbc.ConditionalOnR2dbcEnabled
import me.ahoo.wow.spring.boot.starter.r2dbc.DataSourceProperties
import me.ahoo.wow.spring.boot.starter.r2dbc.ShardingDataSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.r2dbc.ShardingProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration(after = [R2dbcAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnR2dbcEnabled
@ConditionalOnClass(R2dbcEventStore::class)
@ConditionalOnProperty(
    EventStoreProperties.STORAGE,
    havingValue = StorageType.R2DBC_NAME,
)
class R2dbcEventStoreAutoConfiguration {

    @Bean
    fun eventStore(eventStreamDatabase: EventStreamDatabase, eventStreamSchema: EventStreamSchema): EventStore {
        return R2dbcEventStore(eventStreamDatabase, eventStreamSchema)
    }

    @Configuration
    @ConditionalOnProperty(
        value = [DataSourceProperties.TYPE],
        matchIfMissing = true,
        havingValue = DataSourceProperties.Type.SIMPLE_NAME,
    )
    class Simple {

        @Bean
        fun eventStreamDatabase(connectionFactory: ConnectionFactory): EventStreamDatabase {
            return SimpleDatabase(connectionFactory)
        }

        @Bean
        fun eventStreamSchema(): EventStreamSchema {
            return SimpleEventStreamSchema()
        }
    }

    @Configuration
    @ConditionalOnProperty(
        value = [DataSourceProperties.TYPE],
        matchIfMissing = false,
        havingValue = DataSourceProperties.Type.SHARDING_NAME,
    )
    class Sharding {

        @Bean
        fun eventStreamDatabase(
            @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
            boundedContext: NamedBoundedContext,
            connectionFactoryRegistrar: ConnectionFactoryRegistrar,
            shardingRegistrar: ShardingRegistrar,
            shardingProperties: ShardingProperties
        ): EventStreamDatabase {
            val shardingList = shardingProperties.eventStream.map {
                it.key to it.value.databaseAlgorithm
            }.toMap()
            val sharding = ShardingDataSourcingAutoConfiguration.buildCompositeSharding(
                boundedContext,
                shardingRegistrar,
                shardingList,
            )
            return ShardingDatabase(connectionFactoryRegistrar, sharding)
        }

        @Bean
        fun eventStreamSchema(
            @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
            boundedContext: NamedBoundedContext,
            shardingRegistrar: ShardingRegistrar,
            shardingProperties: ShardingProperties
        ): EventStreamSchema {
            val shardingList = shardingProperties.eventStream.map {
                it.key to it.value.tableAlgorithm
            }.toMap()
            val sharding = ShardingDataSourcingAutoConfiguration.buildCompositeSharding(
                boundedContext,
                shardingRegistrar,
                shardingList,
            )
            return ShardingEventStreamSchema(sharding)
        }
    }
}

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

package me.ahoo.wow.spring.boot.starter.r2dbc.snapshot

import io.r2dbc.spi.ConnectionFactory
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.r2dbc.ConnectionFactoryRegistrar
import me.ahoo.wow.r2dbc.R2dbcSnapshotRepository
import me.ahoo.wow.r2dbc.ShardingDatabase
import me.ahoo.wow.r2dbc.ShardingSnapshotSchema
import me.ahoo.wow.r2dbc.SimpleDatabase
import me.ahoo.wow.r2dbc.SimpleSnapshotSchema
import me.ahoo.wow.r2dbc.SnapshotDatabase
import me.ahoo.wow.r2dbc.SnapshotSchema
import me.ahoo.wow.sharding.ShardingRegistrar
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
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
@ConditionalOnSnapshotEnabled
@ConditionalOnClass(R2dbcSnapshotRepository::class)
@ConditionalOnProperty(
    SnapshotProperties.STORAGE,
    havingValue = StorageType.R2DBC_NAME,
)
class R2dbcSnapshotAutoConfiguration {

    @Bean
    fun r2dbcSnapshotRepository(
        snapshotDatabase: SnapshotDatabase,
        snapshotSchema: SnapshotSchema
    ): SnapshotRepository {
        return R2dbcSnapshotRepository(snapshotDatabase, snapshotSchema)
    }

    @Configuration
    @ConditionalOnProperty(
        value = [DataSourceProperties.TYPE],
        matchIfMissing = true,
        havingValue = DataSourceProperties.Type.SIMPLE_NAME,
    )
    class Simple {

        @Bean
        fun snapshotDatabase(connectionFactory: ConnectionFactory): SnapshotDatabase {
            return SimpleDatabase(connectionFactory)
        }

        @Bean
        fun snapshotSchema(): SnapshotSchema {
            return SimpleSnapshotSchema()
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
        fun snapshotDatabase(
            @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
            boundedContext: NamedBoundedContext,
            connectionFactoryRegistrar: ConnectionFactoryRegistrar,
            shardingRegistrar: ShardingRegistrar,
            shardingProperties: ShardingProperties
        ): SnapshotDatabase {
            val shardingList = shardingProperties.snapshot.map {
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
        fun snapshotSchema(
            @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
            boundedContext: NamedBoundedContext,
            shardingRegistrar: ShardingRegistrar,
            shardingProperties: ShardingProperties
        ): SnapshotSchema {
            val shardingList = shardingProperties.snapshot.map {
                it.key to it.value.tableAlgorithm
            }.toMap()
            val sharding = ShardingDataSourcingAutoConfiguration.buildCompositeSharding(
                boundedContext,
                shardingRegistrar,
                shardingList,
            )
            return ShardingSnapshotSchema(sharding)
        }
    }
}

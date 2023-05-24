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

package me.ahoo.wow.spring.boot.starter.r2dbc

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.cosid.sharding.Sharding
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.r2dbc.ConnectionFactoryRegistrar
import me.ahoo.wow.r2dbc.R2dbcEventStore
import me.ahoo.wow.r2dbc.SimpleConnectionFactoryRegistrar
import me.ahoo.wow.sharding.AggregateIdSharding
import me.ahoo.wow.sharding.CompositeAggregateIdSharding
import me.ahoo.wow.sharding.CosIdShardingDecorator
import me.ahoo.wow.sharding.NamedAggregateIdSharding
import me.ahoo.wow.sharding.ShardingRegistrar
import me.ahoo.wow.sharding.SimpleShardingRegistrar
import me.ahoo.wow.sharding.SingleAggregateIdSharding
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnR2dbcEnabled
@ConditionalOnClass(R2dbcEventStore::class)
@ConditionalOnProperty(
    value = [DataSourceProperties.TYPE],
    matchIfMissing = false,
    havingValue = DataSourceProperties.Type.SHARDING_NAME,
)
@EnableConfigurationProperties(ShardingProperties::class)
class ShardingDataSourcingAutoConfiguration(
    val shardingProperties: ShardingProperties
) {
    companion object {

        private fun asNamedAggregate(boundedContext: NamedBoundedContext, aggregateName: String): NamedAggregate {
            return MaterializedNamedAggregate(boundedContext.contextName, aggregateName)
        }

        fun buildCompositeSharding(
            boundedContext: NamedBoundedContext,
            shardingRegistrar: ShardingRegistrar,
            rules: Map<String, String>
        ): AggregateIdSharding {
            val shardingRule = rules.map {
                asNamedAggregate(boundedContext, it.key) to shardingRegistrar[it.value]!!
            }.toMap()
            return CompositeAggregateIdSharding(shardingRule)
        }

        private fun buildShardingAlg(
            shardingAlgorithm: ShardingProperties.ShardingAlgorithm
        ): AggregateIdSharding {
            return when (shardingAlgorithm.type) {
                MOD_ALG -> {
                    shardingAlgorithm.mod!!
                    CosIdShardingDecorator(
                        sharding = ModCycle<Long>(
                            shardingAlgorithm.mod.divisor,
                            shardingAlgorithm.mod.logicNamePrefix,
                        ) as Sharding<Long>,
                    )
                }

                SINGLE_ALG -> {
                    shardingAlgorithm.single!!
                    SingleAggregateIdSharding(node = shardingAlgorithm.single.node)
                }

                else -> {
                    throw IllegalArgumentException("unknown algorithm name:[${shardingAlgorithm.type}]")
                }
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun shardingRegistrar(namedShardingList: List<NamedAggregateIdSharding>): ShardingRegistrar {
        return SimpleShardingRegistrar().apply {
            namedShardingList.forEach {
                register(it)
            }

            shardingProperties.algorithms.forEach {
                val alg = buildShardingAlg(it.value)
                put(it.key, alg)
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun connectionFactoryRegistrar(): ConnectionFactoryRegistrar {
        val databases = shardingProperties.databases.map {
            it.key to createConnectionFactory(it.key, it.value.url)
        }.toMap()
        return SimpleConnectionFactoryRegistrar(databases)
    }
}

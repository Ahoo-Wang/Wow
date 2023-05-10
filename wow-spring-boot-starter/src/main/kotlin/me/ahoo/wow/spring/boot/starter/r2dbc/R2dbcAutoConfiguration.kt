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

import io.r2dbc.proxy.ProxyConnectionFactory
import io.r2dbc.proxy.support.QueryExecutionInfoFormatter
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import me.ahoo.wow.r2dbc.R2dbcEventStore
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.r2dbc.eventstore.R2dbcEventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.r2dbc.snapshot.R2dbcSnapshotAutoConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties

val r2dbcQueryLogger: Logger = LoggerFactory.getLogger("me.ahoo.wow.r2dbc.R2dbcQueryLogger")

internal fun createConnectionFactory(databaseAlias: String, url: String): ConnectionFactory {
    // Very important: https://github.com/r2dbc/r2dbc-pool/issues/129
    val connectionFactory = ConnectionFactories.get(
        url,
    )
    val formatter = QueryExecutionInfoFormatter.showAll()
    return ProxyConnectionFactory.builder(connectionFactory)
        .onAfterQuery { execInfo ->
            if (r2dbcQueryLogger.isDebugEnabled) {
                r2dbcQueryLogger.debug("[$databaseAlias] ${formatter.format(execInfo)}")
            }
        }
        .build()
}

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnR2dbcEnabled
@ConditionalOnClass(R2dbcEventStore::class)
@EnableConfigurationProperties(R2dbcProperties::class, DataSourceProperties::class)
@ImportAutoConfiguration(
    R2dbcEventStoreAutoConfiguration::class,
    R2dbcSnapshotAutoConfiguration::class,
    ShardingDataSourcingAutoConfiguration::class,
)
class R2dbcAutoConfiguration

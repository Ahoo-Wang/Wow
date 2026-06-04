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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.r2dbc.proxy.ProxyConnectionFactory
import io.r2dbc.proxy.support.QueryExecutionInfoFormatter
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory

object ConnectionFactoryProviders {
    private val log = KotlinLogging.logger { }

    @JvmOverloads
    fun create(poolSize: Int = 32): ConnectionFactory {
        // Very important: https://github.com/r2dbc/r2dbc-pool/issues/129
        val connectionFactory = ConnectionFactories.get(
            "r2dbc:pool:mariadb:sequential://root:root@${MariadbLauncher.getHost()}:${MariadbLauncher.getPort()}/wow_db?initialSize=$poolSize&maxSize=$poolSize&acquireRetry=3&maxLifeTime=PT30M",
        )
        val formatter = QueryExecutionInfoFormatter.showAll()
        return ProxyConnectionFactory.builder(connectionFactory)
            .onAfterQuery { execInfo ->
                log.debug {
                    formatter.format(execInfo)
                }
            }
            .build()
    }
}

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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean

/** Installs one explicitly enabled lifecycle owner for periodic, bounded cursor lease cleanup. */
@AutoConfiguration(after = [QueryGatewayAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnProperty(
    prefix = QueryCursorReaperProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(QueryCursorReaperProperties::class)
class QueryCursorReaperAutoConfiguration {
    @Bean(QUERY_CURSOR_REAPER_LIFECYCLE_BEAN_NAME)
    @ConditionalOnMissingBean(name = [QUERY_CURSOR_REAPER_LIFECYCLE_BEAN_NAME])
    fun queryCursorReaperLifecycle(
        runtime: QueryGatewayRuntime,
        @Suppress("UNUSED_PARAMETER") cursorLeaseConfiguration: QueryCursorLeaseConfiguration,
        properties: QueryCursorReaperProperties,
    ): SmartLifecycle = QueryCursorReaperLifecycle(runtime::reapExpiredQueryCursors, properties)

    companion object {
        const val QUERY_CURSOR_REAPER_LIFECYCLE_BEAN_NAME = "queryCursorReaperLifecycle"
    }
}

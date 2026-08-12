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
package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.Wow
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

@ConfigurationProperties(prefix = QueryGatewayProperties.PREFIX)
data class QueryGatewayProperties(
    val maxDepth: Int = 64,
    val maxNodes: Int = 10_000,
    val maxMembershipItems: Int = 10_000,
    val maxNativeParameterBytes: Long = 1_048_576,
    @NestedConfigurationProperty
    val systemBudget: QueryGatewaySystemBudgetProperties = QueryGatewaySystemBudgetProperties(),
    val enabledCapabilities: Set<String> = emptySet()
) {
    init {
        require(maxDepth > 0) { "Maximum query depth must be positive." }
        require(maxNodes > 0) { "Maximum query nodes must be positive." }
        require(maxMembershipItems > 0) { "Maximum membership items must be positive." }
        require(maxNativeParameterBytes > 0) { "Maximum native parameter bytes must be positive." }
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}query.gateway"
    }
}

data class QueryGatewaySystemBudgetProperties(
    val timeout: Duration? = null,
    val maxResults: Long? = null,
    val maxCost: Long? = null
)

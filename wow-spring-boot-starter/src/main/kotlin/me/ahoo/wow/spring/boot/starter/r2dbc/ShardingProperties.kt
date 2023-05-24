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

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

const val MOD_ALG = "mod"
const val SINGLE_ALG = "single"

@ConstructorBinding
@ConfigurationProperties(prefix = ShardingProperties.PREFIX)
data class ShardingProperties(
    val databases: Map<String, Database> = mapOf(),
    val eventStream: Map<String, ShardingRule> = mapOf(),
    val snapshot: Map<String, ShardingRule> = mapOf(),
    val algorithms: Map<String, ShardingAlgorithm> = mapOf()
) {
    companion object {
        const val PREFIX = "${DataSourceProperties.PREFIX}.sharding"
    }

    data class Database(val url: String)
    data class ShardingRule(val databaseAlgorithm: String, val tableAlgorithm: String)

    data class ShardingAlgorithm(val type: String = MOD_ALG, val mod: ModAlgorithm?, val single: SingleAlgorithm?)
    data class ModAlgorithm(val logicNamePrefix: String, val divisor: Int)
    data class SingleAlgorithm(val node: String)
}

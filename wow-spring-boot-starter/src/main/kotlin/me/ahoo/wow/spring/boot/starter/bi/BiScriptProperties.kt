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

package me.ahoo.wow.spring.boot.starter.bi

import me.ahoo.wow.api.Wow
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties(
    @DefaultValue(DEFAULT_DATABASE)
    val database: String = DEFAULT_DATABASE,
    @DefaultValue(DEFAULT_CONSUMER_DATABASE)
    val consumerDatabase: String = DEFAULT_CONSUMER_DATABASE,
    @DefaultValue(DEFAULT_CLUSTER)
    val cluster: String = DEFAULT_CLUSTER,
    @DefaultValue(DEFAULT_INSTALLATION)
    val installation: String = DEFAULT_INSTALLATION,
    @DefaultValue(DEFAULT_SHARD)
    val shard: String = DEFAULT_SHARD,
    @DefaultValue(DEFAULT_REPLICA)
    val replica: String = DEFAULT_REPLICA,
    @DefaultValue(DEFAULT_TIMEZONE)
    val timezone: String = DEFAULT_TIMEZONE,
    val kafkaBootstrapServers: String? = null,
    val topicPrefix: String? = null,
    @DefaultValue("5")
    val maxExpansionDepth: Int = DEFAULT_MAX_EXPANSION_DEPTH,
    @DefaultValue("FAIL")
    val unsupportedTypeStrategy: BiScriptUnsupportedTypeStrategy = BiScriptUnsupportedTypeStrategy.FAIL,
    @DefaultValue("STRING_VALUE_WITH_DIAGNOSTIC")
    val objectMapStrategy: BiScriptObjectMapStrategy = BiScriptObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC,
) {
    init {
        database.requireValidRequiredValue("database")
        consumerDatabase.requireValidRequiredValue("consumerDatabase")
        cluster.requireValidRequiredValue("cluster")
        installation.requireValidRequiredValue("installation")
        shard.requireValidRequiredValue("shard")
        replica.requireValidRequiredValue("replica")
        timezone.requireValidRequiredValue("timezone")
        kafkaBootstrapServers?.requireValidRequiredValue("kafkaBootstrapServers")
        topicPrefix?.requireValidRequiredValue("topicPrefix")
        require(maxExpansionDepth >= 1) {
            "maxExpansionDepth must be greater than or equal to 1"
        }
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
        const val DEFAULT_DATABASE = "bi_db"
        const val DEFAULT_CONSUMER_DATABASE = "bi_db_consumer"
        const val DEFAULT_CLUSTER = "{cluster}"
        const val DEFAULT_INSTALLATION = "{installation}"
        const val DEFAULT_SHARD = "{shard}"
        const val DEFAULT_REPLICA = "{replica}"
        const val DEFAULT_TIMEZONE = "Asia/Shanghai"
        const val DEFAULT_MAX_EXPANSION_DEPTH = 5
    }
}

private fun String.requireValidRequiredValue(name: String) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
}

enum class BiScriptUnsupportedTypeStrategy {
    FAIL,
    STRING_WITH_DIAGNOSTIC,
}

enum class BiScriptObjectMapStrategy {
    STRING_VALUE_WITH_DIAGNOSTIC,
    FAIL,
}

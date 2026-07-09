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

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties(
    val database: String? = null,
    val consumerDatabase: String? = null,
    val cluster: String? = null,
    val installation: String? = null,
    val shard: String? = null,
    val replica: String? = null,
    val timezone: String? = null,
    val kafkaBootstrapServers: String? = null,
    val topicPrefix: String? = null,
    val maxExpansionDepth: Int? = null,
    val unsupportedTypeStrategy: BiScriptUnsupportedTypeStrategy? = null,
    val objectMapStrategy: BiScriptObjectMapStrategy? = null,
) {
    init {
        database?.requireValidRequiredValue("database")
        consumerDatabase?.requireValidRequiredValue("consumerDatabase")
        cluster?.requireValidRequiredValue("cluster")
        installation?.requireValidRequiredValue("installation")
        shard?.requireValidRequiredValue("shard")
        replica?.requireValidRequiredValue("replica")
        timezone?.requireValidRequiredValue("timezone")
        kafkaBootstrapServers?.requireValidRequiredValue("kafkaBootstrapServers")
        topicPrefix?.requireValidRequiredValue("topicPrefix")
        maxExpansionDepth?.let {
            require(it >= 1) {
                "maxExpansionDepth must be greater than or equal to 1"
            }
        }
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
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

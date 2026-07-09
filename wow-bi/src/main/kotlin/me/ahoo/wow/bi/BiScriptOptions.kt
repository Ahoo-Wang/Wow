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

package me.ahoo.wow.bi

import me.ahoo.wow.api.Wow

data class BiScriptOptions(
    val database: String = "bi_db",
    val consumerDatabase: String = "bi_db_consumer",
    val cluster: String = "{cluster}",
    val installation: String = "{installation}",
    val shard: String = "{shard}",
    val replica: String = "{replica}",
    val timezone: String = "Asia/Shanghai",
    val kafkaBootstrapServers: String = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
    val topicPrefix: String = Wow.WOW_PREFIX,
    val maxExpansionDepth: Int = 5,
    val unsupportedTypeStrategy: UnsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL,
    val objectMapStrategy: ObjectMapStrategy = ObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC,
) {
    fun validate(): BiScriptOptions {
        database.requireValidRequiredValue("database")
        consumerDatabase.requireValidRequiredValue("consumerDatabase")
        cluster.requireValidRequiredValue("cluster")
        installation.requireValidRequiredValue("installation")
        shard.requireValidRequiredValue("shard")
        replica.requireValidRequiredValue("replica")
        timezone.requireValidRequiredValue("timezone")
        kafkaBootstrapServers.requireValidRequiredValue("kafkaBootstrapServers")
        topicPrefix.requireValidRequiredValue("topicPrefix")
        require(maxExpansionDepth >= 1) {
            "maxExpansionDepth must be greater than or equal to 1"
        }
        return this
    }
}

private fun String.requireValidRequiredValue(name: String) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
}

enum class UnsupportedTypeStrategy {
    FAIL,
    STRING_WITH_DIAGNOSTIC,
}

enum class ObjectMapStrategy {
    STRING_VALUE_WITH_DIAGNOSTIC,
    FAIL,
}

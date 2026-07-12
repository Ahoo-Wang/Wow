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
    val topology: ClickHouseTopology = ClickHouseTopology.Cluster(),
    val timezone: String = "Asia/Shanghai",
    val kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
    val topicPrefix: String = DEFAULT_TOPIC_PREFIX,
    val maxExpansionDepth: Int = 5,
    val unsupportedTypeStrategy: UnsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
) {
    init {
        database.requireValidRequiredValue("database")
        consumerDatabase.requireValidRequiredValue("consumerDatabase")
        timezone.requireValidRequiredValue("timezone")
        kafkaBootstrapServers.requireValidRequiredValue("kafkaBootstrapServers")
        topicPrefix.requireValidRequiredValue("topicPrefix")
        require(maxExpansionDepth >= 1) {
            "maxExpansionDepth must be greater than or equal to 1"
        }
    }

    private companion object {
        private const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS: String = "localhost:9093"
        private const val DEFAULT_TOPIC_PREFIX: String = Wow.WOW_PREFIX
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
    RAW_JSON,
}

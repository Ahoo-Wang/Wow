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
    val consumerGroupNamespace: String? = null,
    val kafkaOffsetStorage: KafkaOffsetStorage = KafkaOffsetStorage.BROKER,
    val kafkaKeeperPathPrefix: String = DEFAULT_KAFKA_KEEPER_PATH_PREFIX,
    val maxExpansionDepth: Int = 5,
    val unsupportedTypeStrategy: UnsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
) {
    init {
        database.requireValidRequiredValue("database", MAX_DATABASE_LENGTH)
        consumerDatabase.requireValidRequiredValue("consumerDatabase", MAX_CONSUMER_DATABASE_LENGTH)
        timezone.requireValidRequiredValue("timezone", MAX_TIMEZONE_LENGTH)
        kafkaBootstrapServers.requireValidRequiredValue(
            "kafkaBootstrapServers",
            MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH,
        )
        topicPrefix.requireValidRequiredValue("topicPrefix", MAX_TOPIC_PREFIX_LENGTH)
        consumerGroupNamespace?.requireValidRequiredValue(
            "consumerGroupNamespace",
            MAX_CONSUMER_GROUP_NAMESPACE_LENGTH,
        )
        kafkaKeeperPathPrefix.requireValidRequiredValue(
            "kafkaKeeperPathPrefix",
            MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH,
        )
        require(maxExpansionDepth >= 1) {
            "maxExpansionDepth must be greater than or equal to 1"
        }
    }

    private fun String.requireValidRequiredValue(name: String, maxLength: Int) {
        require(isNotBlank()) { "$name must not be blank" }
        require(none { it == '\u0000' || it.isISOControl() }) {
            "$name must not contain control characters"
        }
        require(length <= maxLength) {
            "$name length $length must be less than or equal to $maxLength"
        }
    }

    companion object {
        const val MAX_DATABASE_LENGTH: Int = 128
        const val MAX_CONSUMER_DATABASE_LENGTH: Int = 128
        const val MAX_TIMEZONE_LENGTH: Int = 64
        const val MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH: Int = 4096
        const val MAX_TOPIC_PREFIX_LENGTH: Int = 128
        const val MAX_CONSUMER_GROUP_NAMESPACE_LENGTH: Int = 128
        const val MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH: Int = 512

        private const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS: String = "localhost:9093"
        private const val DEFAULT_TOPIC_PREFIX: String = Wow.WOW_PREFIX
        private const val DEFAULT_KAFKA_KEEPER_PATH_PREFIX: String = "/clickhouse/wow-bi"
    }
}

enum class UnsupportedTypeStrategy {
    FAIL,
    RAW_JSON,
}

enum class KafkaOffsetStorage {
    BROKER,
    KEEPER,
}

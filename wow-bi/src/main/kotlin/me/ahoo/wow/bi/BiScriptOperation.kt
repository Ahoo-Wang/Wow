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

import java.util.UUID

sealed interface BiScriptOperation {
    data class Deploy(val previousManifest: BiScriptManifest? = null) : BiScriptOperation

    data class Reset(
        val previousManifest: BiScriptManifest,
        val replayFromEarliestConfirmed: Boolean,
    ) : BiScriptOperation {
        init {
            require(replayFromEarliestConfirmed) {
                "Reset requires replayFromEarliestConfirmed=true because a new Kafka consumer group must start from earliest"
            }
        }
    }
}

data class BiScriptManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val layoutVersion: Int = CURRENT_LAYOUT_VERSION,
    val deployment: BiDeploymentManifest,
    val consumerGeneration: UUID,
    val aggregates: List<BiAggregateManifest>,
    val retainedAggregates: List<BiAggregateManifest> = emptyList(),
) {
    init {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported BI manifest format version: $formatVersion"
        }
        require(layoutVersion == CURRENT_LAYOUT_VERSION) {
            "Unsupported BI manifest layout version: $layoutVersion"
        }
        require(aggregates.map(BiAggregateManifest::aggregate).distinct().size == aggregates.size) {
            "BI manifest aggregate names must be unique"
        }
        require(retainedAggregates.map(BiAggregateManifest::aggregate).distinct().size == retainedAggregates.size) {
            "BI manifest retained aggregate names must be unique"
        }
        require(aggregates.none { active -> retainedAggregates.any { it.aggregate == active.aggregate } }) {
            "BI manifest aggregates cannot be both active and retained"
        }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
        const val CURRENT_LAYOUT_VERSION: Int = 5
    }
}

data class BiDeploymentManifest(
    val database: String,
    val consumerDatabase: String,
    val topology: BiManifestTopology,
    val clusterName: String? = null,
    val installation: String? = null,
    val timezone: String,
    val kafkaBootstrapServers: String,
    val topicPrefix: String,
    val consumerGroupNamespace: String?,
    val kafkaOffsetStorage: KafkaOffsetStorage,
    val kafkaKeeperPathPrefix: String,
) {
    init {
        if (topology == BiManifestTopology.CLUSTER) {
            require(!clusterName.isNullOrBlank() && !installation.isNullOrBlank()) {
                "Cluster deployment manifest requires clusterName and installation"
            }
        } else {
            require(clusterName == null && installation == null) {
                "Standalone deployment manifest must not contain clusterName or installation"
            }
        }
    }
}

enum class BiManifestTopology {
    STANDALONE,
    CLUSTER,
}

data class BiAggregateManifest(
    val aggregate: String,
    val tablePrefix: String,
    val expansionViews: List<String>,
) {
    init {
        aggregate.requireValidBiValue("aggregate", MAX_NAME_LENGTH)
        tablePrefix.requireValidBiObjectName("tablePrefix")
        require(tablePrefix == aggregate.toBiTablePrefix()) {
            "BI manifest tablePrefix [$tablePrefix] does not match aggregate [$aggregate]"
        }
        require(expansionViews.distinct().size == expansionViews.size) {
            "BI manifest expansion view names must be unique for aggregate [$aggregate]"
        }
        expansionViews.forEach { view ->
            view.requireValidBiObjectName("expansionView")
            val rootView = "${tablePrefix}_state_last_root"
            require(view == rootView || view.startsWith("${rootView}_")) {
                "BI manifest expansion view [$view] is outside aggregate [$aggregate]"
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 512
    }
}

private fun String.toBiTablePrefix(): String {
    require(count { it == '.' } == 1) { "BI manifest aggregate must be '<context>.<aggregate>': $this" }
    return substringBefore('.').removeSuffix("-service").replace("-", "_") + "_" + substringAfter('.')
}

private fun String.requireValidBiObjectName(name: String) {
    requireValidBiValue(name, BiAggregateManifest.MAX_NAME_LENGTH)
    require(all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
        "$name must contain only letters, digits, underscores, or hyphens"
    }
}

internal fun String.requireValidBiValue(name: String, maxLength: Int) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
    require(length <= maxLength) {
        "$name length $length must be less than or equal to $maxLength"
    }
}

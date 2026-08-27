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

package me.ahoo.wow.openapi.contract.bi

data class BiScriptRequest(
    val operation: BiScriptOperationMode = BiScriptOperationMode.DEPLOY,
    val database: String? = null,
    val consumerDatabase: String? = null,
    val topology: BiScriptTopologyRequest? = null,
    val timezone: String? = null,
    val kafkaBootstrapServers: String? = null,
    val topicPrefix: String? = null,
    val maxExpansionDepth: Int? = null,
    val unsupportedTypeStrategy: BiScriptUnsupportedTypeStrategy? = null,
    val replayFromEarliestConfirmed: Boolean? = null,
)

enum class BiScriptOperationMode {
    DEPLOY,
    RESET,
}

data class BiScriptResponse(
    val script: String,
    val destructive: Boolean,
    val diagnostics: List<BiScriptDiagnosticResponse>,
)

data class BiScriptDiagnosticResponse(
    val code: String,
    val aggregate: String,
    val path: String,
    val sourceType: String,
    val decision: String,
    val message: String,
)

data class BiScriptTopologyRequest(
    val mode: BiScriptTopologyMode,
    val cluster: BiScriptClusterRequest? = null
)

data class BiScriptClusterRequest(
    val name: String? = null,
    val installation: String? = null,
)

enum class BiScriptTopologyMode {
    CLUSTER,
    STANDALONE
}

enum class BiScriptUnsupportedTypeStrategy {
    FAIL,
    RAW_JSON
}

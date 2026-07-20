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

@ConsistentCopyVisibility
data class BiScriptResult internal constructor(
    val script: String,
    internal val statements: List<String>,
    val diagnostics: List<BiScriptDiagnostic>,
    val operation: BiScriptOperation,
    val destructive: Boolean,
)

enum class BiScriptDiagnosticCode {
    RAW_JSON_FALLBACK,
    MAX_DEPTH_REACHED,
    INSPECTION_UNAVAILABLE,
    ORPHANED_DATA_TABLE,
    CLUSTER_INTERNAL_REPLICATION_REQUIRED,
    COMPUTED_OBJECT_DRIFT,
}

enum class BiScriptMappingDecision {
    RAW_JSON,
    MAX_DEPTH_RAW_JSON,
    RECONCILIATION_SKIPPED,
    DATA_TABLE_RETAINED,
    EXTERNAL_CONFIGURATION_REQUIRED,
    RECONCILIATION_PLANNED,
}

data class BiScriptDiagnostic(
    val code: BiScriptDiagnosticCode,
    val aggregate: String,
    val path: String,
    val sourceType: String,
    val decision: BiScriptMappingDecision,
    val message: String,
)

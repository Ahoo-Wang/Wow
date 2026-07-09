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

data class BiScriptResult(
    val script: String,
    val diagnostics: List<BiScriptDiagnostic>,
)

enum class BiScriptDiagnosticCode {
    OBJECT_MAP_FALLBACK,
    UNSUPPORTED_TYPE_FALLBACK,
    MAX_DEPTH_REACHED,
}

data class BiScriptDiagnostic(
    val code: BiScriptDiagnosticCode,
    val severity: Severity,
    val aggregate: String,
    val path: String,
    val message: String,
) {
    enum class Severity {
        WARNING,
        ERROR,
    }
}

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

internal data class ExpectedBiQuery(
    val selectSql: String,
    val target: BiObjectKey? = null,
) {
    init {
        require(selectSql.isNotBlank()) { "selectSql must not be blank" }
    }
}

internal data class CanonicalExpectedBiQuery(
    val selectSql: String,
    val target: BiObjectKey? = null,
)

internal data class RepairableBiObjectDrift(
    val key: BiObjectKey,
    val aggregate: String,
    val kind: BiObjectKind,
    val mismatches: Set<BiComputedDefinitionField>,
)

internal enum class BiComputedDefinitionField {
    SELECT,
    TARGET,
}

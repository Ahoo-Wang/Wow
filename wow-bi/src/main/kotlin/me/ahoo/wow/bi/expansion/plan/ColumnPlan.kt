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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.bi.type.ClickHouseType

internal data class ColumnPlan(
    val name: String,
    val path: String,
    val targetName: String,
    val type: ClickHouseType,
    val extraction: ColumnExtraction,
    val placement: ColumnPlacement,
    val inherited: Boolean = true,
)

internal sealed interface ColumnReference {
    val name: String

    data class Input(override val name: String) : ColumnReference

    data class Alias(override val name: String) : ColumnReference
}

internal sealed interface ColumnExtraction {
    data class Reference(val source: ColumnReference) : ColumnExtraction

    data class JsonValue(val source: ColumnReference, val property: String) : ColumnExtraction

    data class JsonString(val source: ColumnReference, val property: String) : ColumnExtraction

    data class JsonRaw(val source: ColumnReference, val property: String) : ColumnExtraction

    data class JsonArray(val source: ColumnReference, val property: String) : ColumnExtraction

    data class ArrayJoin(val source: ColumnReference, val property: String) : ColumnExtraction
}

internal enum class ColumnPlacement {
    WITH,
    SELECT,
}

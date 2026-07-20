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

package me.ahoo.wow.bi.renderer

import me.ahoo.wow.bi.expansion.plan.ColumnReference

internal fun jsonValue(source: String, property: String, sqlType: String): String =
    "JSONExtract(${identifier(source)}, ${literal(property)}, ${literal(sqlType)})"

internal fun jsonValue(source: ColumnReference, property: String, sqlType: String): String =
    "JSONExtract(${renderReference(source)}, ${literal(property)}, ${literal(sqlType)})"

internal fun jsonString(source: String, property: String): String =
    "JSONExtractString(${identifier(source)}, ${literal(property)})"

internal fun jsonString(source: ColumnReference, property: String): String =
    "JSONExtractString(${renderReference(source)}, ${literal(property)})"

internal fun jsonRaw(source: String, property: String): String =
    "JSONExtractRaw(${identifier(source)}, ${literal(property)})"

internal fun jsonRaw(source: ColumnReference, property: String): String =
    "JSONExtractRaw(${renderReference(source)}, ${literal(property)})"

internal fun jsonArray(source: String, property: String): String =
    "JSONExtractArrayRaw(${identifier(source)}, ${literal(property)})"

internal fun jsonArray(source: ColumnReference, property: String): String =
    "JSONExtractArrayRaw(${renderReference(source)}, ${literal(property)})"

internal fun renderReference(reference: ColumnReference): String = when (reference) {
    is ColumnReference.Input ->
        "${identifier(ClickHouseScriptRenderer.SOURCE_ALIAS)}.${identifier(reference.name)}"
    is ColumnReference.Alias -> identifier(reference.name)
}

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

internal fun jsonTopLevelLexicalRaw(source: String, property: String): String {
    val sourceIdentifier = identifier(source)
    val headerProperty = literal("header")
    return "simpleJSONExtractRaw(" +
        "replaceOne($sourceIdentifier, " +
        "concat(${literal("\"header\":")}, simpleJSONExtractRaw($sourceIdentifier, $headerProperty)), " +
        "${literal("\"header\":{}")}), ${literal(property)})"
}

internal fun jsonUInt(source: String, property: String): String =
    "JSONExtractUInt(${identifier(source)}, ${literal(property)})"

internal fun jsonInt(source: String, property: String): String =
    "JSONExtractInt(${identifier(source)}, ${literal(property)})"

internal fun jsonBool(source: String, property: String): String =
    "JSONExtractBool(${identifier(source)}, ${literal(property)})"

internal fun jsonTupleValue(
    source: String,
    tupleIndex: Int,
    property: String,
    sqlType: String,
): String =
    "JSONExtract(${identifier(source)}.$tupleIndex, ${literal(property)}, ${literal(sqlType)})"

internal fun jsonTupleRaw(source: String, tupleIndex: Int, property: String): String =
    "JSONExtractRaw(${identifier(source)}.$tupleIndex, ${literal(property)})"

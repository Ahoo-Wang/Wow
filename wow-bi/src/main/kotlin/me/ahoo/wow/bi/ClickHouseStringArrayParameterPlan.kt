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

import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax

internal data class ClickHouseStringArrayParameterPlan(
    val predicate: String,
    val parameters: Map<String, String>,
) {
    val arrayExpression: String = parameters.keys.joinToString(
        prefix = if (parameters.size == 1) "" else "arrayConcat(",
        postfix = if (parameters.size == 1) "" else ")",
    ) { name -> "{$name:Array(String)}" }

    companion object {
        const val MAX_PARAMETER_BYTES: Int = 64 * 1024

        fun create(
            expression: String,
            parameterName: String,
            values: Collection<String>,
        ): ClickHouseStringArrayParameterPlan {
            require(expression.isNotBlank()) { "expression must not be blank" }
            require(parameterName.isNotBlank()) { "parameterName must not be blank" }
            val parameterValues = values
                .map(ClickHouseSqlSyntax::stringLiteral)
                .toClickHouseArrayParameters(parameterName)
            val predicates = parameterValues.keys.map { name ->
                "$expression IN {$name:Array(String)}"
            }
            return ClickHouseStringArrayParameterPlan(
                predicate = if (predicates.size == 1) {
                    predicates.single()
                } else {
                    predicates.joinToString(prefix = "(", postfix = ")", separator = " OR ")
                },
                parameters = parameterValues,
            )
        }

        private fun List<String>.toClickHouseArrayParameters(parameterName: String): Map<String, String> {
            if (isEmpty()) {
                return mapOf(parameterName to "[]")
            }
            val chunks = mutableListOf<MutableList<String>>()
            var currentChunk = mutableListOf<String>()
            var currentBytes = ARRAY_BRACKETS_BYTES
            for (literal in this) {
                val literalBytes = literal.toByteArray(Charsets.UTF_8).size
                require(literalBytes + ARRAY_BRACKETS_BYTES <= MAX_PARAMETER_BYTES) {
                    "ClickHouse Array(String) value exceeds the safe HTTP form field size [$MAX_PARAMETER_BYTES]"
                }
                val separatorBytes = if (currentChunk.isEmpty()) 0 else ARRAY_SEPARATOR_BYTES
                if (currentBytes + separatorBytes + literalBytes > MAX_PARAMETER_BYTES) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf()
                    currentBytes = ARRAY_BRACKETS_BYTES
                }
                if (currentChunk.isNotEmpty()) {
                    currentBytes += ARRAY_SEPARATOR_BYTES
                }
                currentChunk.add(literal)
                currentBytes += literalBytes
            }
            chunks.add(currentChunk)
            return chunks.mapIndexed { index, chunk ->
                val name = if (index == 0) parameterName else "$parameterName$index"
                name to chunk.joinToString(prefix = "[", postfix = "]")
            }.toMap()
        }

        private const val ARRAY_BRACKETS_BYTES = 2
        private const val ARRAY_SEPARATOR_BYTES = 2
    }
}

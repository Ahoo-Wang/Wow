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

internal object ClickHouseSqlSyntax {
    private fun escape(value: String, quote: Char): String = buildString {
        value.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == quote -> {
                    if (quote == '\'') {
                        append("''")
                    } else {
                        append('\\').append(character)
                    }
                }
                character.isISOControl() || character == '\u007F' -> {
                    character.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                        append("\\x")
                        append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                    }
                }
                else -> append(character)
            }
        }
    }

    fun quoteIdentifier(value: String): String =
        "\"${escape(value, '"')}\""

    fun stringLiteral(value: String): String =
        "'${escape(value, '\'')}'"
}

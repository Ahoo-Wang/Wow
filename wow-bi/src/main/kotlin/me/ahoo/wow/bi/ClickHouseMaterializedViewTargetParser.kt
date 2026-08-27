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

/** Extracts the `TO database.table` header from ClickHouse's canonical MATERIALIZED VIEW DDL. */
internal object ClickHouseMaterializedViewTargetParser {
    fun parse(createTableQuery: String): BiObjectKey? {
        val tokens = ClickHouseDdlTokenizer(createTableQuery).tokenize() ?: return null
        if (!tokens.startsWithKeywords("CREATE", "MATERIALIZED", "VIEW")) {
            return null
        }
        val asIndex = tokens.indexOfFirst { token -> token.isKeyword("AS") }
        if (asIndex < 0) {
            return null
        }
        val toIndexes = tokens.indices.filter { index -> index < asIndex && tokens[index].isKeyword("TO") }
        if (toIndexes.size != 1) {
            return null
        }
        val targetStart = toIndexes.single() + 1
        val database = tokens.getOrNull(targetStart)?.identifierValue ?: return null
        if (tokens.getOrNull(targetStart + 1) != ClickHouseDdlToken.Dot) {
            return null
        }
        val table = tokens.getOrNull(targetStart + 2)?.identifierValue ?: return null
        return BiObjectKey(database, table)
    }
}

private sealed interface ClickHouseDdlToken {
    data class Word(val value: String) : ClickHouseDdlToken
    data class Identifier(val value: String) : ClickHouseDdlToken
    data class StringLiteral(val value: String) : ClickHouseDdlToken
    data object Dot : ClickHouseDdlToken
    data class Symbol(val value: Char) : ClickHouseDdlToken
}

private val ClickHouseDdlToken.identifierValue: String?
    get() = when (this) {
        is ClickHouseDdlToken.Identifier -> value
        is ClickHouseDdlToken.Word -> value
        else -> null
    }

private fun ClickHouseDdlToken.isKeyword(expected: String): Boolean =
    this is ClickHouseDdlToken.Word && value.equals(expected, ignoreCase = true)

private fun List<ClickHouseDdlToken>.startsWithKeywords(vararg keywords: String): Boolean =
    keywords.indices.all { index -> getOrNull(index)?.isKeyword(keywords[index]) == true }

private class ClickHouseDdlTokenizer(private val sql: String) {
    private var index: Int = 0

    fun tokenize(): List<ClickHouseDdlToken>? = buildList {
        while (index < sql.length) {
            when (val character = sql[index]) {
                in WHITESPACE -> index++
                '.', ',', '(', ')', ';' -> {
                    add(if (character == '.') ClickHouseDdlToken.Dot else ClickHouseDdlToken.Symbol(character))
                    index++
                }

                '\'', '"', '`' -> add(readQuoted(character) ?: return null)
                else -> add(readWord())
            }
        }
    }

    private fun readQuoted(quote: Char): ClickHouseDdlToken? {
        index++
        val value = StringBuilder()
        while (index < sql.length) {
            val character = sql[index++]
            when {
                character == '\\' && index < sql.length -> value.append(sql[index++])
                character == quote && sql.getOrNull(index) == quote -> {
                    value.append(quote)
                    index++
                }

                character == quote -> return if (quote == '\'') {
                    ClickHouseDdlToken.StringLiteral(value.toString())
                } else {
                    ClickHouseDdlToken.Identifier(value.toString())
                }

                else -> value.append(character)
            }
        }
        return null
    }

    private fun readWord(): ClickHouseDdlToken.Word {
        val start = index
        while (index < sql.length && sql[index] !in TOKEN_BOUNDARIES) {
            index++
        }
        return ClickHouseDdlToken.Word(sql.substring(start, index))
    }

    private companion object {
        val WHITESPACE = setOf(' ', '\t', '\r', '\n')
        val TOKEN_BOUNDARIES = WHITESPACE + setOf('.', ',', '(', ')', ';', '\'', '"', '`')
    }
}

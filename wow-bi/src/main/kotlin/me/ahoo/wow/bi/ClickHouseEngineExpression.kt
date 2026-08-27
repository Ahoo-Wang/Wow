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

internal fun String.functionArguments(functionName: String): List<String>? =
    FunctionArgumentsParser(trim(), functionName).parse()

internal fun String.settingLiteral(name: String): String? =
    Regex("""\b${Regex.escape(name)}\s*=\s*('(?:\\.|''|[^'])*')""")
        .find(this)
        ?.groupValues
        ?.get(1)

private class FunctionArgumentsParser(
    private val expression: String,
    private val functionName: String,
) {
    private var index: Int = 0
    private var argumentStart: Int = 0
    private var activeQuote: Char? = null
    private var nestedDepth: Int = 0
    private val arguments = mutableListOf<String>()

    fun parse(): List<String>? {
        if (!moveToArgumentsStart()) {
            return null
        }
        while (index < expression.length) {
            val action = activeQuote?.let { quote -> consumeQuoted(expression[index], quote) }
                ?: consumeUnquoted(expression[index])
            when (action) {
                ParseAction.COMPLETE -> return arguments
                ParseAction.INVALID -> return null
                ParseAction.CONTINUE -> index++
            }
        }
        return null
    }

    private fun moveToArgumentsStart(): Boolean {
        if (!expression.startsWith(functionName)) {
            return false
        }
        index = functionName.length
        while (index < expression.length && expression[index].isWhitespace()) {
            index++
        }
        if (expression.getOrNull(index) != '(') {
            return false
        }
        argumentStart = ++index
        return true
    }

    private fun consumeQuoted(character: Char, quote: Char): ParseAction {
        when {
            character == '\\' -> index++
            character == quote && expression.getOrNull(index + 1) == quote -> index++
            character == quote -> activeQuote = null
        }
        return ParseAction.CONTINUE
    }

    private fun consumeUnquoted(character: Char): ParseAction = when (character) {
        '\'', '"' -> {
            activeQuote = character
            ParseAction.CONTINUE
        }
        '(', '[', '{' -> {
            nestedDepth++
            ParseAction.CONTINUE
        }
        ']', '}' -> closeNestedExpression()
        ',' -> splitArgument()
        ')' -> closeFunction()
        else -> ParseAction.CONTINUE
    }

    private fun closeNestedExpression(): ParseAction {
        if (nestedDepth == 0) {
            return ParseAction.INVALID
        }
        nestedDepth--
        return ParseAction.CONTINUE
    }

    private fun splitArgument(): ParseAction {
        if (nestedDepth == 0) {
            arguments += expression.substring(argumentStart, index).trim()
            argumentStart = index + 1
        }
        return ParseAction.CONTINUE
    }

    private fun closeFunction(): ParseAction {
        if (nestedDepth > 0) {
            nestedDepth--
            return ParseAction.CONTINUE
        }
        val lastArgument = expression.substring(argumentStart, index).trim()
        if (lastArgument.isNotEmpty() || arguments.isNotEmpty()) {
            arguments += lastArgument
        }
        return ParseAction.COMPLETE
    }

    private enum class ParseAction {
        CONTINUE,
        COMPLETE,
        INVALID,
    }
}

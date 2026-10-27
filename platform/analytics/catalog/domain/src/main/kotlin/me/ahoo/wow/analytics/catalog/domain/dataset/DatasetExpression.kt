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

package me.ahoo.wow.analytics.catalog.domain.dataset

/**
 * An expression of a dataset's definition (-2): a computed column, a filter, or a reference to a
 * source's value. Its grammar is small on purpose, so the catalog decides what reaches the warehouse:
 *
 * - a reference `<alias>.<column>`, or `<alias>.<column>.<path>` for a value inside a JSON column; a name that is
 *   not a plain identifier is written in backquotes (`` o.`unit price` ``);
 * - literals: numbers, `'text'` (a quote doubled inside), `true`, `false`, `null`;
 * - `+ - * / %`, `= != <> < <= > >=`, `and`, `or`, `not`, `in (…)`, `not in (…)`, `between … and …`,
 *   `is null`, `is not null`;
 * - calls of the functions of [FUNCTIONS] only, by their ClickHouse names, and the text tests of the query protocol,
 *   `contains`, `startsWith` and `endsWith`, each with an `…IgnoreCase` form.
 *
 * Where it says what a filter of the query protocol says (`FilterExpression`), it says it alike, so an analyst reads
 * a definition as a query: `=` is `EQ` (`= null` is `IS_NULL`), `!=` is `NE` (`!= null` is `IS_NOT_NULL`), `> >= < <=`
 * are `GT GTE LT LTE`, `in`/`not in` are `IN`/`NOT_IN`, `between` is `BETWEEN` (both bounds included), `is null`/
 * `is not null` are `IS_NULL`/`IS_NOT_NULL`, `and`/`or` are `AND`/`OR`, `not` is a `NOR` of one, and `contains`,
 * `startsWith`, `endsWith` are `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` (case-insensitive in their `…IgnoreCase` form).
 * As there, `!=` and `not in` keep the rows whose value is null.
 */
internal sealed interface DatasetExpression {
    /** The value at [path] (empty for the whole column) of [alias]'s [column]. */
    data class Ref(val alias: String, val column: String, val path: List<String> = emptyList()) : DatasetExpression {
        /** The reference as the definition writes it, `<alias>.<column>[.<path>]`. */
        override fun toString(): String = (listOf(alias, column) + path).joinToString(".")
    }

    data class Literal(val kind: Kind, val text: String) : DatasetExpression {
        enum class Kind { NUMBER, STRING, BOOLEAN, NULL }
    }

    data class Call(val function: String, val arguments: List<DatasetExpression>) : DatasetExpression

    data class Unary(val operator: String, val operand: DatasetExpression) : DatasetExpression

    data class Binary(val operator: String, val left: DatasetExpression, val right: DatasetExpression) :
        DatasetExpression

    data class In(val operand: DatasetExpression, val values: List<DatasetExpression>, val negated: Boolean) :
        DatasetExpression

    data class IsNull(val operand: DatasetExpression, val negated: Boolean) : DatasetExpression

    /** [operand] between [lower] and [upper], both included. */
    data class Between(val operand: DatasetExpression, val lower: DatasetExpression, val upper: DatasetExpression) :
        DatasetExpression

    /** Every reference of the expression, in the order it reads them. */
    fun refs(): List<Ref> = when (this) {
        is Ref -> listOf(this)
        is Literal -> emptyList()
        is Call -> arguments.flatMap { it.refs() }
        is Unary -> operand.refs()
        is Binary -> left.refs() + right.refs()
        is In -> operand.refs() + values.flatMap { it.refs() }
        is IsNull -> operand.refs()
        is Between -> operand.refs() + lower.refs() + upper.refs()
    }

    companion object {
        /** The longest expression a definition may hold. */
        const val MAX_LENGTH: Int = 4096

        /** How deeply an expression may nest. */
        const val MAX_DEPTH: Int = 32

        /** The query protocol's text tests (`CONTAINS`, `STARTS_WITH`, `ENDS_WITH`), with their case-insensitive forms. */
        val TEXT_TESTS: Set<String> = setOf(
            "contains",
            "containsIgnoreCase",
            "startsWith",
            "startsWithIgnoreCase",
            "endsWith",
            "endsWithIgnoreCase",
        )

        /**
         * The ClickHouse functions an expression may call: conversions, dates, text, conditions and arithmetic, each
         * of one row's values. Nothing that reads other rows or tables, the server or the files is in it.
         */
        val FUNCTIONS: Set<String> = setOf(
            // Conversions.
            "toString", "toInt32", "toInt64", "toUInt32", "toUInt64", "toFloat64", "toDecimal64", "toDate",
            "toDateTime", "toDateTime64", "toBool",
            // Dates and times.
            "toYear", "toQuarter", "toMonth", "toDayOfMonth", "toDayOfWeek", "toHour", "toMinute", "toStartOfYear",
            "toStartOfQuarter", "toStartOfMonth", "toStartOfWeek", "toStartOfDay", "toStartOfHour", "toYYYYMM",
            "toYYYYMMDD", "dateDiff", "addDays", "addMonths", "addHours", "formatDateTime", "today", "now",
            // Text.
            "concat", "lower", "upper", "trim", "substring", "length", "replaceAll", "position", "like",
            "notLike", "leftPad", "rightPad",
            // Conditions and empty values.
            "if", "multiIf", "coalesce", "ifNull", "nullIf", "isNull", "isNotNull", "empty", "notEmpty",
            // Arithmetic.
            "abs", "round", "floor", "ceil", "greatest", "least", "intDiv",
        ) + TEXT_TESTS

        /**
         * The expression [text] writes, or why it is none ([DatasetExpressionException]): a syntax error, a function
         * outside [FUNCTIONS], a name that is not a reference.
         */
        fun parse(text: String): DatasetExpression = DatasetExpressionParser(text).parse()
    }
}

/** Why a definition's expression is not one: the [message] names the expression and the place. */
internal class DatasetExpressionException(message: String) : IllegalArgumentException(message)

/** A recursive descent over [text]: `or` binds loosest, then `and`, `not`, comparisons, `+ -`, `* / %`, unary `-`. */
@Suppress("ThrowsCount") // Each check throws the problem it found.
private class DatasetExpressionParser(private val text: String) {
    private val tokens: List<Token> = tokenize()
    private var position = 0
    private var depth = 0

    fun parse(): DatasetExpression {
        if (text.isBlank()) throw problem("is empty")
        val expression = or()
        peek()?.let { throw problem("has ${it.text} where it should end") }
        return expression
    }

    private fun or(): DatasetExpression = nested {
        var left = and()
        while (keyword("or")) left = DatasetExpression.Binary("OR", left, and())
        left
    }

    private fun and(): DatasetExpression {
        var left = not()
        while (keyword("and")) left = DatasetExpression.Binary("AND", left, not())
        return left
    }

    private fun not(): DatasetExpression =
        if (keyword("not")) DatasetExpression.Unary("NOT", nested { not() }) else comparison()

    private fun comparison(): DatasetExpression {
        val left = additive()
        val token = peek()
        return when {
            token?.kind == Kind.OPERATOR && token.text in COMPARISONS -> {
                position++
                DatasetExpression.Binary(if (token.text == "<>") "!=" else token.text, left, additive())
            }

            keyword("is") -> {
                val negated = keyword("not")
                if (!keyword("null")) throw problem("has is${if (negated) " not" else ""} without null")
                DatasetExpression.IsNull(left, negated)
            }

            isKeyword(token, "not") && isKeyword(tokens.getOrNull(position + 1), "in") -> {
                position += 2
                DatasetExpression.In(left, list(), negated = true)
            }

            keyword("in") -> DatasetExpression.In(left, list(), negated = false)
            keyword("between") -> {
                val lower = additive()
                if (!keyword("and")) throw problem("has between without and")
                DatasetExpression.Between(left, lower, additive())
            }

            else -> left
        }
    }

    private fun additive(): DatasetExpression {
        var left = multiplicative()
        while (true) {
            val operator = operator("+", "-") ?: return left
            left = DatasetExpression.Binary(operator, left, multiplicative())
        }
    }

    private fun multiplicative(): DatasetExpression {
        var left = unary()
        while (true) {
            val operator = operator("*", "/", "%") ?: return left
            left = DatasetExpression.Binary(operator, left, unary())
        }
    }

    private fun unary(): DatasetExpression =
        if (operator("-") != null) DatasetExpression.Unary("-", nested { unary() }) else primary()

    private fun primary(): DatasetExpression {
        val token = next() ?: throw problem("ends too early")
        return when (token.kind) {
            Kind.NUMBER -> DatasetExpression.Literal(DatasetExpression.Literal.Kind.NUMBER, token.text)
            Kind.STRING -> DatasetExpression.Literal(DatasetExpression.Literal.Kind.STRING, token.text)
            Kind.OPEN -> nested { or() }.also { expect(Kind.CLOSE, ")") }
            Kind.NAME, Kind.QUOTED -> name(token)
            else -> throw problem("has ${token.text} where a value should be")
        }
    }

    /** A call, a reference or a keyword literal, from its first [token]. */
    private fun name(token: Token): DatasetExpression {
        if (token.kind == Kind.NAME && peek()?.kind == Kind.OPEN) {
            if (token.text !in DatasetExpression.FUNCTIONS) {
                throw problem("calls ${token.text}, which a dataset may not")
            }
            return DatasetExpression.Call(token.text, list(empty = true))
        }
        val parts = mutableListOf(token)
        while (peek()?.kind == Kind.DOT) {
            position++
            val part = next()?.takeIf { it.kind == Kind.NAME || it.kind == Kind.QUOTED }
                ?: throw problem("has a reference that ends with a dot")
            parts += part
        }
        if (parts.size == 1 && token.kind == Kind.NAME) {
            return when (token.text.lowercase()) {
                "true", "false" -> DatasetExpression.Literal(
                    DatasetExpression.Literal.Kind.BOOLEAN,
                    token.text.lowercase()
                )
                "null" -> DatasetExpression.Literal(DatasetExpression.Literal.Kind.NULL, "null")
                else -> throw problem("names ${token.text}: a value of a source is <alias>.<column>")
            }
        }
        if (parts.size == 1) throw problem("names ${token.text}: a value of a source is <alias>.<column>")
        return DatasetExpression.Ref(parts[0].text, parts[1].text, parts.drop(2).map { it.text })
    }

    /** A parenthesized, comma-separated list of expressions; [empty] when it may have none. */
    private fun list(empty: Boolean = false): List<DatasetExpression> = nested {
        expect(Kind.OPEN, "(")
        val values = mutableListOf<DatasetExpression>()
        if (peek()?.kind == Kind.CLOSE) {
            if (!empty) throw problem("has an empty list")
            position++
        } else {
            do {
                values += or()
                val token = next() ?: throw problem("ends before )")
                if (token.kind != Kind.COMMA && token.kind != Kind.CLOSE) {
                    throw problem("has ${token.text} where , or ) should be")
                }
            } while (token.kind == Kind.COMMA)
        }
        values
    }

    private inline fun <T> nested(block: () -> T): T {
        if (++depth > DatasetExpression.MAX_DEPTH) throw problem("nests deeper than ${DatasetExpression.MAX_DEPTH}")
        try {
            return block()
        } finally {
            depth--
        }
    }

    private fun keyword(word: String): Boolean = isKeyword(peek(), word).also { if (it) position++ }

    private fun isKeyword(token: Token?, word: String): Boolean =
        token?.kind == Kind.NAME && token.text.equals(word, ignoreCase = true)

    private fun operator(vararg operators: String): String? {
        val token = peek()?.takeIf { it.kind == Kind.OPERATOR && it.text in operators } ?: return null
        position++
        return token.text
    }

    private fun expect(kind: Kind, text: String) {
        if (next()?.kind != kind) throw problem("lacks $text")
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private fun next(): Token? = tokens.getOrNull(position)?.also { position++ }

    private fun problem(problem: String) = DatasetExpressionException("The expression [$text] $problem.")

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per kind of token.
    private fun tokenize(): List<Token> {
        if (text.length > DatasetExpression.MAX_LENGTH) {
            throw problem("is longer than ${DatasetExpression.MAX_LENGTH} characters")
        }
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isWhitespace() -> index++
                char.isLetter() || char == '_' -> {
                    val end = text.indexOfFirstFrom(index) { !(it.isLetterOrDigit() || it == '_') }
                    tokens += Token(Kind.NAME, text.substring(index, end))
                    index = end
                }

                char.isDigit() -> {
                    val match = NUMBER.matchAt(text, index)!!
                    tokens += Token(Kind.NUMBER, match.value)
                    index += match.value.length
                }

                char == '\'' -> {
                    val value = StringBuilder()
                    index++
                    while (true) {
                        if (index >= text.length) throw problem("has a text without its closing quote")
                        if (text[index] == '\'') {
                            if (text.getOrNull(index + 1) == '\'') {
                                value.append('\'')
                                index += 2
                                continue
                            }
                            index++
                            break
                        }
                        value.append(text[index++])
                    }
                    tokens += Token(Kind.STRING, value.toString())
                }

                char == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end < 0) throw problem("has a name without its closing backquote")
                    val name = text.substring(index + 1, end)
                    if (name.isEmpty()) throw problem("has an empty name")
                    tokens += Token(Kind.QUOTED, name)
                    index = end + 1
                }

                char == '(' -> tokens += Token(Kind.OPEN, "(").also { index++ }
                char == ')' -> tokens += Token(Kind.CLOSE, ")").also { index++ }
                char == ',' -> tokens += Token(Kind.COMMA, ",").also { index++ }
                char == '.' -> tokens += Token(Kind.DOT, ".").also { index++ }
                else -> {
                    val operator = OPERATORS.firstOrNull { text.startsWith(it, index) } ?: throw problem("has $char")
                    tokens += Token(Kind.OPERATOR, operator)
                    index += operator.length
                }
            }
        }
        return tokens
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        var index = start
        while (index < length && !predicate(this[index])) index++
        return index
    }

    private enum class Kind { NAME, QUOTED, NUMBER, STRING, OPERATOR, OPEN, CLOSE, COMMA, DOT }

    private data class Token(val kind: Kind, val text: String)

    private companion object {
        val NUMBER = Regex("""\d+(\.\d+)?([eE][+-]?\d+)?""")

        /** The operators, the longer before their prefixes. */
        val OPERATORS = listOf("<=", ">=", "!=", "<>", "=", "<", ">", "+", "-", "*", "/", "%")
        val COMPARISONS = setOf("=", "!=", "<>", "<", "<=", ">", ">=")
    }
}

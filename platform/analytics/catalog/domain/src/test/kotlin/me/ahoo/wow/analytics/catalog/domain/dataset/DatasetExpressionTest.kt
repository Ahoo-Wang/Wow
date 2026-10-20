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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.Binary
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.Call
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.In
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.IsNull
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.Literal
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.Ref
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetExpression.Unary
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DatasetExpressionTest {
    private fun number(text: String) = Literal(Literal.Kind.NUMBER, text)

    @Test
    fun `operators should bind as SQL does, or loosest, then and, not, comparisons, sums and products`() {
        DatasetExpression.parse("o.a + o.b * 2 > 10 and not o.c is null or o.d in ('x', 'it''s')").assert().isEqualTo(
            Binary(
                "OR",
                Binary(
                    "AND",
                    Binary(">", Binary("+", Ref("o", "a"), Binary("*", Ref("o", "b"), number("2"))), number("10")),
                    Unary("NOT", IsNull(Ref("o", "c"), negated = false)),
                ),
                In(
                    Ref("o", "d"),
                    listOf(Literal(Literal.Kind.STRING, "x"), Literal(Literal.Kind.STRING, "it's")),
                    false
                ),
            ),
        )
        DatasetExpression.parse("-(o.a - 1.5e3) / o.b % 4 <> 0").assert().isEqualTo(
            Binary(
                "!=",
                Binary(
                    "%",
                    Binary("/", Unary("-", Binary("-", Ref("o", "a"), number("1.5e3"))), Ref("o", "b")),
                    number("4")
                ),
                number("0"),
            ),
        )
        DatasetExpression.parse("o.a NOT IN (1, 2) AND o.b IS NOT NULL").assert().isEqualTo(
            Binary("AND", In(Ref("o", "a"), listOf(number("1"), number("2")), true), IsNull(Ref("o", "b"), true)),
        )
    }

    @Test
    fun `references, calls and literals should be read as written`() {
        val parsed = DatasetExpression.parse(
            "if(o.`unit price` >= 1, m.state.customer.phone, null) = true OR today() = false"
        )
        parsed.assert().isEqualTo(
            Binary(
                "OR",
                Binary(
                    "=",
                    Call(
                        "if",
                        listOf(
                            Binary(">=", Ref("o", "unit price"), number("1")),
                            Ref("m", "state", listOf("customer", "phone")),
                            Literal(Literal.Kind.NULL, "null"),
                        ),
                    ),
                    Literal(Literal.Kind.BOOLEAN, "true"),
                ),
                Binary("=", Call("today", emptyList()), Literal(Literal.Kind.BOOLEAN, "false")),
            ),
        )
        parsed.refs().map { it.toString() }.assert().containsExactly("o.unit price", "m.state.customer.phone")
        DatasetExpression.parse("o.a <= 1 or o.b < 2 or o.c != 3").refs().assert().hasSize(3)
        DatasetExpression.parse("not o.a in (o.b, 1) and -o.c is null").refs().map { it.column }.assert()
            .containsExactly("a", "b", "c")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            " |is empty",
            "sleep(1)|calls sleep",
            "amount|names amount",
            "`amount`|names amount",
            "o.a)|has ) where it should end",
            "'open|without its closing quote",
            "o.`a|without its closing backquote",
            "o.``|has an empty name",
            "o.a in ()|has an empty list",
            "o.a is 1|without null",
            "o.a is not 1|is not without null",
            "o.|ends with a dot",
            "o.a; drop|has ;",
            "(o.a + 1|lacks )",
            "concat(o.a o.b)|where , or ) should be",
            "concat(o.a,|ends too early",
            "o.a +|ends too early",
            "o.a = ,|has , where a value should be",
            "lower(o.a|ends before )",
        ],
    )
    fun `a malformed expression should say what is wrong with it`(text: String?, problem: String) {
        assertThrownBy<DatasetExpressionException> { DatasetExpression.parse(text.orEmpty()) }
            .hasMessageContaining(problem)
    }

    @Test
    fun `an expression should be bounded in length and depth`() {
        assertThrownBy<DatasetExpressionException> { DatasetExpression.parse("o.a + ".repeat(1000) + "1") }
            .hasMessageContaining("longer than")
        assertThrownBy<DatasetExpressionException> { DatasetExpression.parse("(".repeat(40) + "o.a" + ")".repeat(40)) }
            .hasMessageContaining("nests deeper")
        assertThrownBy<DatasetExpressionException> { DatasetExpression.parse("-".repeat(40) + "o.a") }
            .hasMessageContaining("nests deeper")
        assertThrownBy<DatasetExpressionException> { DatasetExpression.parse("not ".repeat(40) + "o.a") }
            .hasMessageContaining("nests deeper")
    }
}

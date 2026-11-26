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

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Round two of the deep review, security (suspicion 7 of #7021): the tokenizer of a dataset's expression takes a
 * number at any character `Char.isDigit()` accepts, Unicode's decimal digits included (`٣`, `３`), then matches it with
 * `\d`, which is ASCII alone, and dereferences the match with `!!`. Such an expression ends in a NullPointerException,
 * a failure of the command, where every other expression the tokenizer cannot read is a [DatasetExpressionException]
 * naming it.
 */
class R2ExpressionDigitTest {
    @ParameterizedTest
    @ValueSource(strings = ["o.a > ٣", "o.a > ３", "١ + 1"])
    fun `a digit that is not ASCII is refused as an expression the catalog cannot read, not a NullPointerException`(
        text: String,
    ) {
        assertThrows<DatasetExpressionException> { DatasetExpression.parse(text) }
    }
}

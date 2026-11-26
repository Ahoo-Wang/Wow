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

package me.ahoo.wow.mongo.query

import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.StringComparison
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Round two of the deep review, security (suspicion 3 of #7021): a query body may hold a lone surrogate (`"\ud800"`
 * in JSON), and a match is sent to MongoDB as a `$regex` of its operand. Should the driver write the surrogate as `?`,
 * the pattern would read a quantifier the operand never held: `a\ud800` would match `a` and nothing, `\ud800b` be
 * refused by the server. What the reference answers is what MongoDB should answer, or the operand is refused as a
 * query the caller can mend.
 */
internal class R2LoneSurrogateTest : MongoDifferentialFixtures() {
    @TestFactory
    fun `a lone surrogate in an operand matches as itself`(): List<DynamicTest> = listOf(
        "a\uD800",
        "\uD800b",
        "\uDC00",
    ).flatMap { operand ->
        StringComparison.entries.map { comparison ->
            sameOrRefused("contains ${operand.map { "U+%04X".format(it.code) }} $comparison") {
                ids(ContainsFilter(NAME, operand, comparison))
            }
        } + sameOrRefused("equals ${operand.map { "U+%04X".format(it.code) }}") { ids(EqualFilter(NAME, operand)) }
    }
}

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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Round two of the deep review, security (suspicion 3 of #7021): an ignore-case `contains` or `startsWith` is matched
 * with `~` and the core's folding pattern of its operand, four characters (`[aA]`) a letter, with no bound on its size
 * here, where MongoDB and Elasticsearch refuse a pattern past theirs as storage-unsupported. A 200,000-letter operand
 * fits the 256 KB body the codecs take. What the reference answers is what PostgreSQL should answer, or the operand is
 * refused as a query the caller can mend; the server's refusal of the pattern, answered as a storage failure, is the
 * defect.
 */
internal class R2IgnoreCasePatternTest : PostgresDifferentialFixtures() {
    @TestFactory
    fun `an ignore-case match of a long operand answers what the reference answers`(): List<DynamicTest> = listOf(
        sameOrRefused("contains, ignoring case, 200,000 letters") {
            ids(ContainsFilter(NAME, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE))
        },
        sameOrRefused("startsWith, ignoring case, 200,000 letters") {
            ids(StartsWithFilter(NAME, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE))
        },
    )

    private companion object {
        const val LETTERS = 200_000
    }
}

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

import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.QueryBudget
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Round two of the deep review, security (suspicion 4 of #7021): PostgreSQL's protocol counts a statement's bind
 * parameters in 16 bits, so pgjdbc refuses a statement of more than 65,535 (SQLSTATE 08006 or 22023). The default
 * HTTP budget admits 128 filter nodes of 1,000 values each, so an `or` of 70 `in`s of 1,000 ids, 71 nodes, is a query
 * the budget takes, and the backend binds each value as a parameter of its own. What the reference answers is what
 * PostgreSQL should answer, or the query is refused as one the caller can mend; a storage failure is the defect.
 */
internal class R2BindParametersTest : PostgresDifferentialFixtures() {
    @TestFactory
    fun `a query the default budget admits binds no more parameters than PostgreSQL takes`(): List<DynamicTest> = listOf(
        sameOrRefused("an or of 70 ins of 1,000 ids") {
            val known = ids(MatchAllFilter)
            val lists = List(INS) { list ->
                val unknown = List(QueryBudget.DEFAULT_MAX_FILTER_VALUES) { "unknown-$list-$it" }
                InFilter(QueryField("aggregateId"), (if (list == 0) known + unknown else unknown).take(QueryBudget.DEFAULT_MAX_FILTER_VALUES))
            }
            ids(OrFilter(lists)).sorted()
        },
    )

    private companion object {
        const val INS = 70
    }
}

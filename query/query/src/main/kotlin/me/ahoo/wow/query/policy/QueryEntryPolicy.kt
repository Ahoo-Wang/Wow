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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBudget
import kotlin.time.Duration

/**
 * The budget each [QueryEntry] runs under, which a gateway applies before admitting a query.
 *
 * @property http the budget of [QueryEntry.HTTP] queries.
 * @property inProcess the budget of [QueryEntry.IN_PROCESS] queries; `null`, the
 * default, checks nothing. A host gives it [QueryBudget.IN_PROCESS_DEFAULT] by default (`wow.query.budgets.in-process.*`),
 * so in-process code is bounded as a request is.
 * @property inProcessTimeout the execution timeout of a query whose entry has no budget ([QueryEntry.UNBOUNDED], or
 * every in-process query when [inProcess] is `null`: every query has one); `null` runs such a query
 * without one.
 */
@InternalWowApi
class QueryEntryPolicy(
    val http: QueryBudget = QueryBudget.HTTP_DEFAULT,
    val inProcess: QueryBudget? = null,
    val inProcessTimeout: Duration? = QueryBudget.DEFAULT_TIMEOUT,
) {
    init {
        require(inProcessTimeout == null || inProcessTimeout.isPositive()) { "inProcessTimeout must be positive." }
    }

    fun budget(entry: QueryEntry): QueryBudget? = when (entry) {
        QueryEntry.HTTP -> http
        QueryEntry.IN_PROCESS -> inProcess
        QueryEntry.UNBOUNDED -> null
    }

    /** The execution timeout of [entry]'s queries: its budget's, or [inProcessTimeout] for an entry without one. */
    fun timeout(entry: QueryEntry): Duration? {
        val budget = budget(entry) ?: return inProcessTimeout
        return budget.timeout.takeUnless { it == Duration.ZERO }
    }

    @InternalWowApi
    companion object {
        val DEFAULT = QueryEntryPolicy()

        /** A [QueryEntryPolicy] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            http: QueryBudget = QueryBudget.HTTP_DEFAULT,
            inProcess: QueryBudget? = null,
            inProcessTimeout: Duration? = QueryBudget.DEFAULT_TIMEOUT,
        ): QueryEntryPolicy = QueryEntryPolicy(http, inProcess, inProcessTimeout)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryEntryPolicy && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryEntryPolicy(http=$http, inProcess=$inProcess, inProcessTimeout=$inProcessTimeout)"

    private fun valueFields(): List<Any?> = listOf(http, inProcess, inProcessTimeout)
}

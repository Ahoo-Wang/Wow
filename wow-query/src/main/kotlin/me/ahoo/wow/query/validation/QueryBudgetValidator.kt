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

package me.ahoo.wow.query.validation

import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import java.time.Duration

data class QueryBudgetLimit(
    val timeout: Duration? = null,
    val maxResults: Long? = null,
    val maxCost: Long? = null
) {
    init {
        require(timeout == null || !timeout.isNegative) { "timeout cannot be negative." }
        require(maxResults == null || maxResults >= 0) { "maxResults cannot be negative." }
        require(maxCost == null || maxCost >= 0) { "maxCost cannot be negative." }
    }

    companion object {
        @JvmField
        val UNBOUNDED: QueryBudgetLimit = QueryBudgetLimit()

        @JvmStatic
        fun min(
            requestHint: QueryBudgetHint?,
            systemLimit: QueryBudgetLimit,
            policyLimit: QueryBudgetLimit,
            backendLimit: QueryBudgetLimit
        ): QueryBudgetLimit = QueryBudgetValidator.min(
            requestHint,
            systemLimit,
            policyLimit,
            backendLimit
        )
    }
}

object QueryBudgetValidator {
    fun min(
        requestHint: QueryBudgetHint?,
        systemLimit: QueryBudgetLimit,
        policyLimit: QueryBudgetLimit,
        backendLimit: QueryBudgetLimit
    ): QueryBudgetLimit = QueryBudgetLimit(
        timeout = minFinite(
            requestHint?.timeout,
            systemLimit.timeout,
            policyLimit.timeout,
            backendLimit.timeout
        ),
        maxResults = minFinite(
            requestHint?.maxResults,
            systemLimit.maxResults,
            policyLimit.maxResults,
            backendLimit.maxResults
        ),
        maxCost = minFinite(
            requestHint?.maxCost,
            systemLimit.maxCost,
            policyLimit.maxCost,
            backendLimit.maxCost
        )
    )

    private fun <T : Comparable<T>> minFinite(vararg limits: T?): T? {
        var minimum: T? = null
        limits.forEach { limit ->
            if (limit != null && (minimum == null || limit < minimum)) {
                minimum = limit
            }
        }
        return minimum
    }
}

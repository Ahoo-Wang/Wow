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

package me.ahoo.wow.query.plan

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.validation.QueryBudgetLimit

internal class QueryPlanValidator {
    fun validateBudget(request: QueryRequest, budget: QueryBudgetLimit) {
        val maximum = budget.maxResults ?: return
        val requested = when (request) {
            is SingleQueryRequest<*> -> 1L
            is ListQueryRequest<*> -> request.limit.toLong().takeIf { it > 0 }
            is PageQueryRequest<*> -> request.page.size.toLong()
            is CountQueryRequest -> null
        }
        if (requested != null && requested > maximum) {
            throw QueryException(
                QueryErrorCode.BUDGET_EXCEEDED,
                QueryStage.PLANNING,
                QueryErrorReason.BUDGET_LIMIT_REACHED
            )
        }
    }
}

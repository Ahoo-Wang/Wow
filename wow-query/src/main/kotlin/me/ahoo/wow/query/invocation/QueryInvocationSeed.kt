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

package me.ahoo.wow.query.invocation

import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.immutableSnapshot
import me.ahoo.wow.query.validation.QueryBudgetLimit
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

internal class QueryInvocationSeed(
    val request: QueryRequest,
    val operation: QueryOperation,
    expressionContributions: Map<QueryProvenance, QueryExpression>,
    val scope: QueryInvocationScope,
    val frozenInstant: Instant,
    val zoneId: ZoneId,
    val admissionDeadline: Instant?,
    val admissionBudget: QueryBudgetLimit,
    val deadlineGuard: QueryDeadlineGuard
) {
    val expressionContributions: Map<QueryProvenance, QueryExpression> =
        Collections.unmodifiableMap(LinkedHashMap(expressionContributions))

    init {
        require(
            this.expressionContributions.keys == setOf(QueryProvenance.CALLER_REQUEST) ||
                this.expressionContributions.keys == linkedSetOf(
                    QueryProvenance.CALLER_REQUEST,
                    QueryProvenance.LEGACY_ENRICHMENT
                )
        ) {
            "Invocation expression contributions contain unsupported provenance."
        }
        require(this.expressionContributions.getValue(QueryProvenance.CALLER_REQUEST) === request.expression) {
            "Caller contribution must be the request expression."
        }
    }

    fun toInvocation(
        schema: QuerySchemaView,
        normalize: (QueryExpression) -> QueryExpression
    ): QueryInvocation {
        val schemaSnapshot = schema.immutableSnapshot()
        val normalizedContributions = LinkedHashMap<QueryProvenance, QueryExpression>(expressionContributions.size)
        expressionContributions.forEach { (provenance, expression) ->
            normalizedContributions[provenance] = normalize(expression)
        }
        val normalizedExpression = ExpressionNormalizer.logical(
            LogicalOperator.AND,
            normalizedContributions.values.toList()
        )
        return QueryInvocation(
            request = request,
            operation = operation,
            scope = scope,
            frozenInstant = frozenInstant,
            zoneId = zoneId,
            admissionDeadline = admissionDeadline,
            admissionBudget = admissionBudget,
            deadlineGuard = deadlineGuard,
            schema = schemaSnapshot,
            normalizedExpression = normalizedExpression,
            expressionProvenance = normalizedContributions
        )
    }
}

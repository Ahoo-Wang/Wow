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

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.validation.QueryBudgetLimit
import java.time.Instant
import java.util.Collections

sealed interface QueryPlanV1 {
    val version: QueryPlanVersion
    val target: QueryTarget
    val securedExpression: QueryExpression
    val expressionProvenance: Map<QueryProvenance, QueryExpression>
    val authorizedResultShape: QueryPlanResultShape
    val sort: List<QuerySort>
    val effectiveDeadline: Instant?
    val effectiveBudget: QueryBudgetLimit
    val correlationId: String
    val routeIdentity: QueryBackendRouteIdentity
}

sealed interface SingleQueryPlanV1<R : Any> : QueryPlanV1

sealed interface ListQueryPlanV1<R : Any> : QueryPlanV1 {
    val limit: Int
}

sealed interface PageQueryPlanV1<R : Any> : QueryPlanV1 {
    val page: QueryPageSpec
}

sealed interface CountQueryPlanV1 : QueryPlanV1

sealed interface QueryPlanResultShape {
    class Typed(
        val resultType: Class<*>,
        fields: Set<LogicalField>
    ) : QueryPlanResultShape {
        val fields: Set<LogicalField> = immutableFields(fields)

        override fun equals(other: Any?): Boolean = other is Typed &&
            resultType == other.resultType && fields == other.fields

        override fun hashCode(): Int = 31 * resultType.hashCode() + fields.hashCode()

        override fun toString(): String = "QueryPlanResultShape.Typed(resultType=<redacted>, fields=<redacted>)"
    }

    class Dynamic(fields: Set<LogicalField>) : QueryPlanResultShape {
        val fields: Set<LogicalField> = immutableFields(fields)

        override fun equals(other: Any?): Boolean = other is Dynamic && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "QueryPlanResultShape.Dynamic(fields=<redacted>)"
    }

    data object Count : QueryPlanResultShape

    companion object {
        private fun immutableFields(source: Set<LogicalField>): Set<LogicalField> =
            Collections.unmodifiableSet(LinkedHashSet(source))
    }
}

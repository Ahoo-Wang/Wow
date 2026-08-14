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

package me.ahoo.wow.cosec.query

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import reactor.core.publisher.Mono

class CoSecQueryPolicy : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> = Mono.defer {
        val authority = context.invocationScope.trustedAuthority
        val requestedScope = context.invocationScope.requestedScope
        val tenantId = authority.tenantId ?: throw denied("COSEC_TENANT_REQUIRED")
        if (requestedScope.tenantId != null && requestedScope.tenantId != tenantId) {
            throw denied("COSEC_TENANT_MISMATCH")
        }
        if (authority.spaceIds.isEmpty()) {
            throw denied("COSEC_SPACE_REQUIRED")
        }
        if (requestedScope.spaceId != null && requestedScope.spaceId !in authority.spaceIds) {
            throw denied("COSEC_SPACE_MISMATCH")
        }

        Mono.just(
            QueryPolicyResult(
                PortableLogicalExpression(
                    LogicalOperator.AND,
                    listOf(tenantExpression(tenantId), spaceExpression(requestedScope.spaceId, authority.spaceIds))
                )
            )
        )
    }

    private fun tenantExpression(tenantId: String): PredicateExpression = PredicateExpression(
        LogicalField("tenantId"),
        PortableOperator.EQ,
        listOf(QueryValue.StringValue(tenantId))
    )

    private fun spaceExpression(requestedSpaceId: String?, trustedSpaceIds: Set<String>): PredicateExpression {
        if (requestedSpaceId != null) {
            return PredicateExpression(
                LogicalField("spaceId"),
                PortableOperator.EQ,
                listOf(QueryValue.StringValue(requestedSpaceId))
            )
        }
        val sortedSpaceIds = trustedSpaceIds.sorted()
        return PredicateExpression(
            LogicalField("spaceId"),
            if (sortedSpaceIds.size == 1) PortableOperator.EQ else PortableOperator.IN,
            sortedSpaceIds.map(QueryValue::StringValue)
        )
    }

    private fun denied(reasonCode: String): QueryPolicyDeniedException = QueryPolicyDeniedException(reasonCode)
}

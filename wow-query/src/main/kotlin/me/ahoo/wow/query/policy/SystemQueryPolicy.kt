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

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.validation.QueryBudgetLimit
import reactor.core.publisher.Mono

object QueryPolicyPermissions {
    const val QUERY_DELETED_SNAPSHOTS: String = "query:snapshot:deletion"
}

private const val DELETION_SCOPE_DENIED: String = "DELETION_SCOPE_DENIED"
private val DELETED_FIELD: LogicalField = LogicalField("deleted")

internal class SystemQueryPolicy(
    private val systemBudgetLimit: QueryBudgetLimit
) : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> = Mono.defer {
        Mono.just(
            QueryPolicyResult(
                mandatoryExpression = deletionExpression(context),
                constraints = QueryPolicyConstraints(
                    fieldAccess = QueryFieldAccess.Restricted(context.schema.fields.keys),
                    maxBudget = systemBudgetLimit
                )
            )
        )
    }

    private fun deletionExpression(context: QueryPolicyContext): PortableExpression {
        if (context.target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            return MatchAll
        }
        return when (context.invocationScope.requestedScope.deletion) {
            DeletionScope.DEFAULT,
            DeletionScope.ACTIVE -> deleted(false)

            DeletionScope.DELETED -> {
                requireDeletionPermission(context)
                deleted(true)
            }

            DeletionScope.ALL -> {
                requireDeletionPermission(context)
                MatchAll
            }
        }
    }

    private fun requireDeletionPermission(context: QueryPolicyContext) {
        if (QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS !in context.invocationScope.trustedAuthority.permissions) {
            throw QueryPolicyDeniedException(DELETION_SCOPE_DENIED)
        }
    }

    private fun deleted(value: Boolean): PredicateExpression = PredicateExpression(
        field = DELETED_FIELD,
        operator = if (value) PortableOperator.TRUE else PortableOperator.FALSE,
        values = emptyList()
    )
}

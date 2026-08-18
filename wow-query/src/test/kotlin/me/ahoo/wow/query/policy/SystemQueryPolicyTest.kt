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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Instant
import java.time.ZoneOffset

class SystemQueryPolicyTest {
    @Test
    fun `injects exactly one active predicate for default and active snapshot scopes`() {
        listOf(DeletionScope.DEFAULT, DeletionScope.ACTIVE).forEach { deletion ->
            val result = policy().evaluate(context(QueryDocumentKind.SNAPSHOT, deletion)).block()!!

            result.mandatoryExpression.assert().isEqualTo(deleted(false))
            result.constraints.capabilityAccess.assert().isEmpty()
            (result.constraints.fieldAccess as QueryFieldAccess.Restricted).fields
                .assert().isEqualTo(context(QueryDocumentKind.SNAPSHOT, deletion).schema.fields.keys)
        }
    }

    @Test
    fun `requires stable permission for deleted and all snapshot scopes`() {
        listOf(DeletionScope.DELETED, DeletionScope.ALL).forEach { deletion ->
            StepVerifier.create(policy().evaluate(context(QueryDocumentKind.SNAPSHOT, deletion)))
                .expectError(QueryPolicyDeniedException::class.java)
                .verify()
        }

        policy().evaluate(
            context(
                QueryDocumentKind.SNAPSHOT,
                DeletionScope.DELETED,
                setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
            )
        ).block()!!.mandatoryExpression.assert().isEqualTo(deleted(true))
        policy().evaluate(
            context(
                QueryDocumentKind.SNAPSHOT,
                DeletionScope.ALL,
                setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
            )
        ).block()!!.mandatoryExpression.assert().isSameAs(MatchAll)
    }

    @Test
    fun `does not apply snapshot deletion semantics to event streams`() {
        DeletionScope.entries.forEach { deletion ->
            val result = policy().evaluate(context(QueryDocumentKind.EVENT_STREAM, deletion)).block()!!
            result.mandatoryExpression.assert().isSameAs(MatchAll)
        }
    }

    @Test
    fun `system policy cannot be inserted as a custom policy or removed`() {
        val system = policy()

        StepVerifier.create(
            reactor.core.publisher.Mono.fromCallable {
                DefaultQueryPolicyChain(
                    system,
                    listOf(QueryPolicyRegistration("replacement", 0, system)),
                    me.ahoo.wow.query.validation.QueryExpressionValidator(
                        me.ahoo.wow.query.validation.QueryStructureLimits(64, 10_000, 10_000, 1_048_576)
                    )
                )
            }
        ).expectError(IllegalArgumentException::class.java).verify()
    }

    @Test
    fun `maps system policy denial through the stable policy error triplet`() {
        val chain = DefaultQueryPolicyChain(
            policy(),
            emptyList(),
            me.ahoo.wow.query.validation.QueryExpressionValidator(
                me.ahoo.wow.query.validation.QueryStructureLimits(64, 10_000, 10_000, 1_048_576)
            )
        )

        StepVerifier.create(
            chain.evaluate(
                context(QueryDocumentKind.SNAPSHOT, DeletionScope.ALL),
                QueryDeadlineGuard.anchor(FROZEN, VirtualTimeScheduler.create())
            )
        )
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
                    stage.assert().isEqualTo(QueryStage.POLICY)
                    reason.assert().isEqualTo(QueryErrorReason.POLICY_EVALUATION_FAILED)
                }
            }.verify()
    }

    private fun policy(): SystemQueryPolicy = SystemQueryPolicy(SYSTEM_BUDGET)

    private fun context(
        kind: QueryDocumentKind,
        deletion: DeletionScope,
        permissions: Set<String> = emptySet()
    ): QueryPolicyContext {
        val target = target(kind)
        val schema = QuerySchema(target, QuerySystemFields.fields(kind))
        return QueryPolicyContext(
            target,
            QueryOperation.COUNT,
            MatchAll,
            QueryPolicyResultShape.Count,
            QueryInvocationScope(
                QueryAuthorityView("subject", "tenant", null, emptySet(), permissions),
                RequestedQueryScope(deletion = deletion),
                "correlation"
            ),
            schema,
            QueryBudgetHint(),
            FROZEN,
            ZoneOffset.UTC
        )
    }

    private fun target(kind: QueryDocumentKind): QueryTarget = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = "example"
            override val aggregateName: String = "order"
        },
        kind
    )

    private fun deleted(value: Boolean): PredicateExpression = PredicateExpression(
        LogicalField("deleted"),
        if (value) PortableOperator.TRUE else PortableOperator.FALSE,
        emptyList()
    )

    private companion object {
        val FROZEN: Instant = Instant.parse("2026-08-12T08:00:00Z")
        val SYSTEM_BUDGET: QueryBudgetLimit = QueryBudgetLimit(maxResults = 1_000, maxCost = 100)
    }
}

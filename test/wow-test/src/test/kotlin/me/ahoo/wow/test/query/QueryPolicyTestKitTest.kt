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

package me.ahoo.wow.test.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchemaView
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class QueryPolicyTestKitTest {
    @Test
    fun `builder uses stable explicit defaults`() {
        val context = QueryPolicyContextBuilder().build()

        context.target.namedAggregate.contextName.assert().isEqualTo("test")
        context.target.namedAggregate.aggregateName.assert().isEqualTo("query-policy")
        context.target.documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
        context.operation.assert().isEqualTo(QueryOperation.SINGLE)
        context.normalizedExpression.assert().isSameAs(MatchAll)
        context.resultShape.assert().isSameAs(QueryPolicyResultShape.Dynamic)
        context.invocationScope.trustedAuthority.assert().isEqualTo(QueryPolicyContextBuilder.DEFAULT_AUTHORITY)
        context.invocationScope.requestedScope.assert().isEqualTo(RequestedQueryScope())
        context.requestBudget.assert().isEqualTo(QueryBudgetHint())
        context.frozenInstant.assert().isEqualTo(QueryPolicyContextBuilder.DEFAULT_FROZEN_INSTANT)
        context.zoneId.assert().isEqualTo(QueryPolicyContextBuilder.DEFAULT_ZONE_ID)
    }

    @Test
    fun `builder changes return independent contexts`() {
        val base = QueryPolicyContextBuilder()
        val target = target("sales", "invoice")
        val authority = QueryAuthorityView("subject", "tenant", "owner", setOf("space"), setOf("query"))
        val expression = predicate(STATUS, "OPEN")
        val changed = base
            .withTarget(target)
            .withAuthority(authority)
            .withRequestedScope(RequestedQueryScope(tenantId = "tenant"))
            .withOperation(QueryOperation.COUNT)
            .withExpression(expression)
            .withResultShape(QueryPolicyResultShape.Count)

        base.build().target.assert().isEqualTo(QueryPolicyContextBuilder.DEFAULT_TARGET)
        changed.build().apply {
            this.target.assert().isEqualTo(target)
            operation.assert().isEqualTo(QueryOperation.COUNT)
            invocationScope.trustedAuthority.assert().isEqualTo(authority)
            invocationScope.requestedScope.assert().isEqualTo(RequestedQueryScope(tenantId = "tenant"))
            normalizedExpression.assert().isEqualTo(expression)
            resultShape.assert().isSameAs(QueryPolicyResultShape.Count)
        }
    }

    @Test
    fun `built context owns a defensive schema snapshot`() {
        val target = target("sales", "invoice")
        val fields = linkedMapOf(STATUS to QueryFieldSchema.string(STATUS, false))
        val mutableSchema = object : QuerySchemaView {
            override val target: QueryTarget = target
            override val fields: Map<LogicalField, QueryFieldSchema> = fields
        }
        val context = QueryPolicyContextBuilder()
            .withTarget(target)
            .withSchema(mutableSchema)
            .build()

        fields.clear()

        context.schema.fields.keys.assert().isEqualTo(setOf(STATUS))
    }

    @Test
    fun `tests target applicable and not applicable policies`() {
        val expected = predicate(STATUS, "OPEN")
        val policy = QueryPolicy { context ->
            Mono.just(
                QueryPolicyResult(
                    if (context.target.namedAggregate.aggregateName == "invoice") expected else MatchAll
                )
            )
        }

        QueryPolicyTestKit(policy, builderFor("invoice").build())
            .expectMandatory(expected)
            .block()
        QueryPolicyTestKit(policy, builderFor("shipment").build())
            .expectMandatory(MatchAll)
            .block()
    }

    @Test
    fun `tests tenant matches and reports exact mismatch reason`() {
        val policy = QueryPolicy { context ->
            if (context.invocationScope.trustedAuthority.tenantId ==
                context.invocationScope.requestedScope.tenantId
            ) {
                Mono.just(QueryPolicyResult())
            } else {
                Mono.error(QueryPolicyDeniedException("TENANT_MISMATCH"))
            }
        }
        val authority = QueryAuthorityView("subject", "tenant-a", null, emptySet(), emptySet())

        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder()
                .withAuthority(authority)
                .withRequestedScope(RequestedQueryScope(tenantId = "tenant-a"))
                .build()
        ).expectMandatory(MatchAll).block()
        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder()
                .withAuthority(authority)
                .withRequestedScope(RequestedQueryScope(tenantId = "tenant-b"))
                .build()
        ).expectDenied("TENANT_MISMATCH").block()
    }

    @Test
    fun `evaluate exposes field intersections and capability decisions`() {
        val capability = QueryCapabilityId("x-test:search")
        val schemaFields = listOf(
            QueryFieldSchema.string(STATUS, false),
            QueryFieldSchema(
                TENANT,
                QueryFieldValueKind.STRING,
                false,
                capabilities = setOf(capability)
            )
        )

        CapabilityDecision.entries.forEach { decision ->
            val policy = QueryPolicy { context ->
                Mono.just(
                    QueryPolicyResult(
                        constraints = QueryPolicyConstraints(
                            fieldAccess = QueryFieldAccess.Restricted(
                                context.schema.fields.keys.intersect(setOf(STATUS, LogicalField("missing")))
                            ),
                            capabilityAccess = mapOf(capability to decision)
                        )
                    )
                )
            }
            val result = QueryPolicyTestKit(
                policy,
                builderFor("invoice").withSchemaFields(schemaFields).build()
            ).evaluate().block()!!

            result.constraints.fieldAccess.assert().isEqualTo(QueryFieldAccess.Restricted(setOf(STATUS)))
            result.constraints.capabilityAccess.assert().isEqualTo(mapOf(capability to decision))
        }
    }

    @Test
    fun `policy observes the builder frozen time and zone unchanged`() {
        val frozen = Instant.parse("2042-03-04T05:06:07Z")
        val zone = ZoneId.of("Asia/Shanghai")
        var observedInstant: Instant? = null
        var observedZone: ZoneId? = null
        var observedBudget: QueryBudgetHint? = null
        val policy = QueryPolicy { context ->
            observedInstant = context.frozenInstant
            observedZone = context.zoneId
            observedBudget = context.requestBudget
            Mono.just(QueryPolicyResult())
        }

        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder()
                .withFrozenInstant(frozen)
                .withZoneId(zone)
                .withBudget(QueryBudgetHint(timeout = Duration.ofSeconds(3), maxResults = 5, maxCost = 8))
                .build()
        ).evaluate().block()

        observedInstant.assert().isEqualTo(frozen)
        observedZone.assert().isEqualTo(zone)
        observedBudget.assert().isEqualTo(QueryBudgetHint(Duration.ofSeconds(3), 5, 8))
    }

    @Test
    fun `expect denied rejects wrong reason success and empty results`() {
        val context = QueryPolicyContextBuilder().build()
        val wrongReason = QueryPolicyTestKit(
            QueryPolicy { Mono.error(QueryPolicyDeniedException("ACTUAL_REASON")) },
            context
        ).expectDenied("EXPECTED_REASON")
        val success = QueryPolicyTestKit(
            QueryPolicy { Mono.just(QueryPolicyResult()) },
            context
        ).expectDenied("EXPECTED_REASON")
        val empty = QueryPolicyTestKit(QueryPolicy { Mono.empty() }, context)
            .expectDenied("EXPECTED_REASON")

        listOf(wrongReason, success, empty).forEach { assertion ->
            StepVerifier.create(assertion)
                .expectError(AssertionError::class.java)
                .verify()
        }
    }

    @Test
    fun `evaluate preserves unexpected policy errors`() {
        val expected = IllegalStateException("policy failed")
        val evaluation = QueryPolicyTestKit(
            QueryPolicy { Mono.error(expected) },
            QueryPolicyContextBuilder().build()
        ).evaluate()

        StepVerifier.create(evaluation)
            .expectErrorMatches { it === expected }
            .verify()
    }

    @Test
    fun `public helpers redact policy context and scope details`() {
        val sentinel = "tenant-secret-4729"
        val builder = QueryPolicyContextBuilder()
            .withAuthority(QueryAuthorityView("subject", sentinel, null, emptySet(), setOf("secret-permission")))
            .withRequestedScope(RequestedQueryScope(tenantId = sentinel))
            .withCorrelationId("secret-correlation")
        val kit = QueryPolicyTestKit(QueryPolicy { Mono.just(QueryPolicyResult()) }, builder.build())

        builder.toString().assert().doesNotContain(sentinel, "secret-permission", "secret-correlation")
        kit.toString().assert().doesNotContain(sentinel, "secret-permission", "secret-correlation")
    }

    private fun builderFor(aggregateName: String): QueryPolicyContextBuilder = QueryPolicyContextBuilder()
        .withTarget(target("sales", aggregateName))

    private fun QueryPolicyContextBuilder.withSchemaFields(fields: Collection<QueryFieldSchema>): QueryPolicyContextBuilder =
        withSchema(
            object : QuerySchemaView {
                override val target: QueryTarget = this@withSchemaFields.target
                override val fields: Map<LogicalField, QueryFieldSchema> = fields.associateBy(QueryFieldSchema::path)
            }
        )

    private fun target(contextName: String, aggregateName: String): QueryTarget = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = contextName
            override val aggregateName: String = aggregateName
        },
        QueryDocumentKind.SNAPSHOT
    )

    private fun predicate(field: LogicalField, value: String): PredicateExpression = PredicateExpression(
        field,
        PortableOperator.EQ,
        listOf(QueryValue.StringValue(value))
    )

    private companion object {
        val STATUS: LogicalField = LogicalField("status")
        val TENANT: LogicalField = LogicalField("tenantId")
    }
}

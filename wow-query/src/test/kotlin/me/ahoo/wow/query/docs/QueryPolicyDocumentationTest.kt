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

package me.ahoo.wow.query.docs

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.gatewayConfiguration
import me.ahoo.wow.query.gatewayDescriptor
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.test.query.QueryPolicyTestKit
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class QueryPolicyDocumentationTest {
    @Test
    fun `tenant policy example is executable through the published test kit`() {
        val mandatory = tenantPredicate("tenant-a")

        QueryPolicyTestKit(TenantPolicy, context("tenant-a"))
            .expectMandatory(mandatory)
            .test()
            .verifyComplete()
    }

    @Test
    fun `tenant policy fails closed when trusted authority is missing`() {
        QueryPolicyTestKit(TenantPolicy, context(null))
            .expectDenied("TENANT_REQUIRED")
            .test()
            .verifyComplete()
    }

    @Test
    fun `non Spring factory and Spring ordering examples compile and execute`() {
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(backend, customPolicies = listOf(TenantPolicy))
        )

        gateway.assert().isNotNull()
        val beanMethod = PolicyConfiguration::class.java.getDeclaredMethod("tenantQueryPolicy")
        beanMethod.getAnnotation(Order::class.java).value.assert().isEqualTo(100)
    }

    private fun context(tenantId: String?): QueryPolicyContext = QueryPolicyContext(
        target = GATEWAY_TARGET,
        operation = QueryOperation.COUNT,
        normalizedExpression = MatchAll,
        resultShape = QueryPolicyResultShape.Count,
        invocationScope = QueryInvocationScope(
            trustedAuthority = QueryAuthorityView("subject", tenantId, null, emptySet(), emptySet()),
            requestedScope = RequestedQueryScope(),
            correlationId = "docs"
        ),
        schema = gatewaySchema(),
        requestBudget = QueryBudgetHint(),
        frozenInstant = Instant.EPOCH,
        zoneId = ZoneOffset.UTC
    )

    private object TenantPolicy : QueryPolicy {
        override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> {
            val tenantId = context.invocationScope.trustedAuthority.tenantId
                ?: return Mono.error(QueryPolicyDeniedException("TENANT_REQUIRED"))
            return Mono.just(
                QueryPolicyResult(
                    mandatoryExpression = tenantPredicate(tenantId),
                    constraints = QueryPolicyConstraints(
                        fieldAccess = QueryFieldAccess.Restricted(
                            setOf(LogicalField("tenantId"), LogicalField("deleted"), LogicalField("state.status"))
                        ),
                        capabilityAccess = mapOf(FULL_TEXT to CapabilityDecision.GRANT),
                        maxBudget = QueryBudgetLimit(
                            timeout = Duration.ofSeconds(2),
                            maxResults = 100,
                            maxCost = 1_000
                        )
                    )
                )
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class PolicyConfiguration {
        @Bean
        @Order(100)
        fun tenantQueryPolicy(): QueryPolicy = TenantPolicy
    }

    private companion object {
        val FULL_TEXT: QueryCapabilityId = QueryCapabilityId("full-text")

        fun tenantPredicate(tenantId: String): PredicateExpression = PredicateExpression(
            LogicalField("tenantId"),
            PortableOperator.EQ,
            listOf(QueryValue.StringValue(tenantId))
        )
    }
}

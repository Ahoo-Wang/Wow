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

package me.ahoo.wow.query.policy.abac

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.GATEWAY_SHAPE
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.gatewayConfiguration
import me.ahoo.wow.query.gatewayDescriptor
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaCustomizationContext
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Instant
import java.time.ZoneOffset

class AbacQueryPolicyTest {
    @Test
    fun `resolver snapshots declared keys and resolved tags immutably`() {
        val declared = linkedSetOf("dept")
        val values = mutableListOf("eng")
        val tags = linkedMapOf("dept" to values)
        val resolver = PrincipalTagResolver(declared) { Mono.just(tags) }

        declared += "late"
        val snapshot = resolver.resolve(context()).block()!!
        values += "late"
        tags["late"] = mutableListOf("value")

        resolver.declaredKeys.assert().containsExactly("dept")
        snapshot.assert().isEqualTo(mapOf("dept" to listOf("eng")))
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (resolver.declaredKeys as MutableSet<String>).add("mutate")
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableMap<String, List<String>>)["mutate"] = listOf("value")
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.getValue("dept") as MutableList<String>).add("mutate")
        }
    }

    @Test
    fun `schema customizer declares only finite snapshot scalar collection tags`() {
        val resolver = PrincipalTagResolver(linkedSetOf("dept", "role")) {
            error("schema customization must not resolve runtime tags")
        }
        val customizer = PrincipalTagSchemaCustomizer(resolver)
        val snapshotBase = QuerySchema(snapshotTarget, QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT))
        val eventBase = QuerySchema(eventTarget, QuerySystemFields.fields(QueryDocumentKind.EVENT_STREAM))

        val snapshot = customizer.customize(QuerySchemaCustomizationContext(snapshotTarget, snapshotBase))
        val event = customizer.customize(QuerySchemaCustomizationContext(eventTarget, eventBase))

        snapshot.fields.keys.assert().contains(LogicalField("tags.dept"), LogicalField("tags.role"))
        snapshot.field("tags.dept")!!.run {
            valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
            collectionKind.assert().isEqualTo(QueryCollectionKind.SCALAR)
            system.assert().isFalse()
        }
        event.fields.assert().isEqualTo(eventBase.fields)
    }

    @Test
    fun `non wildcard reproduces missing empty or intersects semantics exactly`() {
        val policy = policy(mapOf("dept" to listOf("eng", "pm")))
        val expected = or(
            predicate("tags.dept", PortableOperator.EXISTS, QueryValue.BooleanValue(false)),
            predicate("tags.dept", PortableOperator.EMPTY_COLLECTION),
            predicate(
                "tags.dept",
                PortableOperator.IN,
                QueryValue.StringValue("eng"),
                QueryValue.StringValue("pm")
            )
        )

        policy.evaluate(context()).test()
            .assertNext { result -> result.mandatoryExpression.assert().isEqualTo(expected) }
            .verifyComplete()
    }

    @Test
    fun `empty principal values still match only missing or empty resource collections`() {
        val policy = policy(mapOf("dept" to emptyList()))
        val expected = or(
            predicate("tags.dept", PortableOperator.EXISTS, QueryValue.BooleanValue(false)),
            predicate("tags.dept", PortableOperator.EMPTY_COLLECTION)
        )

        policy.evaluate(context()).test()
            .assertNext { result -> result.mandatoryExpression.assert().isEqualTo(expected) }
            .verifyComplete()
    }

    @Test
    fun `wildcard requires the declared resource tag to exist`() {
        val policy = policy(mapOf("dept" to listOf("restricted", "*")))

        policy.evaluate(context()).test()
            .assertNext { result ->
                result.mandatoryExpression.assert().isEqualTo(
                    predicate("tags.dept", PortableOperator.EXISTS, QueryValue.BooleanValue(true))
                )
            }
            .verifyComplete()
    }

    @Test
    fun `multiple principal tags use AND semantics`() {
        val policy = AbacQueryPolicy(
            PrincipalTagResolver(linkedSetOf("dept", "role")) {
                Mono.just(linkedMapOf("dept" to listOf("eng"), "role" to listOf("admin")))
            }
        )

        policy.evaluate(context()).test()
            .assertNext { result ->
                val mandatory = result.mandatoryExpression as PortableLogicalExpression
                mandatory.operator.assert().isEqualTo(LogicalOperator.AND)
                mandatory.operands.assert().hasSize(2)
            }
            .verifyComplete()
    }

    @Test
    fun `event stream is not ABAC filtered and does not resolve tags`() {
        var resolutions = 0
        val policy = AbacQueryPolicy(
            PrincipalTagResolver(setOf("dept")) {
                resolutions++
                Mono.error(AssertionError("must not resolve"))
            }
        )

        policy.evaluate(context(eventTarget)).test()
            .assertNext { result -> result.mandatoryExpression.assert().isSameAs(MatchAll) }
            .verifyComplete()
        resolutions.assert().isZero()
    }

    @Test
    fun `empty error empty map and undeclared runtime keys all fail closed`() {
        val failures = listOf(
            AbacQueryPolicy(PrincipalTagResolver(setOf("dept")) { Mono.empty() }) to "ABAC_TAGS_REQUIRED",
            AbacQueryPolicy(
                PrincipalTagResolver(setOf("dept")) { Mono.error(IllegalStateException("sensitive")) }
            ) to "ABAC_TAGS_UNAVAILABLE",
            policy(emptyMap()) to "ABAC_TAGS_REQUIRED",
            AbacQueryPolicy(
                PrincipalTagResolver(setOf("dept")) { Mono.just(mapOf("role" to listOf("admin"))) }
            ) to "ABAC_TAGS_UNDECLARED"
        )

        failures.forEach { (policy, reason) ->
            policy.evaluate(context()).test()
                .expectErrorSatisfies { error ->
                    (error as QueryPolicyDeniedException).reasonCode.assert().isEqualTo(reason)
                    error.message.orEmpty().assert().doesNotContain("sensitive", "role")
                }
                .verify()
        }
    }

    @Test
    fun `fatal principal resolver errors remain fatal`() {
        val fatal = OutOfMemoryError("fatal")
        val policy = AbacQueryPolicy(PrincipalTagResolver(setOf("dept")) { Mono.error(fatal) })

        assertThrows<OutOfMemoryError> { policy.evaluate(context()).block() }.assert().isSameAs(fatal)
    }

    @Test
    fun `fail closed policy stops gateway before backend resolution`() {
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val policy = AbacQueryPolicy(PrincipalTagResolver(setOf("dept")) { Mono.empty() })
        val schema = PrincipalTagSchemaCustomizer(PrincipalTagResolver(setOf("dept")) { Mono.empty() })
            .customize(QuerySchemaCustomizationContext(GATEWAY_TARGET, gatewaySchema()))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                customPolicies = listOf(policy),
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.just(schema)
                }
            )
        )

        gateway.single(SingleQueryRequest(GATEWAY_TARGET, resultShape = GATEWAY_SHAPE)).test()
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
            }
            .verify()
        backend.readinessSubscriptions.get().assert().isZero()
        backend.singleSubscriptions.get().assert().isZero()
    }

    @Test
    fun `public gateway and legacy QueryService receive the same mandatory ABAC expression`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1L))
        val resolver = PrincipalTagResolver(setOf("dept")) { Mono.just(mapOf("dept" to listOf("eng"))) }
        val policy = AbacQueryPolicy(resolver)
        val schema = PrincipalTagSchemaCustomizer(resolver)
            .customize(QuerySchemaCustomizationContext(GATEWAY_TARGET, gatewaySchema()))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                customPolicies = listOf(policy),
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.just(schema)
                }
            )
        )
        val legacy = GatewaySnapshotQueryService<Any>(GATEWAY_TARGET.namedAggregate, gateway)

        gateway.count(CountQueryRequest(GATEWAY_TARGET)).block()
        legacy.count(Condition.ALL).block()

        backend.countPlans.assert().hasSize(2)
        backend.countPlans[0].expressionProvenance[QueryProvenance.MANDATORY_POLICY].assert()
            .isEqualTo(backend.countPlans[1].expressionProvenance[QueryProvenance.MANDATORY_POLICY])
    }

    @Test
    fun `declared tag keys must be finite direct logical children`() {
        assertThrows<IllegalArgumentException> {
            PrincipalTagResolver(emptySet()) { Mono.just(emptyMap()) }
        }
        listOf("", "nested.key", "bad key").forEach { key ->
            assertThrows<IllegalArgumentException> {
                PrincipalTagResolver(setOf(key)) { Mono.just(emptyMap()) }
            }
        }
    }

    private fun policy(tags: Map<String, List<String>>): AbacQueryPolicy =
        AbacQueryPolicy(PrincipalTagResolver(setOf("dept")) { Mono.just(tags) })

    private fun context(target: QueryTarget = snapshotTarget): QueryPolicyContext = QueryPolicyContext(
        target = target,
        operation = QueryOperation.SINGLE,
        normalizedExpression = MatchAll,
        resultShape = QueryPolicyResultShape.Dynamic,
        invocationScope = QueryInvocationScope(
            QueryAuthorityView("subject", "tenant", null, emptySet(), emptySet()),
            me.ahoo.wow.api.query.gateway.RequestedQueryScope(),
            "abac-test"
        ),
        schema = if (target.documentKind == QueryDocumentKind.SNAPSHOT) {
            PrincipalTagSchemaCustomizer(PrincipalTagResolver(setOf("dept", "role")) { Mono.empty() })
                .customize(
                    QuerySchemaCustomizationContext(
                        target,
                        QuerySchema(target, QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT))
                    )
                )
        } else {
            QuerySchema(target, QuerySystemFields.fields(QueryDocumentKind.EVENT_STREAM))
        },
        requestBudget = me.ahoo.wow.api.query.gateway.QueryBudgetHint(),
        frozenInstant = Instant.EPOCH,
        zoneId = ZoneOffset.UTC
    )

    private fun predicate(
        field: String,
        operator: PortableOperator,
        vararg values: QueryValue
    ): PredicateExpression = PredicateExpression(LogicalField(field), operator, values.toList())

    private fun or(vararg expressions: PredicateExpression): PortableLogicalExpression =
        PortableLogicalExpression(LogicalOperator.OR, expressions.toList())

    private companion object {
        val snapshotTarget: QueryTarget = GATEWAY_TARGET
        val eventTarget: QueryTarget = GATEWAY_TARGET.copy(documentKind = QueryDocumentKind.EVENT_STREAM)
    }
}

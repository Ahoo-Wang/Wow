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
package me.ahoo.wow.spring.boot.starter.query

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.annotation.Order
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withBean(ObjectMapper::class.java, { JsonSerializer.rebuild().build() })
        .withConfiguration(queryAutoConfigurations())

    @Test
    fun `auto configuration adds query gateway without replacing legacy query handlers`() {
        contextRunner.run { context ->
            context.assert()
                .hasSingleBean(QueryGateway::class.java)
                .hasSingleBean(QueryAuthorityProvider::class.java)
                .hasSingleBean(QuerySchemaResolver::class.java)
                .hasSingleBean(SnapshotQueryHandler::class.java)
                .hasSingleBean(EventStreamQueryHandler::class.java)
                .doesNotHaveBean(QueryPolicy::class.java)
        }
    }

    @Test
    fun `properties expose explicit production defaults and bind overrides`() {
        QueryGatewayProperties().let { defaults ->
            defaults.maxDepth.assert().isEqualTo(64)
            defaults.maxNodes.assert().isEqualTo(10_000)
            defaults.maxMembershipItems.assert().isEqualTo(10_000)
            defaults.maxNativeParameterBytes.assert().isEqualTo(1_048_576)
            defaults.systemBudget.assert().isEqualTo(QueryGatewaySystemBudgetProperties())
            defaults.enabledCapabilities.assert().isEmpty()
        }

        contextRunner.withPropertyValues(
            "${QueryGatewayProperties.PREFIX}.max-depth=8",
            "${QueryGatewayProperties.PREFIX}.max-nodes=128",
            "${QueryGatewayProperties.PREFIX}.max-membership-items=32",
            "${QueryGatewayProperties.PREFIX}.max-native-parameter-bytes=4096",
            "${QueryGatewayProperties.PREFIX}.system-budget.timeout=5s",
            "${QueryGatewayProperties.PREFIX}.system-budget.max-results=21",
            "${QueryGatewayProperties.PREFIX}.system-budget.max-cost=34",
            "${QueryGatewayProperties.PREFIX}.enabled-capabilities[0]=full-text"
        ).run { context ->
            context.getBean(QueryGatewayProperties::class.java).let { properties ->
                properties.maxDepth.assert().isEqualTo(8)
                properties.maxNodes.assert().isEqualTo(128)
                properties.maxMembershipItems.assert().isEqualTo(32)
                properties.maxNativeParameterBytes.assert().isEqualTo(4096)
                properties.systemBudget.timeout.assert().isEqualTo(Duration.ofSeconds(5))
                properties.systemBudget.maxResults.assert().isEqualTo(21)
                properties.systemBudget.maxCost.assert().isEqualTo(34)
                properties.enabledCapabilities.assert().containsExactly("full-text")
            }
        }
    }

    @Test
    fun `non-positive structure limits fail application startup`() {
        listOf(
            "max-depth=0" to "Maximum query depth must be positive.",
            "max-nodes=-1" to "Maximum query nodes must be positive.",
            "max-membership-items=0" to "Maximum membership items must be positive.",
            "max-native-parameter-bytes=-1" to "Maximum native parameter bytes must be positive."
        ).forEach { (property, message) ->
            contextRunner.withPropertyValues("${QueryGatewayProperties.PREFIX}.$property").run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull(Throwable::message)
                    .any { it.contains(message) }
                    .assert().isTrue()
            }
        }
    }

    @Test
    fun `custom query gateway makes auto configured gateway back off`() {
        val custom = UnavailableQueryGateway

        contextRunner.withBean(QueryGateway::class.java, { custom }).run { context ->
            context.assert().hasSingleBean(QueryGateway::class.java)
            context.getBean(QueryGateway::class.java).assert().isSameAs(custom)
        }
    }

    @Test
    fun `invalid structure limits fail startup when custom query gateway makes runtime bean back off`() {
        listOf(
            "max-depth=0" to "Maximum query depth must be positive.",
            "max-depth=-1" to "Maximum query depth must be positive.",
            "max-nodes=0" to "Maximum query nodes must be positive.",
            "max-nodes=-1" to "Maximum query nodes must be positive.",
            "max-membership-items=0" to "Maximum membership items must be positive.",
            "max-membership-items=-1" to "Maximum membership items must be positive.",
            "max-native-parameter-bytes=0" to "Maximum native parameter bytes must be positive.",
            "max-native-parameter-bytes=-1" to "Maximum native parameter bytes must be positive."
        ).forEach { (property, message) ->
            contextRunner
                .withBean(QueryGateway::class.java, { UnavailableQueryGateway })
                .withPropertyValues("${QueryGatewayProperties.PREFIX}.$property")
                .run { context ->
                    context.startupFailure.assert().isNotNull()
                    generateSequence(context.startupFailure) { it.cause }
                        .mapNotNull(Throwable::message)
                        .any { it.contains(message) }
                        .assert().isTrue()
                }
        }
    }

    @Test
    fun `missing backend fails with stable unavailable error and records metrics`() {
        val registry = SimpleMeterRegistry()
        gatewayContextRunner()
            .withBean(SimpleMeterRegistry::class.java, { registry })
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).let { queryError ->
                            queryError.code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
                            queryError.stage.assert().isEqualTo(QueryStage.BACKEND_RESOLUTION)
                            queryError.reason.assert().isEqualTo(QueryErrorReason.BACKEND_UNAVAILABLE)
                        }
                    }.verify()

                registry.get("wow.query.gateway")
                    .tag("backendId", "unresolved")
                    .tag("errorCode", "BACKEND_NOT_READY")
                    .counter().count().assert().isEqualTo(1.0)
            }
    }

    @Test
    fun `spring policy failure metric uses a normalized bounded bean descriptor`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingBackend()
        val secret = "secret-policy-error"
        val beanName = "Tenant Authorization@Primary" + "X".repeat(100)
        gatewayContextRunner(backend)
            .withBean(SimpleMeterRegistry::class.java, { registry })
            .withBean(beanName, QueryPolicy::class.java, {
                QueryPolicy { Mono.error(IllegalStateException(secret)) }
            })
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).apply {
                            code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                            message.orEmpty().contains(secret).assert().isFalse()
                            message.orEmpty().contains("Tenant Authorization").assert().isFalse()
                            message.orEmpty().contains("IllegalStateException").assert().isFalse()
                        }
                    }.verify()

                registry.get("wow.query.gateway")
                    .tag(
                        "policyDescriptor",
                        "spring-tenant-authorization-primaryxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                    )
                    .tag("outcome", "failure")
                    .counter().count().assert().isEqualTo(1.0)
                backend.countPlans.assert().isEmpty()
            }
    }

    @Test
    fun `normalized spring policy descriptor collisions fail fast`() {
        gatewayContextRunner(RecordingBackend())
            .withBean("Access Policy", QueryPolicy::class.java, {
                QueryPolicy { Mono.just(QueryPolicyResult()) }
            })
            .withBean("Access@Policy", QueryPolicy::class.java, {
                QueryPolicy { Mono.just(QueryPolicyResult()) }
            })
            .run { context ->
                val failure = context.startupFailure
                failure.assert().isNotNull()
                generateSequence(failure) { it.cause }
                    .mapNotNull(Throwable::message)
                    .any { it == "Normalized query policy descriptors must be unique." }
                    .assert().isTrue()
            }
    }

    @Test
    fun `priority ordered spring policy sequence is preserved through registration and execution`() {
        val registry = SimpleMeterRegistry()
        val trace = CopyOnWriteArrayList<String>()
        val backend = RecordingBackend(trace)
        gatewayContextRunner(backend)
            .withBean(SimpleMeterRegistry::class.java, { registry })
            .withBean("priority-lowest", QueryPolicy::class.java, {
                PriorityOrderedQueryPolicy(Ordered.LOWEST_PRECEDENCE, "priority-lowest", trace)
            })
            .withBean("plain-highest", QueryPolicy::class.java, {
                FailingOrderedQueryPolicy(Ordered.HIGHEST_PRECEDENCE, "plain-highest", trace)
            })
            .run { context ->
                val springOrderedPolicies = context.beanFactory.getBeanProvider(QueryPolicy::class.java)
                    .orderedStream()
                    .toList()
                springOrderedPolicies.assert().hasSize(2)
                springOrderedPolicies[0].assert().isSameAs(context.getBean("priority-lowest"))
                springOrderedPolicies[1].assert().isSameAs(context.getBean("plain-highest"))

                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                    }.verify()

                trace.assert().containsExactly("priority-lowest", "plain-highest")
                registry.get("wow.query.gateway")
                    .tag("policyDescriptor", "spring-plain-highest")
                    .tag("outcome", "failure")
                    .counter().count().assert().isEqualTo(1.0)
                backend.countPlans.assert().isEmpty()
            }
    }

    @Test
    fun `prototype policy is materialized once into the ordered deployment snapshot`() {
        val registry = SimpleMeterRegistry()
        val trace = CopyOnWriteArrayList<String>()
        val backend = RecordingBackend(trace)
        val prototypeCreations = AtomicInteger()
        gatewayContextRunner(backend)
            .withBean(SimpleMeterRegistry::class.java, { registry })
            .withBean("priority-lowest", QueryPolicy::class.java, {
                PriorityOrderedQueryPolicy(Ordered.LOWEST_PRECEDENCE, "priority-lowest", trace)
            })
            .withBean(
                "prototype-policy",
                QueryPolicy::class.java,
                {
                    prototypeCreations.incrementAndGet()
                    FailingOrderedQueryPolicy(Ordered.HIGHEST_PRECEDENCE, "prototype-policy", trace)
                },
                { beanDefinition -> beanDefinition.scope = ConfigurableBeanFactory.SCOPE_PROTOTYPE }
            )
            .run { context ->
                context.startupFailure.assert().isNull()
                prototypeCreations.get().assert().isEqualTo(1)

                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                    }.verify()

                prototypeCreations.get().assert().isEqualTo(1)
                trace.assert().containsExactly("priority-lowest", "prototype-policy")
                registry.get("wow.query.gateway")
                    .tag("policyDescriptor", "spring-prototype-policy")
                    .tag("outcome", "failure")
                    .counter().count().assert().isEqualTo(1.0)
                backend.countPlans.assert().isEmpty()
            }
    }

    @Test
    fun `system policy is always applied and anonymous authority is explicit`() {
        listOf(false, true).forEach { registerCustomSystemPolicy ->
            val backend = RecordingBackend()
            val authorities = CopyOnWriteArrayList<QueryAuthorityView>()
            var runner = gatewayContextRunner(backend)
                .withBean(QueryPolicy::class.java, {
                    OrderedQueryPolicy(0, "authority", CopyOnWriteArrayList()) { authority ->
                        authorities += authority
                    }
                })
            if (registerCustomSystemPolicy) {
                runner = runner.withBean("system", QueryPolicy::class.java, {
                    OrderedQueryPolicy(Int.MIN_VALUE, "custom-system", CopyOnWriteArrayList())
                })
            }

            runner.run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectNext(0)
                    .verifyComplete()

                backend.countPlans.single().securedExpression.hasActiveDeletionPredicate().assert().isTrue()
                backend.countPlans.single().effectiveBudget.assert().isEqualTo(QueryBudgetLimit.UNBOUNDED)
                backend.countPlans.single().effectiveDeadline.assert().isNull()
                authorities.single().let { anonymous ->
                    anonymous.subjectId.assert().isNull()
                    anonymous.tenantId.assert().isNull()
                    anonymous.ownerId.assert().isNull()
                    anonymous.spaceIds.assert().isEmpty()
                    anonymous.permissions.assert().isEmpty()
                }
            }
        }
    }

    @Test
    fun `custom policies use spring order and are immutable snapshots`() {
        val trace = CopyOnWriteArrayList<String>()
        val backend = RecordingBackend(trace)
        val frozen = Instant.parse("2026-08-12T08:30:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val observedTimes = CopyOnWriteArrayList<Pair<Instant, ZoneId>>()

        gatewayContextRunner(backend)
            .withBean(Clock::class.java, { Clock.fixed(frozen, ZoneOffset.UTC) })
            .withBean(ZoneId::class.java, { zone })
            .withBean("laterQueryPolicy", QueryPolicy::class.java, {
                LaterAnnotatedQueryPolicy(trace, observedTimes)
            })
            .withBean("earlierQueryPolicy", QueryPolicy::class.java, {
                EarlierAnnotatedQueryPolicy(trace)
            })
            .withBean("zTieFirstQueryPolicy", QueryPolicy::class.java, {
                OrderedQueryPolicy(0, "policy-tie-first", trace)
            })
            .withBean("aTieSecondQueryPolicy", QueryPolicy::class.java, {
                OrderedQueryPolicy(0, "policy-tie-second", trace)
            })
            .withBean("laterResultPolicy", ResultPolicy::class.java, {
                LaterAnnotatedResultPolicy(trace)
            })
            .withBean("earlierResultPolicy", ResultPolicy::class.java, {
                EarlierAnnotatedResultPolicy(trace)
            })
            .run { context ->
                context.beanFactory.registerSingleton(
                    "lateQueryPolicy",
                    OrderedQueryPolicy(Int.MIN_VALUE, "late-policy", trace)
                )
                context.beanFactory.registerSingleton(
                    "lateResultPolicy",
                    OrderedResultPolicy(Int.MIN_VALUE, "late-result", trace)
                )

                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectNext(0)
                    .verifyComplete()

                trace.assert().containsExactly(
                    "policy-earlier",
                    "policy-tie-first",
                    "policy-tie-second",
                    "policy-later",
                    "backend",
                    "result-earlier",
                    "result-later"
                )
                observedTimes.assert().containsExactly(frozen to zone)
            }
    }

    @Test
    fun `factory method order remains part of the authoritative spring policy sequence`() {
        val trace = CopyOnWriteArrayList<String>()
        gatewayContextRunner(RecordingBackend(trace))
            .withBean(FactoryMethodPolicyTrace::class.java, { FactoryMethodPolicyTrace(trace) })
            .withUserConfiguration(FactoryMethodOrderedPolicyConfiguration::class.java)
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectNext(0)
                    .verifyComplete()

                trace.assert().containsExactly("factory-earlier", "factory-later", "backend")
            }
    }

    @Test
    fun `configured system budget reaches the planned backend call`() {
        val backend = RecordingBackend()
        val frozen = Instant.parse("2026-08-12T00:00:00Z")
        gatewayContextRunner(backend)
            .withBean(Clock::class.java, { Clock.fixed(frozen, ZoneOffset.UTC) })
            .withPropertyValues(
                "${QueryGatewayProperties.PREFIX}.system-budget.timeout=5s",
                "${QueryGatewayProperties.PREFIX}.system-budget.max-results=21",
                "${QueryGatewayProperties.PREFIX}.system-budget.max-cost=34"
            )
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(CountQueryRequest(QUERY_TARGET)))
                    .expectNext(0)
                    .verifyComplete()

                backend.countPlans.single().let { plan ->
                    plan.effectiveBudget.assert().isEqualTo(
                        QueryBudgetLimit(Duration.ofSeconds(5), 21, 34)
                    )
                    plan.effectiveDeadline.assert().isEqualTo(frozen.plusSeconds(5))
                }
            }
    }

    @Test
    fun `capabilities require both explicit configuration and a policy grant`() {
        val capability = QueryCapabilityId("full-text")
        val content = LogicalField("state.content")
        val schema = QuerySchema(
            QUERY_TARGET,
            QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
                QueryFieldSchema.string(content, nullable = false).copy(capabilities = setOf(capability))
        )
        val request = CountQueryRequest(
            QUERY_TARGET,
            expression = FullTextExpression(capability, "term", setOf(content))
        )
        val grant = QueryPolicy {
            Mono.just(
                QueryPolicyResult(
                    constraints = QueryPolicyConstraints(
                        capabilityAccess = mapOf(capability to CapabilityDecision.GRANT)
                    )
                )
            )
        }

        gatewayContextRunner(RecordingBackend(capabilities = setOf(capability)), schema)
            .withBean(QueryPolicy::class.java, { grant })
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(request))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
                    }.verify()
            }

        gatewayContextRunner(RecordingBackend(capabilities = setOf(capability)), schema)
            .withPropertyValues("${QueryGatewayProperties.PREFIX}.enabled-capabilities[0]=full-text")
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(request))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).let { queryError ->
                            queryError.code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
                            queryError.reason.assert().isEqualTo(QueryErrorReason.CAPABILITY_DENIED)
                        }
                    }.verify()
            }

        val backend = RecordingBackend(capabilities = setOf(capability))
        gatewayContextRunner(backend, schema)
            .withBean(QueryPolicy::class.java, { grant })
            .withPropertyValues("${QueryGatewayProperties.PREFIX}.enabled-capabilities[0]=full-text")
            .run { context ->
                StepVerifier.create(context.getBean(QueryGateway::class.java).count(request))
                    .expectNext(0)
                    .verifyComplete()
                backend.countPlans.assert().hasSize(1)
            }
    }

    private fun queryAutoConfigurations(): AutoConfigurations {
        val classLoader = QueryGatewayAutoConfigurationTest::class.java.classLoader
        val configurations = ImportCandidates.load(AutoConfiguration::class.java, classLoader)
            .candidates
            .filter { it.startsWith("me.ahoo.wow.spring.boot.starter.query.") }
            .map { Class.forName(it, true, classLoader) }
            .toTypedArray()
        return AutoConfigurations.of(*configurations)
    }

    private fun gatewayContextRunner(
        backend: RecordingBackend? = null,
        schema: QuerySchema = QUERY_SCHEMA
    ): ApplicationContextRunner {
        var runner = contextRunner.withBean(QuerySchemaResolver::class.java, {
            object : QuerySchemaResolver {
                override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.just(schema)
            }
        })
        if (backend != null) {
            runner = runner.withBean(QueryBackendResolver::class.java, {
                QueryBackendResolver {
                    ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("test-route"))
                }
            })
        }
        return runner
    }
}

private val QUERY_TARGET: QueryTarget = QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.SNAPSHOT)
private val QUERY_SCHEMA: QuerySchema = QuerySchema(
    QUERY_TARGET,
    QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT)
)
private val DELETED_FIELD: LogicalField = LogicalField("deleted")

private object UnavailableQueryGateway : QueryGateway {
    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.error(UnsupportedOperationException())

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.error(UnsupportedOperationException())

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> =
        Mono.error(UnsupportedOperationException())

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.error(UnsupportedOperationException())
}

private class OrderedQueryPolicy(
    private val order: Int,
    private val label: String,
    private val trace: MutableList<String>,
    private val observer: (QueryAuthorityView, me.ahoo.wow.query.policy.QueryPolicyContext) -> Unit = { _, _ -> }
) : QueryPolicy,
    Ordered {
    constructor(
        order: Int,
        label: String,
        trace: MutableList<String>,
        authorityObserver: (QueryAuthorityView) -> Unit
    ) : this(order, label, trace, { authority, _ -> authorityObserver(authority) })

    override fun getOrder(): Int = order

    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> = Mono.fromSupplier {
        trace += label
        observer(context.invocationScope.trustedAuthority, context)
        QueryPolicyResult()
    }
}

private class PriorityOrderedQueryPolicy(
    private val order: Int,
    private val label: String,
    private val trace: MutableList<String>
) : QueryPolicy,
    PriorityOrdered {
    override fun getOrder(): Int = order

    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> = Mono.fromSupplier {
        trace += label
        QueryPolicyResult()
    }
}

private class FailingOrderedQueryPolicy(
    private val order: Int,
    private val label: String,
    private val trace: MutableList<String>
) : QueryPolicy,
    Ordered {
    override fun getOrder(): Int = order

    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> = Mono.defer {
        trace += label
        Mono.error(IllegalStateException("policy failed"))
    }
}

private class FactoryMethodPolicyTrace(
    val values: MutableList<String>
)

@Configuration(proxyBeanMethods = false)
private class FactoryMethodOrderedPolicyConfiguration {
    @Bean
    @Order(20)
    fun factoryLaterQueryPolicy(trace: FactoryMethodPolicyTrace): QueryPolicy = QueryPolicy {
        Mono.fromSupplier {
            trace.values += "factory-later"
            QueryPolicyResult()
        }
    }

    @Bean
    @Order(-20)
    fun factoryEarlierQueryPolicy(trace: FactoryMethodPolicyTrace): QueryPolicy = QueryPolicy {
        Mono.fromSupplier {
            trace.values += "factory-earlier"
            QueryPolicyResult()
        }
    }
}

private class OrderedResultPolicy(
    private val order: Int,
    private val label: String,
    private val trace: MutableList<String>
) : ResultPolicy,
    Ordered {
    override fun getOrder(): Int = order

    override fun apply(context: me.ahoo.wow.query.result.ResultPolicyContext, value: Any): Mono<Any> = Mono.fromSupplier {
        trace += label
        value
    }
}

@Order(-20)
private class EarlierAnnotatedQueryPolicy(
    private val trace: MutableList<String>
) : QueryPolicy {
    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> =
        Mono.fromSupplier {
            trace += "policy-earlier"
            QueryPolicyResult()
        }
}

@Order(20)
private class LaterAnnotatedQueryPolicy(
    private val trace: MutableList<String>,
    private val observedTimes: MutableList<Pair<Instant, ZoneId>>
) : QueryPolicy {
    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> =
        Mono.fromSupplier {
            trace += "policy-later"
            observedTimes += context.frozenInstant to context.zoneId
            QueryPolicyResult()
        }
}

@Order(-20)
private class EarlierAnnotatedResultPolicy(
    private val trace: MutableList<String>
) : ResultPolicy {
    override fun apply(context: me.ahoo.wow.query.result.ResultPolicyContext, value: Any): Mono<Any> =
        Mono.fromSupplier {
            trace += "result-earlier"
            value
        }
}

@Order(20)
private class LaterAnnotatedResultPolicy(
    private val trace: MutableList<String>
) : ResultPolicy {
    override fun apply(context: me.ahoo.wow.query.result.ResultPolicyContext, value: Any): Mono<Any> =
        Mono.fromSupplier {
            trace += "result-later"
            value
        }
}

private class RecordingBackend(
    private val trace: MutableList<String>? = null,
    capabilities: Set<QueryCapabilityId> = emptySet()
) : QueryBackend {
    val countPlans: MutableList<CountQueryPlanV1> = CopyOnWriteArrayList()

    override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
        backendId = "recording",
        documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
        planVersions = setOf(QueryPlanVersion.V1),
        portableOperators = PortableOperator.entries.toSet(),
        portableFeatures = QueryPortableFeature.entries.toSet(),
        stringComparisonModes = StringComparisonMode.entries.toSet(),
        capabilities = capabilities,
        maxBudget = QueryBudgetLimit.UNBOUNDED
    )

    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.error(AssertionError("unexpected single"))

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.error(AssertionError("unexpected list"))

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> =
        Mono.error(AssertionError("unexpected page"))

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.fromSupplier {
        countPlans += plan
        trace?.add("backend")
        0L
    }
}

private fun QueryExpression.hasActiveDeletionPredicate(): Boolean = when (this) {
    is PredicateExpression -> field == DELETED_FIELD && operator == PortableOperator.FALSE && values.isEmpty()
    is LogicalExpression -> operands.any(QueryExpression::hasActiveDeletionPredicate)
    is PortableLogicalExpression -> operands.any(QueryExpression::hasActiveDeletionPredicate)
    is ElementMatchExpression -> predicate.hasActiveDeletionPredicate()
    else -> false
}

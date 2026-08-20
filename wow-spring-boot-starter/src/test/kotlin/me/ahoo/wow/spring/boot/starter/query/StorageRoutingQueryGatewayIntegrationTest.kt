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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaCustomizer
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.CanonicalStorageRouteConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class StorageRoutingQueryGatewayIntegrationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withPropertyValues(
            "wow.context-name=order-service",
            "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
            "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
        )
        .withBean(ObjectMapper::class.java, { JsonSerializer.rebuild().build() })
        .withConfiguration(
            AutoConfigurations.of(
                StorageRoutingAutoConfiguration::class.java,
                CanonicalStorageRouteConfiguration::class.java,
                QueryAutoConfiguration::class.java,
                QueryGatewayAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(QueryGatewayRoutingFixture::class.java)

    @Test
    fun `real Jackson schemas and policies flow through canonical storage routes`() {
        contextRunner.run { context ->
            context.assert().hasNotFailed()
            val gateway = context.getBean(QueryGateway::class.java)
            val recording = context.getBean(RoutingBackendRecording::class.java)
            val customization = context.getBean(SchemaCustomizationRecording::class.java)

            StepVerifier.create(gateway.count(snapshotRequest()))
                .expectNext(1)
                .verifyComplete()
            StepVerifier.create(gateway.count(snapshotRequest()))
                .expectNext(1)
                .verifyComplete()
            StepVerifier.create(gateway.count(eventRequest()))
                .expectNext(1)
                .verifyComplete()

            val snapshotContexts = recording.contexts.filter {
                it.target.documentKind == QueryDocumentKind.SNAPSHOT
            }
            snapshotContexts.assert().hasSize(2)
            (snapshotContexts[0].schema === snapshotContexts[1].schema).assert().isTrue()
            customization.bases.getValue(SNAPSHOT_TARGET).let { bases ->
                bases.assert().hasSize(2)
                (bases[0] === bases[1]).assert().isTrue()
            }
            customization.bases.getValue(EVENT_TARGET).assert().hasSize(2)

            val stateId = snapshotContexts.singleSchema().field(STATE_ID)!!
            stateId.projectable.assert().isFalse()
            stateId.bindings.single().let { binding ->
                binding.backendId.assert().isEqualTo(QueryBackendId(RECORDING_BACKEND_ID))
                binding.usage.assert().isEqualTo(QueryFieldUsage.EXACT)
                binding.field.assert().isEqualTo(QueryBackendFieldPath("document.state.id"))
            }
            recording.boundSchemas.zip(recording.contexts).forEach { (boundSchema, resolution) ->
                (boundSchema === resolution.schema).assert().isTrue()
            }
            recording.plans.map(CountQueryPlanV1::routeIdentity).map { it.value }.assert()
                .containsExactly(
                    "mongo-snapshot-store:SNAPSHOT",
                    "mongo-snapshot-store:SNAPSHOT",
                    "mongo-event-store:EVENT_STREAM",
                )
            recording.transportCalls.get().assert().isEqualTo(3)
            context.getBeansOfType(QuerySchemaCustomizer::class.java).values.assert().hasSize(2)

            assertThrows<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (snapshotContexts[0].schema.fields as MutableMap<LogicalField, *>).clear()
            }
        }
    }

    @Test
    fun `policy denial short circuits backend binding and transport`() {
        contextRunner
            .withBean("denyQueryPolicy", QueryPolicy::class.java, {
                QueryPolicy { Mono.error(QueryPolicyDeniedException("TENANT_DENIED")) }
            })
            .run { context ->
                val recording = context.getBean(RoutingBackendRecording::class.java)

                @Suppress("UNCHECKED_CAST")
                val legacyService = context.getBean(
                    "order-service.order.SnapshotQueryService",
                    SnapshotQueryService::class.java
                ) as SnapshotQueryService<Any>
                StepVerifier.create(legacyService.count(Condition.ALL))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).let { queryError ->
                            queryError.code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
                            queryError.stage.assert().isEqualTo(QueryStage.POLICY)
                            queryError.reason.assert().isEqualTo(QueryErrorReason.POLICY_EVALUATION_FAILED)
                        }
                    }.verify()

                recording.contexts.assert().isEmpty()
                recording.transportCalls.get().assert().isZero()
            }
    }

    @Test
    fun `legacy Spring bean and direct gateway share policy and routed backend identity`() {
        val policyCalls = AtomicInteger()
        contextRunner
            .withBean("recordingPolicy", QueryPolicy::class.java, {
                QueryPolicy {
                    policyCalls.incrementAndGet()
                    Mono.just(QueryPolicyResult())
                }
            })
            .run { context ->
                context.assert().hasNotFailed()
                val gateway = context.getBean(QueryGateway::class.java)
                val recording = context.getBean(RoutingBackendRecording::class.java)

                @Suppress("UNCHECKED_CAST")
                val legacyService = context.getBean(
                    "order-service.order.SnapshotQueryService",
                    SnapshotQueryService::class.java
                ) as SnapshotQueryService<Any>

                StepVerifier.create(legacyService.count(Condition.ALL)).expectNext(1).verifyComplete()
                StepVerifier.create(gateway.count(CountQueryRequest(SNAPSHOT_TARGET))).expectNext(1).verifyComplete()

                policyCalls.get().assert().isEqualTo(2)
                recording.contexts.assert().hasSize(2)
                recording.plans.map { it.routeIdentity.value }.assert().containsOnly(
                    "mongo-snapshot-store:SNAPSHOT"
                )
                recording.transportCalls.get().assert().isEqualTo(2)
            }
    }

    @Test
    fun `customizer conflict fails before backend binding`() {
        contextRunner
            .withBean("conflictingCustomizer", QuerySchemaCustomizer::class.java, {
                QuerySchemaCustomizer { customization ->
                    if (customization.target.documentKind != QueryDocumentKind.SNAPSHOT) {
                        return@QuerySchemaCustomizer customization.baseSchema
                    }
                    val field = customization.baseSchema.field(STATE_ID)!!
                    customization.baseSchema.withField(
                        field.copy(
                            bindings = setOf(
                                QueryCapabilityBinding(
                                    QueryBackendId(RECORDING_BACKEND_ID),
                                    QueryFieldUsage.EXACT,
                                    QueryBackendFieldPath("conflicting.state.id"),
                                ),
                            ),
                        ),
                    )
                }
            })
            .run { context ->
                val recording = context.getBean(RoutingBackendRecording::class.java)

                StepVerifier.create(context.getBean(QueryGateway::class.java).count(snapshotRequest()))
                    .expectErrorSatisfies { error ->
                        (error as QueryException).let { queryError ->
                            queryError.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
                            queryError.stage.assert().isEqualTo(QueryStage.VALIDATION)
                            queryError.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
                        }
                    }.verify()

                recording.contexts.assert().isEmpty()
                recording.transportCalls.get().assert().isZero()
            }
    }

    private fun snapshotRequest(): CountQueryRequest = CountQueryRequest(
        SNAPSHOT_TARGET,
        PredicateExpression(
            STATE_ID,
            PortableOperator.EQ,
            listOf(QueryValue.StringValue("order-1")),
        ),
    )

    private fun eventRequest(): CountQueryRequest = CountQueryRequest(
        EVENT_TARGET,
        PredicateExpression(
            LogicalField("body.name"),
            PortableOperator.EQ,
            listOf(QueryValue.StringValue("OrderCreated")),
        ),
    )

    private fun List<QueryBackendResolutionContext>.singleSchema(): QuerySchema = first().schema

    companion object {
        private const val RECORDING_BACKEND_ID = "recording"
        private val AGGREGATE = MaterializedNamedAggregate("order-service", "order")
        private val SNAPSHOT_TARGET = QueryTarget(AGGREGATE, QueryDocumentKind.SNAPSHOT)
        private val EVENT_TARGET = QueryTarget(AGGREGATE, QueryDocumentKind.EVENT_STREAM)
        private val STATE_ID = LogicalField("state.id")
    }
}

@Configuration(proxyBeanMethods = false)
internal class QueryGatewayRoutingFixture {
    @Bean
    fun routingBackendRecording(): RoutingBackendRecording = RoutingBackendRecording()

    @Bean
    fun schemaCustomizationRecording(): SchemaCustomizationRecording = SchemaCustomizationRecording()

    @Bean
    fun mongoEventStoreBinding(): EventStoreBinding = EventStoreBinding.storage(StorageType.MONGO, mockk<EventStore>())

    @Bean
    fun mongoSnapshotStoreBinding(): SnapshotStoreBinding =
        SnapshotStoreBinding.storage(StorageType.MONGO, mockk<SnapshotStore>())

    @Bean
    fun mongoEventQueryBackendBinding(recording: RoutingBackendRecording): QueryBackendBinding =
        QueryBackendBinding.storage(
            StorageType.MONGO,
            QueryDocumentKind.EVENT_STREAM,
            recording.factory,
        )

    @Bean
    fun mongoSnapshotQueryBackendBinding(recording: RoutingBackendRecording): QueryBackendBinding =
        QueryBackendBinding.storage(
            StorageType.MONGO,
            QueryDocumentKind.SNAPSHOT,
            recording.factory,
        )

    @Bean
    @Order(10)
    fun physicalBindingCustomizer(recording: SchemaCustomizationRecording): QuerySchemaCustomizer =
        QuerySchemaCustomizer { customization ->
            recording.record(customization.target, customization.baseSchema)
            if (customization.target.documentKind != QueryDocumentKind.SNAPSHOT) {
                return@QuerySchemaCustomizer customization.baseSchema
            }
            val field = customization.baseSchema.field(LogicalField("state.id"))!!
            customization.baseSchema.withField(
                field.copy(
                    bindings = setOf(
                        QueryCapabilityBinding(
                            QueryBackendId("recording"),
                            QueryFieldUsage.EXACT,
                            QueryBackendFieldPath("document.state.id"),
                        ),
                    ),
                ),
            )
        }

    @Bean
    @Order(20)
    fun resultShapeCustomizer(recording: SchemaCustomizationRecording): QuerySchemaCustomizer =
        QuerySchemaCustomizer { customization ->
            recording.record(customization.target, customization.baseSchema)
            if (customization.target.documentKind != QueryDocumentKind.SNAPSHOT) {
                return@QuerySchemaCustomizer customization.baseSchema
            }
            val field = customization.baseSchema.field(LogicalField("state.id"))!!
            customization.baseSchema.withField(field.copy(projectable = false))
        }
}

internal class SchemaCustomizationRecording {
    val bases: MutableMap<QueryTarget, MutableList<QuerySchema>> = LinkedHashMap()

    fun record(target: QueryTarget, schema: QuerySchema) {
        bases.getOrPut(target) { mutableListOf() } += schema
    }
}

internal class RoutingBackendRecording {
    val contexts: MutableList<QueryBackendResolutionContext> = CopyOnWriteArrayList()
    val boundSchemas: MutableList<QuerySchema> = CopyOnWriteArrayList()
    val plans: MutableList<CountQueryPlanV1> = CopyOnWriteArrayList()
    val transportCalls = AtomicInteger()
    val factory = me.ahoo.wow.query.backend.QueryBackendFactory { context ->
        contexts += context
        RecordingRoutedBackend(context.schema, boundSchemas, plans, transportCalls)
    }
}

private class RecordingRoutedBackend(
    private val boundSchema: QuerySchema,
    private val boundSchemas: MutableList<QuerySchema>,
    private val plans: MutableList<CountQueryPlanV1>,
    private val transportCalls: AtomicInteger,
) : QueryBackend {
    override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
        backendId = "recording",
        documentKinds = QueryDocumentKind.entries.toSet(),
        planVersions = setOf(QueryPlanVersion.V1),
        portableOperators = PortableOperator.entries.toSet(),
        portableFeatures = QueryPortableFeature.entries.toSet(),
        stringComparisonModes = StringComparisonMode.entries.toSet(),
        capabilities = emptySet(),
        maxBudget = QueryBudgetLimit.UNBOUNDED,
    )

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = unused()

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.error(AssertionError("unused"))

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = unused()

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.fromSupplier {
        boundSchemas += boundSchema
        plans += plan
        transportCalls.incrementAndGet()
        1L
    }

    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)

    private fun <T : Any> unused(): Mono<T> = Mono.error(AssertionError("unused"))
}

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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.mask.DataMasking
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.MaskingResultPolicy
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class LegacyGatewayMaskingCompatibilityTest {
    private val metadata = aggregateMetadata<MaskingCompatibilityAggregate, MaskingCompatibilityState>()
    private val snapshot = MaterializedSnapshot(
        contextName = metadata.contextName,
        aggregateName = metadata.aggregateName,
        tenantId = "tenant",
        aggregateId = "aggregate-id",
        version = 1,
        eventId = "event-id",
        firstOperator = "operator",
        operator = "operator",
        firstEventTime = 1,
        eventTime = 2,
        state = MaskingCompatibilityState(ORIGINAL),
        snapshotTime = 3,
        deleted = false,
    )
    private val eventStream = generateEventStream(metadata.aggregateId("aggregate-id"), eventCount = 1)
    private val snapshotDocument = document(snapshot)
    private val eventDocument = document(eventStream)

    @BeforeEach
    fun resetMaskInvocations() {
        MaskingCompatibilityState.maskInvocations.set(0)
    }

    @ParameterizedTest
    @EnumSource(FacadeOperation::class)
    internal fun `typed snapshot facade applies DataMasking once without dynamic masking`(operation: FacadeOperation) {
        val fixture = fixture()
        val dynamicMasker = CountingStateMasker(metadata)
        fixture.stateRegistry.register(dynamicMasker)
        val service = GatewaySnapshotQueryService<MaskingCompatibilityState>(metadata, fixture.gateway)

        StepVerifier.create(typedSnapshot(service, operation))
            .assertNext { result -> result.state.secret.assert().isEqualTo(MASKED) }
            .verifyComplete()

        MaskingCompatibilityState.maskInvocations.get().assert().isOne()
        dynamicMasker.invocations.get().assert().isZero()
        fixture.eventMasker.invocations.get().assert().isZero()
    }

    @ParameterizedTest
    @EnumSource(FacadeOperation::class)
    internal fun `typed event facade never applies legacy masking registries`(operation: FacadeOperation) {
        val fixture = fixture(eventDocument)
        val stateMasker = CountingStateMasker(metadata)
        fixture.stateRegistry.register(stateMasker)
        val service = GatewayEventStreamQueryService(metadata, fixture.gateway)

        StepVerifier.create(typedEvent(service, operation)).expectNext(eventStream).verifyComplete()

        MaskingCompatibilityState.maskInvocations.get().assert().isZero()
        stateMasker.invocations.get().assert().isZero()
        fixture.eventMasker.invocations.get().assert().isZero()
    }

    @ParameterizedTest
    @EnumSource(FacadeOperation::class)
    internal fun `dynamic facade applies only the registry matching its document kind`(operation: FacadeOperation) {
        val snapshotFixture = fixture()
        val stateMasker = CountingStateMasker(metadata)
        snapshotFixture.stateRegistry.register(stateMasker)
        val snapshotService = GatewaySnapshotQueryService<MaskingCompatibilityState>(metadata, snapshotFixture.gateway)

        StepVerifier.create(dynamicSnapshot(snapshotService, operation)).expectNextCount(1).verifyComplete()
        stateMasker.invocations.get().assert().isOne()
        snapshotFixture.eventMasker.invocations.get().assert().isZero()

        val eventFixture = fixture(eventDocument)
        val eventStateMasker = CountingStateMasker(metadata)
        eventFixture.stateRegistry.register(eventStateMasker)
        val eventService = GatewayEventStreamQueryService(metadata, eventFixture.gateway)

        StepVerifier.create(dynamicEvent(eventService, operation)).expectNextCount(1).verifyComplete()
        eventStateMasker.invocations.get().assert().isZero()
        eventFixture.eventMasker.invocations.get().assert().isOne()
    }

    @Test
    fun `masking failure after the first list item is incomplete and cancels the backend`() {
        val fixture = fixture(snapshotDocument, listDocuments = listOf(snapshotDocument, snapshotDocument))
        fixture.stateRegistry.register(FailOnSecondStateMasker(metadata))
        val service = GatewaySnapshotQueryService<MaskingCompatibilityState>(metadata, fixture.gateway)

        StepVerifier.create(service.dynamicList(ListQuery(Condition.ALL)))
            .expectNext(snapshotDocument)
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                    stage.assert().isEqualTo(QueryStage.EXECUTION)
                    reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
                    causeCode.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
                }
            }
            .verify()

        fixture.backend.cancellations.get().assert().isOne()
    }

    private fun fixture(
        document: DynamicDocument = snapshotDocument,
        listDocuments: List<DynamicDocument> = listOf(document),
    ): Fixture {
        val descriptor = QueryBackendDescriptor(
            backendId = "legacy-mask",
            documentKinds = QueryDocumentKind.entries.toSet(),
            planVersions = setOf(QueryPlanVersion.V1),
            portableOperators = PortableOperator.entries.toSet(),
            portableFeatures = QueryPortableFeature.entries.toSet(),
            stringComparisonModes = StringComparisonMode.entries.toSet(),
            capabilities = emptySet(),
            maxBudget = QueryBudgetLimit.UNBOUNDED,
        )
        val backend = RecordingQueryBackend(descriptor)
            .respondSingle(Mono.just(document))
            .respondList(Flux.fromIterable(listDocuments))
            .respondPage(Mono.just(QueryPage(listDocuments, listDocuments.size.toLong(), QueryConsistency.EXACT)))
        val stateRegistry = StateDataMaskerRegistry()
        val eventRegistry = EventStreamMaskerRegistry()
        val eventMasker = CountingEventMasker(metadata)
        eventRegistry.register(eventMasker)
        val gateway = QueryGatewayFactory.create(
            QueryGatewayConfiguration(
                admission = QueryAdmission { context ->
                    Mono.just(
                        QueryInvocationScope(
                            QueryAuthorityView("subject", "tenant", "owner", emptySet(), emptySet()),
                            context.request.requestedScope,
                            context.correlationId,
                        ),
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.just(schema(target))
                },
                backendResolver = { ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("legacy-mask")) },
                customPolicies = emptyList(),
                resultPolicies = listOf(MaskingResultPolicy(stateRegistry, eventRegistry)),
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                zoneId = ZoneOffset.UTC,
                structureLimits = QueryStructureLimits(16, 128, 128, 4096),
                systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
                enabledCapabilities = emptySet(),
                meterRegistry = null,
            ),
        )
        return Fixture(gateway, backend, stateRegistry, eventMasker)
    }

    private fun schema(target: QueryTarget): QuerySchema = QuerySchema(
        target,
        QuerySystemFields.fields(target.documentKind) +
            if (target.documentKind == QueryDocumentKind.SNAPSHOT) {
                listOf(
                    QueryFieldSchema.string(
                        me.ahoo.wow.api.query.expression.LogicalField("state.secret"),
                        nullable = false,
                    ),
                )
            } else {
                emptyList<QueryFieldSchema>()
            },
    )

    private fun typedSnapshot(
        service: GatewaySnapshotQueryService<MaskingCompatibilityState>,
        operation: FacadeOperation,
    ): Publisher<MaterializedSnapshot<MaskingCompatibilityState>> = when (operation) {
        FacadeOperation.SINGLE -> service.single(SingleQuery(Condition.ALL))
        FacadeOperation.LIST -> service.list(ListQuery(Condition.ALL))
        FacadeOperation.PAGE -> service.paged(PagedQuery(Condition.ALL)).flatMapMany { Flux.fromIterable(it.list) }
    }

    private fun dynamicSnapshot(
        service: GatewaySnapshotQueryService<MaskingCompatibilityState>,
        operation: FacadeOperation,
    ): Publisher<DynamicDocument> = when (operation) {
        FacadeOperation.SINGLE -> service.dynamicSingle(SingleQuery(Condition.ALL))
        FacadeOperation.LIST -> service.dynamicList(ListQuery(Condition.ALL))
        FacadeOperation.PAGE -> service.dynamicPaged(
            PagedQuery(Condition.ALL)
        ).flatMapMany { Flux.fromIterable(it.list) }
    }

    private fun typedEvent(
        service: GatewayEventStreamQueryService,
        operation: FacadeOperation,
    ): Publisher<DomainEventStream> = when (operation) {
        FacadeOperation.SINGLE -> service.single(SingleQuery(Condition.ALL))
        FacadeOperation.LIST -> service.list(ListQuery(Condition.ALL))
        FacadeOperation.PAGE -> service.paged(PagedQuery(Condition.ALL)).flatMapMany { Flux.fromIterable(it.list) }
    }

    private fun dynamicEvent(
        service: GatewayEventStreamQueryService,
        operation: FacadeOperation,
    ): Publisher<DynamicDocument> = when (operation) {
        FacadeOperation.SINGLE -> service.dynamicSingle(SingleQuery(Condition.ALL))
        FacadeOperation.LIST -> service.dynamicList(ListQuery(Condition.ALL))
        FacadeOperation.PAGE -> service.dynamicPaged(
            PagedQuery(Condition.ALL)
        ).flatMapMany { Flux.fromIterable(it.list) }
    }

    private fun document(value: Any): DynamicDocument = ImmutableDynamicDocument.copyOf(value.toLinkedHashMap())

    private data class Fixture(
        val gateway: me.ahoo.wow.query.QueryGateway,
        val backend: RecordingQueryBackend,
        val stateRegistry: StateDataMaskerRegistry,
        val eventMasker: CountingEventMasker,
    )

    private class CountingStateMasker(
        override val namedAggregate: NamedAggregate,
    ) : StateDynamicDocumentMasker {
        val invocations: AtomicInteger = AtomicInteger()

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument =
            dynamicDocument.also { invocations.incrementAndGet() }
    }

    private class CountingEventMasker(
        override val namedAggregate: NamedAggregate,
    ) : EventStreamDynamicDocumentMasker {
        val invocations: AtomicInteger = AtomicInteger()

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument =
            dynamicDocument.also { invocations.incrementAndGet() }
    }

    private class FailOnSecondStateMasker(
        override val namedAggregate: NamedAggregate,
    ) : StateDynamicDocumentMasker {
        private val invocations = AtomicInteger()

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            if (invocations.incrementAndGet() == 2) {
                error("mask failed with sensitive-source")
            }
            return dynamicDocument
        }
    }

    internal enum class FacadeOperation {
        SINGLE,
        LIST,
        PAGE,
    }

    private companion object {
        const val ORIGINAL = "original"
        const val MASKED = "masked"
    }
}

@AggregateRoot
internal class MaskingCompatibilityAggregate(val state: MaskingCompatibilityState)

internal data class MaskingCompatibilityState(val secret: String) : DataMasking<MaskingCompatibilityState> {
    override fun mask(): MaskingCompatibilityState {
        maskInvocations.incrementAndGet()
        return copy(secret = "masked")
    }

    companion object {
        val maskInvocations: AtomicInteger = AtomicInteger()
    }
}

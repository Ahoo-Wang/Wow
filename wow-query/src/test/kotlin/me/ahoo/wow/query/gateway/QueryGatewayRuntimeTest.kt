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

package me.ahoo.wow.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SimpleDynamicDocument
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.BackendRecord
import me.ahoo.wow.query.backend.BackendRecordCompleteness
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendStreamSupport
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.RecordQueryBackendNotReady
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.internal.gateway.TrustedAuthorityChannel
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    ExperimentalQueryGatewayApi::class,
)
class QueryGatewayRuntimeTest {
    @Test
    fun `gateway should remain cold and materialize an independent projected document`() {
        val authorityCalls = AtomicInteger()
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val sourceState = linkedMapOf<String, Any?>(
            "status" to "PAID",
            "bytes" to byteArrayOf(1, 2),
            "internal" to "must-not-leak",
        )
        raw.singleResult = SimpleDynamicDocument(
            linkedMapOf(
                "aggregateId" to "order-1",
                "state" to sourceState,
                "backendOnly" to "must-not-leak",
            ),
        )
        val gateway = gateway(raw) {
            authorityCalls.incrementAndGet()
            Mono.just(QueryAuthority.System("test", "gateway-test"))
        }
        val publisher = gateway.single(
            snapshotCall,
            SingleQuery(
                Condition.eq("state.status", "PAID"),
                Projection(include = listOf("state.status", "state.bytes")),
            ),
        )

        authorityCalls.get().assert().isZero()
        raw.singleCalls.get().assert().isZero()

        val first = checkNotNull(publisher.block())
        authorityCalls.get().assert().isEqualTo(1)
        raw.singleCalls.get().assert().isEqualTo(1)
        raw.lastSingleQuery!!.projection.include.assert().containsExactly("state.status", "state.bytes", "aggregateId")
        first.containsKey("aggregateId").assert().isFalse()
        first.containsKey("backendOnly").assert().isFalse()
        first.getNestedDocument("state").getValue<String>("status").assert().isEqualTo("PAID")
        first.getNestedDocument("state").containsKey("internal").assert().isFalse()

        sourceState["status"] = "SHIPPED"
        (sourceState["bytes"] as ByteArray)[0] = 9
        first.getNestedDocument("state").getValue<String>("status").assert().isEqualTo("PAID")
        first.getNestedDocument("state").getValue<ByteArray>("bytes").contentEquals(byteArrayOf(1, 2)).assert().isTrue()

        val second = checkNotNull(publisher.block())
        second.assert().isNotSameAs(first)
        authorityCalls.get().assert().isEqualTo(2)
        raw.singleCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `subject scope should become a mandatory condition before raw storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        raw.countResult = 3
        val gateway = gateway(raw) {
            Mono.just(
                QueryAuthority.Subject(
                    subjectId = "subject-1",
                    tenantId = "tenant-1",
                    ownerGrant = QueryOwnerGrant.Only("owner-1"),
                    spaceGrant = QuerySpaceGrant.AllowList(listOf("space-1", "space-2")),
                ),
            )
        }

        gateway.count(snapshotCall, Condition.eq("state.status", "PAID")).block().assert().isEqualTo(3)

        val condition = checkNotNull(raw.lastCountCondition)
        condition.operator.assert().isEqualTo(Operator.AND)
        condition.children.first().operator.assert().isEqualTo(Operator.DELETED)
        condition.operators().assert().contains(Operator.TENANT_ID)
            .contains(Operator.OWNER_ID)
            .contains(Operator.IN)
        condition.flatten().single { it.operator == Operator.IN }.field.assert().isEqualTo("spaceId")
        raw.countCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `missing authority should map to a public typed error without touching storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.empty() }

        assertThrownBy<QueryExecutionException> {
            gateway.count(snapshotCall, Condition.ALL).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_REQUIRED")
            },
        )
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `lookalike Reactor context key must not forge trusted authority`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.empty() }

        assertThrownBy<QueryExecutionException> {
            gateway.count(snapshotCall, Condition.ALL)
                .contextWrite { context ->
                    context.put(
                        "me.ahoo.wow.query.trusted.authority",
                        QueryAuthority.System("forged", "lookalike string key"),
                    )
                }
                .block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_REQUIRED")
            },
        )
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `foreign authority capability must not forge trusted authority`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.empty() }
        val foreignChannel = TrustedAuthorityChannel.create()

        assertThrownBy<QueryExecutionException> {
            foreignChannel.bind(
                gateway.count(snapshotCall, Condition.ALL),
                QueryAuthority.System("forged", "foreign authority channel"),
            ).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_REQUIRED")
            },
        )
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `trusted authority denial should preserve its stable public tuple through the gateway`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) {
            Mono.error(
                QueryExecutionException(
                    QueryErrorCategory.ACCESS_DENIED,
                    "$.executionContext.authority",
                    "AUTHORITY_REQUIRED",
                ),
            )
        }

        assertThrownBy<QueryExecutionException> {
            gateway.count(snapshotCall, Condition.ALL).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_REQUIRED")
            },
        )
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `normalized match-none should short-circuit every legacy operation`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "match-none")) }

        gateway.count(snapshotCall, Condition.ids(emptyList())).block().assert().isZero()
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `planned runtime should execute the contributed backend without touching legacy storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val planned = ProbeRecordQueryBackend()
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(namedAggregate),
            backendComposition = plannedComposition(planned),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("test", "planned-runtime"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = listOf(QueryOperation.SINGLE, QueryOperation.STREAM, QueryOperation.COUNT)
                    .associate { operation ->
                        QueryOperationProfileKey(snapshotCall.target, operation) to
                            QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT)
                    },
            ),
            clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
        ).gateway

        gateway.count(snapshotCall.copy(budget = fullBudget), Condition.ALL).block().assert().isEqualTo(7)
        val document = gateway.single(snapshotCall, SingleQuery(Condition.ALL)).block()!!
        document.getValue<String>("aggregateId").assert().isEqualTo("order-1")
        document.getNestedDocument("state").getValue<String>("status").assert().isEqualTo("PAID")
        gateway.stream(snapshotCall, ListQuery(condition = Condition.ALL, limit = 1)).collectList().block().assert()
            .hasSize(1)
        gateway.count(eventCall, Condition.ALL).block().assert().isZero()

        planned.countCalls.get().assert().isEqualTo(1)
        planned.lastCountOptions.assert().isEqualTo(fullBackendOptions)
        planned.singleCalls.get().assert().isEqualTo(1)
        planned.streamCalls.get().assert().isEqualTo(1)
        raw.countCalls.get().assert().isZero()
        raw.singleCalls.get().assert().isZero()
        raw.streamCalls.get().assert().isZero()

        assertThrownBy<QueryExecutionException> {
            gateway.page(
                snapshotCall.copy(budget = QueryExecutionBudget(maxPageWindow = 1)),
                PagedQuery(Condition.ALL, pagination = Pagination(2, 1)),
            ).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.BUDGET_EXCEEDED)
                error.path.assert().isEqualTo("$.input.page")
                error.code.assert().isEqualTo("PAGE_WINDOW_EXCEEDED")
            },
        )
        raw.lastPagedQuery.assert().isNull()
    }

    @Test
    fun `shadow runtime should return legacy and compare the planned probe`() {
        val raw = ProbeSnapshotQueryService(namedAggregate).apply { countResult = 7 }
        val planned = ProbeRecordQueryBackend()
        val observations = CopyOnWriteArrayList<QueryShadowObservation>()
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(namedAggregate),
            backendComposition = plannedComposition(planned),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("test", "shadow-runtime"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(snapshotCall.target, QueryOperation.COUNT) to
                        QueryExecutionProfile(QueryExecutionMode.SHADOW, QueryValidationMode.STRICT),
                ),
            ),
            shadowObserver = QueryShadowObserver(observations::add),
            runtimeHealthObserver = QueryRuntimeHealthObserver { },
            clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
        ).gateway

        gateway.count(snapshotCall, Condition.ALL).block().assert().isEqualTo(7)
        observations.single().outcome.assert().isEqualTo(QueryShadowOutcome.MATCH)

        planned.countResult = 9
        gateway.count(snapshotCall, Condition.ALL).block().assert().isEqualTo(7)
        observations.last().outcome.assert().isEqualTo(QueryShadowOutcome.VALUE_MISMATCH)
        observations.assert().hasSize(2)
        raw.countCalls.get().assert().isEqualTo(2)
        planned.countCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `shadow runtime should keep legacy available and report a configured backend that is not ready`() {
        val raw = ProbeSnapshotQueryService(namedAggregate).apply { countResult = 7 }
        val observations = CopyOnWriteArrayList<QueryShadowObservation>()
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(namedAggregate),
            backendComposition = notReadyComposition(),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("test", "shadow-not-ready"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(snapshotCall.target, QueryOperation.COUNT) to
                        QueryExecutionProfile(QueryExecutionMode.SHADOW, QueryValidationMode.STRICT),
                ),
            ),
            shadowObserver = QueryShadowObserver(observations::add),
            runtimeHealthObserver = QueryRuntimeHealthObserver { },
            clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
        ).gateway

        gateway.count(snapshotCall, Condition.ALL).block().assert().isEqualTo(7)

        observations.single().outcome.assert().isEqualTo(QueryShadowOutcome.PROBE_ERROR)
        observations.single().reasonCode.assert().isEqualTo("BACKEND_NOT_READY")
        raw.countCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `compatible fallback should emit a stable descriptor only runtime health observation`() {
        val raw = ProbeSnapshotQueryService(namedAggregate).apply { countResult = 3 }
        val observations = CopyOnWriteArrayList<QueryRuntimeHealthObservation>()
        val gateway = gateway(
            raw,
            runtimeHealthObserver = QueryRuntimeHealthObserver(observations::add),
        ) { Mono.just(QueryAuthority.System("test", "fallback-observation")) }

        gateway.count(snapshotCall, Condition.eq("state.unregistered", "value")).block().assert().isEqualTo(3)

        observations.single().let { observation ->
            observation.target.assert().isEqualTo(snapshotCall.target)
            observation.operation.assert().isEqualTo(QueryOperation.COUNT)
            observation.kind.assert().isEqualTo(QueryRuntimeHealthKind.FALLBACK)
            observation.reasonCode.assert().isEqualTo("FIELD_NOT_FOUND")
        }
    }

    @Test
    fun `planned runtime should reject a configured backend that is not ready at startup`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)

        assertThrownBy<IllegalArgumentException> {
            QueryGatewayRuntime.create(
                namedAggregates = listOf(namedAggregate),
                backendComposition = notReadyComposition(),
                rawServiceSource = object : QueryRawServiceSource {
                    override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                    override fun eventStream(namedAggregate: NamedAggregate) =
                        NoOpEventStreamQueryServiceFactory.create(namedAggregate)
                },
                dialectResolver = QueryLegacyDialectResolver {
                    QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
                },
                authorityResolver = QueryAuthorityResolver {
                    Mono.just(QueryAuthority.System("test", "planned-not-ready"))
                },
                executionProfiles = QueryExecutionProfiles(
                    operationProfiles = mapOf(
                        QueryOperationProfileKey(snapshotCall.target, QueryOperation.COUNT) to
                            QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT),
                    ),
                ),
            )
        }
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `target wide planned profile should reject a partial backend matrix at startup`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        assertThrownBy<IllegalArgumentException> {
            QueryGatewayRuntime.create(
                namedAggregates = listOf(namedAggregate),
                backendComposition = plannedComposition(ProbeRecordQueryBackend()),
                rawServiceSource = object : QueryRawServiceSource {
                    override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                    override fun eventStream(namedAggregate: NamedAggregate) =
                        NoOpEventStreamQueryServiceFactory.create(namedAggregate)
                },
                dialectResolver = QueryLegacyDialectResolver {
                    QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
                },
                authorityResolver = QueryAuthorityResolver {
                    Mono.just(QueryAuthority.System("test", "invalid-planned-profile"))
                },
                executionProfiles = QueryExecutionProfiles(
                    targetProfiles = mapOf(
                        snapshotCall.target to QueryExecutionProfile(
                            QueryExecutionMode.PLANNED,
                            QueryValidationMode.STRICT,
                        ),
                    ),
                ),
            )
        }
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `event stream queries should not receive snapshot deletion semantics`() {
        val snapshot = ProbeSnapshotQueryService(namedAggregate)
        val event = ProbeEventStreamQueryService(namedAggregate)
        event.countResult = 2
        val gateway = gateway(snapshot, event) { Mono.just(QueryAuthority.System("test", "event-test")) }

        gateway.count(eventCall, Condition.ALL).block().assert().isEqualTo(2)

        event.lastCountCondition!!.operator.assert().isEqualTo(Operator.ALL)
        event.countCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `strict page should lower the planner identity tie-breaker`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(
            raw = raw,
            configuration = QueryGatewayConfiguration(
                executionMode = QueryExecutionMode.LEGACY,
                validationMode = QueryValidationMode.STRICT,
            ),
        ) { Mono.just(QueryAuthority.System("test", "strict-page")) }

        gateway.page(
            snapshotCall,
            PagedQuery(condition = Condition.ALL, pagination = Pagination(1, 10)),
        ).block()

        raw.lastPagedQuery!!.sort.assert().hasSize(1)
        raw.lastPagedQuery!!.sort.single().field.assert().isEqualTo("aggregateId")
    }

    @Test
    fun `strict page should resolve the snapshot identity alias without adding a duplicate sort`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(
            raw = raw,
            configuration = QueryGatewayConfiguration(
                executionMode = QueryExecutionMode.LEGACY,
                validationMode = QueryValidationMode.STRICT,
            ),
        ) { Mono.just(QueryAuthority.System("test", "strict-identity-alias")) }

        gateway.page(
            snapshotCall,
            PagedQuery(
                condition = Condition.ALL,
                sort = listOf(Sort("aggregateId", Sort.Direction.DESC)),
                pagination = Pagination(1, 10),
            ),
        ).block()

        raw.lastPagedQuery!!.sort.assert().hasSize(1)
        raw.lastPagedQuery!!.sort.single().assert().isEqualTo(Sort("aggregateId", Sort.Direction.DESC))
    }

    @Test
    fun `typed materializer should be target-bound and run inside the gateway`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        raw.singleResult = SimpleDynamicDocument(
            linkedMapOf(
                "aggregateId" to "order-1",
                "state" to linkedMapOf("status" to "PAID"),
            ),
        )
        val materializer = QueryResultMaterializer(snapshotCall.target, TypedResult::class.java) { identity, document ->
            TypedResult(identity, document.getNestedDocument("state").getValue("status"))
        }
        val gateway = gateway(raw, resultMaterializers = listOf(materializer)) {
            Mono.just(QueryAuthority.System("test", "typed"))
        }

        gateway.single(snapshotCall, SingleQuery(Condition.ALL), TypedResult::class.java).block().assert()
            .isEqualTo(TypedResult("order-1", "PAID"))
    }

    @Test
    fun `typed projection should reject before legacy storage in compatible mode`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val materializer = QueryResultMaterializer(snapshotCall.target, TypedResult::class.java) { identity, document ->
            TypedResult(identity, document.getNestedDocument("state").getValue("status"))
        }
        val gateway = gateway(raw, resultMaterializers = listOf(materializer)) {
            Mono.just(QueryAuthority.System("test", "typed-projection"))
        }

        assertThrownBy<QueryExecutionException> {
            gateway.single(
                snapshotCall,
                SingleQuery(Condition.ALL, Projection(include = listOf("state.status"))),
                TypedResult::class.java,
            ).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.INVALID_QUERY)
                error.path.assert().isEqualTo("$.input.query.projection")
                error.code.assert().isEqualTo("TYPED_PROJECTION_NOT_ALLOWED")
            },
        )
        raw.singleCalls.get().assert().isZero()
    }

    @Test
    fun `legacy include projection should preserve a nullable nested parent`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        raw.singleResult = SimpleDynamicDocument(
            linkedMapOf(
                "aggregateId" to "order-1",
                "profile" to null,
            ),
        )
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "nullable-projection")) }

        val result = gateway.single(
            snapshotCall,
            SingleQuery(Condition.ALL, Projection(include = listOf("profile.name"))),
        ).block()!!

        result.containsKey("profile").assert().isTrue()
        result["profile"].assert().isNull()
    }

    @Test
    fun `missing typed materializer and returned-record budget should fail before storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "fail-closed")) }

        assertThrownBy<QueryExecutionException> {
            gateway.single(snapshotCall, SingleQuery(Condition.ALL), TypedResult::class.java).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.MAPPING_FAILURE)
                error.path.assert().isEqualTo("$.result")
                error.code.assert().isEqualTo("RESULT_MAPPING_FAILED")
            },
        )
        raw.singleCalls.get().assert().isZero()

        assertThrownBy<QueryExecutionException> {
            gateway.stream(
                snapshotCall.copy(budget = QueryExecutionBudget(maxReturnedRecords = 1)),
                ListQuery(condition = Condition.ALL, limit = 2),
            ).collectList().block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.BUDGET_EXCEEDED)
                error.code.assert().isEqualTo("RESULT_LIMIT_EXCEEDED")
            },
        )
        raw.streamCalls.get().assert().isZero()
    }

    @Test
    fun `raw result should be observed once through the bounded snapshot`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val source = linkedMapOf<String, Any?>(
            "aggregateId" to "order-1",
            "state" to linkedMapOf("status" to "PAID"),
        )
        raw.singleResult = GetRejectingDynamicDocument(source)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "single-observation")) }

        gateway.single(snapshotCall, SingleQuery(Condition.ALL)).block()!!.getValue<String>("aggregateId").assert()
            .isEqualTo("order-1")
    }

    @Test
    fun `oversized raw result should fail mapping without escaping the admission budget`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val source = LinkedHashMap<String, Any?>()
        source["aggregateId"] = "order-1"
        repeat(1024) { index -> source["field-$index"] = index }
        raw.singleResult = SimpleDynamicDocument(source)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "bounded-result")) }

        assertThrownBy<QueryExecutionException> {
            gateway.single(snapshotCall, SingleQuery(Condition.ALL)).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.MAPPING_FAILURE)
                error.path.assert().isEqualTo("$.result")
                error.code.assert().isEqualTo("RESULT_MAPPING_FAILED")
            },
        )
    }

    private fun gateway(
        raw: ProbeSnapshotQueryService,
        eventRaw: EventStreamQueryService = NoOpEventStreamQueryServiceFactory.create(namedAggregate),
        configuration: QueryGatewayConfiguration = QueryGatewayConfiguration(),
        resultMaterializers: Iterable<QueryResultMaterializer<*>> = emptyList(),
        runtimeHealthObserver: QueryRuntimeHealthObserver = QueryRuntimeHealthObserver.NONE,
        authority: QueryAuthorityResolver,
    ): QueryGateway = QueryGatewayRuntime.create(
        namedAggregates = listOf(namedAggregate),
        backendComposition = QueryBackendComposition.EMPTY,
        rawServiceSource = object : QueryRawServiceSource {
            override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

            override fun eventStream(namedAggregate: NamedAggregate) = eventRaw
        },
        dialectResolver = QueryLegacyDialectResolver {
            QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
        },
        authorityResolver = authority,
        resultMaterializers = resultMaterializers,
        runtimeHealthObserver = runtimeHealthObserver,
        configuration = configuration,
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
    ).gateway

    private fun plannedComposition(backend: RecordQueryBackend): QueryBackendComposition {
        val identity = QueryFieldId.System(me.ahoo.wow.query.backend.SystemFieldKind.IDENTITY)
        val deleted = QueryFieldId.System(me.ahoo.wow.query.backend.SystemFieldKind.DELETED)
        val schema = QueryDocumentSchema(
            target = snapshotCall.target,
            fields = listOf(
                QueryFieldSchema(
                    id = identity,
                    type = LogicalFieldType.Text,
                    presence = Presence.REQUIRED,
                    nullability = Nullability.NON_NULL,
                    allowedOperators = listOf(PredicateOperator.EQ, PredicateOperator.IN),
                    capabilities = listOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
                    logicalAliases = listOf(QueryFieldId.Path(listOf("aggregateId"))),
                ),
                QueryFieldSchema(
                    id = deleted,
                    type = LogicalFieldType.Boolean,
                    presence = Presence.REQUIRED,
                    nullability = Nullability.NON_NULL,
                    allowedOperators = listOf(PredicateOperator.IS_TRUE, PredicateOperator.IS_FALSE),
                    capabilities = listOf(FieldCapability.EXACT),
                ),
            ),
            searchScopes = emptyList(),
        )
        val backendId = BackendId("probe")
        return QueryBackendComposition(
            contributions = listOf(
                RecordQueryBackendContribution(
                    schema = schema,
                    backendId = backendId,
                    supportedOperations = setOf(QueryOperation.SINGLE, QueryOperation.STREAM, QueryOperation.COUNT),
                    streamSupport = BackendStreamSupport.BOUNDED_ONLY,
                    semanticTiers = setOf(SemanticTier.PORTABLE),
                    fieldCapabilities = mapOf(
                        identity to setOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
                        deleted to setOf(FieldCapability.EXACT),
                    ),
                    backend = backend,
                ),
            ),
            defaultRoutes = mapOf(snapshotCall.target to backendId),
        )
    }

    private fun notReadyComposition(): QueryBackendComposition {
        val ready = plannedComposition(ProbeRecordQueryBackend())
        val contribution = ready.contributions.single()
        return QueryBackendComposition(
            contributions = emptyList(),
            notReadyBackends = listOf(RecordQueryBackendNotReady(contribution.schema, contribution.backendId)),
            defaultRoutes = ready.defaultRoutes,
        )
    }

    private val namedAggregate = MaterializedNamedAggregate("sales", "order")
    private val snapshotCall = QueryCall(
        QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT),
        QueryPurpose("query-test"),
    )
    private val eventCall = QueryCall(
        QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM),
        QueryPurpose("query-test"),
    )
    private val fullBudget = QueryExecutionBudget(
        maxReturnedRecords = 10,
        maxScannedRecords = 100,
        maxPageWindow = 1_000,
        maxCandidateBuckets = 20,
        maxReturnedBuckets = 5,
        maxCursorPages = 3,
        allowDiskUse = true,
    )
    private val fullBackendOptions = QueryBackendExecutionOptions(null, 10, 100, 1_000, 20, 5, 3, true)

    private data class TypedResult(val identity: String, val status: String)

    private class GetRejectingDynamicDocument(
        private val delegate: MutableMap<String, Any?>,
    ) : DynamicDocument,
        MutableMap<String, Any?> by delegate {
        override fun get(key: String): Any? = error("Dynamic result must be traversed once instead of read by key.")

        override fun getNestedDocument(key: String): DynamicDocument = error("Not used by the raw snapshot boundary.")
    }

    private fun Condition.operators(): List<Operator> =
        listOf(operator) + children.flatMap { child -> child.operators() }

    private fun Condition.flatten(): List<Condition> =
        listOf(this) + children.flatMap { child -> child.flatten() }

    private class ProbeSnapshotQueryService(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryService<Any> {
        override val name: String = "probe"
        val singleCalls = AtomicInteger()
        val streamCalls = AtomicInteger()
        val countCalls = AtomicInteger()
        var singleResult: DynamicDocument? = null
        var countResult: Long = 0
        var lastSingleQuery: ISingleQuery? = null
        var lastCountCondition: Condition? = null
        var lastPagedQuery: IPagedQuery? = null

        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> = Mono.empty()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
            singleCalls.incrementAndGet()
            lastSingleQuery = singleQuery
            return Mono.justOrEmpty(singleResult)
        }

        override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<Any>> = Flux.empty()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
            streamCalls.incrementAndGet()
            return Flux.empty()
        }

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> =
            Mono.just(PagedList.empty())

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
            lastPagedQuery = pagedQuery
            return Mono.just(PagedList.empty())
        }

        override fun count(condition: Condition): Mono<Long> {
            countCalls.incrementAndGet()
            lastCountCondition = condition
            return Mono.just(countResult)
        }
    }

    private class ProbeEventStreamQueryService(
        override val namedAggregate: NamedAggregate,
    ) : EventStreamQueryService {
        val countCalls = AtomicInteger()
        var countResult: Long = 0
        var lastCountCondition: Condition? = null

        override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> = Mono.empty()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.empty()

        override fun list(listQuery: IListQuery): Flux<DomainEventStream> = Flux.empty()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.empty()

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> = Mono.just(PagedList.empty())

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
            Mono.just(PagedList.empty())

        override fun count(condition: Condition): Mono<Long> {
            countCalls.incrementAndGet()
            lastCountCondition = condition
            return Mono.just(countResult)
        }
    }

    private class ProbeRecordQueryBackend : RecordQueryBackend {
        val singleCalls = AtomicInteger()
        val streamCalls = AtomicInteger()
        val countCalls = AtomicInteger()
        var countResult: Long = 7
        var lastCountOptions: QueryBackendExecutionOptions? = null

        override fun single(
            plan: BackendSingleQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendRecord> {
            singleCalls.incrementAndGet()
            return Mono.just(record())
        }

        override fun stream(
            plan: BackendStreamQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Flux<BackendRecord> {
            streamCalls.incrementAndGet()
            return Flux.just(record())
        }

        override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long> {
            countCalls.incrementAndGet()
            lastCountOptions = options
            return Mono.just(countResult)
        }

        private fun record(): BackendRecord = BackendRecord(
            identity = "order-1",
            document = NormalizedValue.ObjectValue(
                linkedMapOf(
                    "aggregateId" to NormalizedValue.Text("order-1"),
                    "state" to NormalizedValue.ObjectValue(
                        linkedMapOf("status" to NormalizedValue.Text("PAID")),
                    ),
                ),
            ),
            completeness = BackendRecordCompleteness.COMPLETE,
        )
    }
}

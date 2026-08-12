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

package me.ahoo.wow.query.invocation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyDescriptor
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryStructureLimits
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QuerySubscriptionIsolationTest {
    @Test
    fun `freezes an independent invocation exactly once for each subscription`() {
        val clock = AdvancingClock(Instant.parse("2026-08-12T01:00:00Z"), Duration.ofSeconds(5))
        val correlationSequence = AtomicInteger()
        val request = CountQueryRequest(
            target = queryTarget(),
            budget = QueryBudgetHint(
                timeout = Duration.ofSeconds(20),
                maxResults = 100,
                maxCost = 10
            )
        )
        val factory = QueryInvocationFactory(
            admission = trustedAdmission(),
            clock = clock,
            zoneId = ZoneId.of("Asia/Shanghai"),
            systemBudgetLimit = QueryBudgetLimit(
                timeout = Duration.ofSeconds(10),
                maxResults = 50,
                maxCost = 20
            ),
            deadlineScheduler = VirtualTimeScheduler.create(),
            correlationIdFactory = { "correlation-${correlationSequence.incrementAndGet()}" }
        )
        val invocationPublisher = factory.admit(
            request = request,
            operation = QueryOperation.COUNT
        )

        clock.reads.get().assert().isZero()
        correlationSequence.get().assert().isZero()

        val seeds = mutableListOf<QueryInvocationSeed>()
        StepVerifier.create(invocationPublisher).recordWith { seeds }.expectNextCount(1).verifyComplete()
        StepVerifier.create(invocationPublisher).recordWith { seeds }.expectNextCount(1).verifyComplete()

        val first = seeds[0]
        val second = seeds[1]
        first.scope.assert().isNotSameAs(second.scope)
        first.frozenInstant.assert().isEqualTo(Instant.parse("2026-08-12T01:00:00Z"))
        second.frozenInstant.assert().isEqualTo(Instant.parse("2026-08-12T01:00:05Z"))
        first.admissionDeadline.assert().isEqualTo(Instant.parse("2026-08-12T01:00:10Z"))
        second.admissionDeadline.assert().isEqualTo(Instant.parse("2026-08-12T01:00:15Z"))
        first.scope.correlationId.assert().isEqualTo("correlation-1")
        second.scope.correlationId.assert().isEqualTo("correlation-2")
        first.admissionBudget.assert().isEqualTo(QueryBudgetLimit(Duration.ofSeconds(10), 50, 10))
        first.zoneId.assert().isEqualTo(ZoneId.of("Asia/Shanghai"))
        clock.reads.get().assert().isEqualTo(2)

        val schema = QuerySchema(request.target, emptyList())
        val invocation = first.toInvocation(schema) { it }
        invocation.frozenInstant.assert().isSameAs(first.frozenInstant)
        invocation.admissionDeadline.assert().isSameAs(first.admissionDeadline)
        invocation.scope.assert().isSameAs(first.scope)
        invocation.schema.assert().isSameAs(schema)
        invocation.normalizedExpression.assert().isSameAs(MatchAll)
        invocation.expressionProvenance.assert().isEqualTo(
            mapOf(QueryProvenance.CALLER_REQUEST to MatchAll)
        )
        clock.reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `anchors one monotonic deadline per subscription without rereading wall clock in policy`() {
        val frozen = Instant.parse("2026-08-12T01:10:00Z")
        val wallClock = AdvancingClock(frozen, Duration.ZERO)
        val scheduler = VirtualTimeScheduler.create()
        val cancellationCalls = AtomicInteger()
        val request = CountQueryRequest(
            target = queryTarget(),
            budget = QueryBudgetHint(timeout = Duration.ofSeconds(1))
        )
        val factory = QueryInvocationFactory(
            admission = trustedAdmission(),
            clock = wallClock,
            zoneId = ZoneOffset.UTC,
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            deadlineScheduler = scheduler,
            correlationIdFactory = { "monotonic" }
        )
        val seed = factory.admit(request, QueryOperation.COUNT).block()!!
        val invocation = seed.toInvocation(
            QuerySchema(request.target, QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT))
        ) { it }
        val chain = DefaultQueryPolicyChain(
            systemPolicy = SystemQueryPolicy(QueryBudgetLimit.UNBOUNDED),
            customPolicies = listOf(
                QueryPolicyDescriptor(
                    "never",
                    0,
                    QueryPolicy {
                        Mono.never<QueryPolicyResult>().doOnCancel(cancellationCalls::incrementAndGet)
                    }
                )
            ),
            expressionValidator = QueryExpressionValidator(LIMITS)
        )

        scheduler.advanceTimeBy(Duration.ofMillis(600))
        StepVerifier.withVirtualTime(
            { chain.evaluate(invocation) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .expectNoEvent(Duration.ofMillis(399))
            .thenAwait(Duration.ofMillis(1))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
                    stage.assert().isEqualTo(QueryStage.POLICY)
                    reason.assert().isEqualTo(QueryErrorReason.DEADLINE_REACHED)
                }
            }.verify(Duration.ofSeconds(1))

        wallClock.reads.get().assert().isOne()
        cancellationCalls.get().assert().isOne()
    }

    @Test
    fun `keeps caller and legacy expressions as separate normalized contributions`() {
        val callerPredicate = predicate("state.status", "CREATED")
        val legacyPredicate = predicate("tenantId", "tenant")
        val request = CountQueryRequest(
            target = queryTarget(),
            expression = LogicalExpression(LogicalOperator.AND, listOf(callerPredicate))
        )
        val seed = factory(
            Instant.parse("2026-08-12T01:30:00Z"),
            QueryBudgetLimit.UNBOUNDED
        ).admitLegacy(
            request = request,
            operation = QueryOperation.COUNT,
            legacyExpression = LogicalExpression(LogicalOperator.AND, listOf(legacyPredicate))
        ).block()!!

        seed.expressionContributions.assert().isEqualTo(
            mapOf(
                QueryProvenance.CALLER_REQUEST to request.expression,
                QueryProvenance.LEGACY_ENRICHMENT to
                    LogicalExpression(LogicalOperator.AND, listOf(legacyPredicate))
            )
        )
        seed.expressionContributions.keys.none {
            it == QueryProvenance.TRUSTED_AUTHORITY ||
                it == QueryProvenance.SYSTEM_METADATA ||
                it == QueryProvenance.MANDATORY_POLICY
        }.assert().isTrue()

        val normalizationInputs = mutableListOf<QueryExpression>()
        val invocation = seed.toInvocation(QuerySchema(request.target, emptyList())) { expression ->
            normalizationInputs += expression
            when (expression) {
                request.expression -> callerPredicate
                else -> legacyPredicate
            }
        }

        normalizationInputs.assert().containsExactly(
            request.expression,
            seed.expressionContributions.getValue(QueryProvenance.LEGACY_ENRICHMENT)
        )
        invocation.expressionProvenance.assert().isEqualTo(
            mapOf(
                QueryProvenance.CALLER_REQUEST to callerPredicate,
                QueryProvenance.LEGACY_ENRICHMENT to legacyPredicate
            )
        )
        invocation.normalizedExpression.assert().isEqualTo(
            PortableLogicalExpression(LogicalOperator.AND, listOf(callerPredicate, legacyPredicate))
        )
    }

    @Test
    fun `snapshots and validates a mutable custom schema before creating invocation`() {
        val seed = factory(
            Instant.parse("2026-08-12T01:45:00Z"),
            QueryBudgetLimit.UNBOUNDED
        ).admit(request(), QueryOperation.COUNT).block()!!
        val field = QueryFieldSchema(LogicalField("state.status"), QueryFieldValueKind.STRING, false)
        val mutableFields = linkedMapOf(field.path to field)
        val customView = object : QuerySchemaView {
            override val target: QueryTarget = queryTarget()
            override val fields: Map<LogicalField, QueryFieldSchema> = mutableFields
        }

        val invocation = seed.toInvocation(customView) { it }
        mutableFields.clear()

        invocation.schema.assert().isNotSameAs(customView)
        invocation.schema.fields.keys.assert().containsExactly(field.path)
    }

    @Test
    fun `fails closed when custom schema cardinality changes during snapshot`() {
        val seed = factory(
            Instant.parse("2026-08-12T01:50:00Z"),
            QueryBudgetLimit.UNBOUNDED
        ).admit(request(), QueryOperation.COUNT).block()!!
        val field = QueryFieldSchema(LogicalField("state.status"), QueryFieldValueKind.STRING, false)
        val inconsistentFields = object : Map<LogicalField, QueryFieldSchema> by mapOf(field.path to field) {
            override val size: Int = 2
        }
        val customView = object : QuerySchemaView {
            override val target: QueryTarget = queryTarget()
            override val fields: Map<LogicalField, QueryFieldSchema> = inconsistentFields
        }

        StepVerifier.create(Mono.fromCallable { seed.toInvocation(customView) { it } })
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `factory does not accept caller selected provenance`() {
        QueryInvocationFactory::class.java.declaredMethods
            .flatMap { it.parameterTypes.asList() }
            .none { it == QueryProvenance::class.java }
            .assert().isTrue()
    }

    @Test
    fun `keeps zero timeout immediate and unbounded timeout absent`() {
        val frozen = Instant.parse("2026-08-12T02:00:00Z")
        val zeroTimeoutSeed = factory(
            frozen,
            QueryBudgetLimit(timeout = Duration.ZERO)
        ).admit(request(), QueryOperation.COUNT).block()!!
        val unboundedSeed = factory(
            frozen,
            QueryBudgetLimit.UNBOUNDED
        ).admit(request(), QueryOperation.COUNT).block()!!

        zeroTimeoutSeed.admissionDeadline.assert().isSameAs(zeroTimeoutSeed.frozenInstant)
        unboundedSeed.admissionDeadline.assert().isNull()
    }

    @Test
    fun `rejects finite timeout whose absolute deadline overflows`() {
        val factory = factory(
            Instant.MAX.minusSeconds(1),
            QueryBudgetLimit(timeout = Duration.ofSeconds(2))
        )

        StepVerifier.create(factory.admit(request(), QueryOperation.COUNT))
            .expectErrorSatisfies { error ->
                (error is java.time.DateTimeException).assert().isFalse()
                (error is ArithmeticException).assert().isFalse()
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
                    stage.assert().isEqualTo(QueryStage.ADMISSION)
                    reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
                }
            }
            .verify()
    }

    @Test
    fun `rejects maximum finite duration without saturating or becoming unbounded`() {
        val maximumDuration = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)
        val factory = factory(
            Instant.parse("2026-08-12T02:30:00Z"),
            QueryBudgetLimit(timeout = maximumDuration)
        )

        StepVerifier.create(factory.admit(request(), QueryOperation.COUNT))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
                    stage.assert().isEqualTo(QueryStage.ADMISSION)
                    reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
                }
            }
            .verify()
    }

    @Test
    fun `cancellation during admission does not invoke later resolvers`() {
        val authoritySubscriptions = AtomicInteger()
        val schemaResolverInvocations = AtomicInteger()
        val backendResolverInvocations = AtomicInteger()
        val admission = DefaultQueryAdmission(
            QueryAuthorityProvider {
                Mono.defer {
                    authoritySubscriptions.incrementAndGet()
                    Mono.never()
                }
            }
        )
        val factory = QueryInvocationFactory(
            admission = admission,
            clock = Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            deadlineScheduler = VirtualTimeScheduler.create(),
            correlationIdFactory = { "cancelled" }
        )
        val prepared = factory.admit(request(), QueryOperation.COUNT)
            .flatMap { seed ->
                schemaResolverInvocations.incrementAndGet()
                Mono.just(seed)
            }.flatMap { seed ->
                backendResolverInvocations.incrementAndGet()
                Mono.just(seed)
            }

        StepVerifier.create(prepared).thenCancel().verify()

        authoritySubscriptions.get().assert().isOne()
        schemaResolverInvocations.get().assert().isZero()
        backendResolverInvocations.get().assert().isZero()
    }

    @Test
    fun `fails closed when admission completes empty`() {
        val factory = QueryInvocationFactory(
            admission = QueryAdmission { Mono.empty() },
            clock = Clock.fixed(Instant.parse("2026-08-12T03:30:00Z"), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            deadlineScheduler = VirtualTimeScheduler.create(),
            correlationIdFactory = { "empty-admission" }
        )

        StepVerifier.create(factory.admit(request(), QueryOperation.COUNT))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                    stage.assert().isEqualTo(QueryStage.ADMISSION)
                    reason.assert().isEqualTo(QueryErrorReason.POLICY_EVALUATION_FAILED)
                }
            }
            .verify()
    }

    private fun factory(frozen: Instant, systemBudget: QueryBudgetLimit): QueryInvocationFactory =
        QueryInvocationFactory(
            admission = trustedAdmission(),
            clock = Clock.fixed(frozen, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            systemBudgetLimit = systemBudget,
            deadlineScheduler = VirtualTimeScheduler.create(),
            correlationIdFactory = { "correlation" }
        )

    private fun trustedAdmission(): QueryAdmission = DefaultQueryAdmission(
        QueryAuthorityProvider {
            Mono.just(
                QueryAuthorityView(
                    subjectId = "subject",
                    tenantId = "tenant",
                    ownerId = null,
                    spaceIds = setOf("space"),
                    permissions = setOf("query:read")
                )
            )
        }
    )

    private fun request(): CountQueryRequest = CountQueryRequest(queryTarget())

    private fun predicate(field: String, value: String): PredicateExpression = PredicateExpression(
        field = LogicalField(field),
        operator = PortableOperator.EQ,
        values = listOf(QueryValue.StringValue(value))
    )

    private fun queryTarget(): QueryTarget = QueryTarget(
        namedAggregate = object : NamedAggregate {
            override val contextName: String = "example"
            override val aggregateName: String = "order"
        },
        documentKind = QueryDocumentKind.SNAPSHOT
    )

    private class AdvancingClock(
        initial: Instant,
        private val step: Duration
    ) : Clock() {
        private var current: Instant = initial
        val reads = AtomicInteger()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        @Synchronized
        override fun instant(): Instant = current.also {
            reads.incrementAndGet()
            current = current.plus(step)
        }
    }

    private companion object {
        val LIMITS: QueryStructureLimits = QueryStructureLimits(64, 10_000, 10_000, 1_048_576)
    }
}

class QueryDeadlineGuardTest {
    @Test
    fun `flux deadline remains active after the first item and cancels upstream once`() {
        val scheduler = VirtualTimeScheduler.create()
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val upstream = Flux.defer {
            subscriptions.incrementAndGet()
            Flux.concat(Flux.just("first"), Flux.never<String>())
                .doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.withVirtualTime(
            { guard.enforce(upstream, FROZEN.plusSeconds(1), QueryStage.EXECUTION) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .expectNext("first")
            .thenAwait(Duration.ofSeconds(1))
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.EXECUTION)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `flux source completion cancels its deadline timer and completes immediately`() {
        val scheduler = TrackingScheduler(VirtualTimeScheduler.create())
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)

        StepVerifier.create(
            guard.enforce(Flux.just("value"), FROZEN.plusSeconds(1), QueryStage.EXECUTION)
        ).expectNext("value").verifyComplete()

        scheduler.scheduled.get().assert().isOne()
        scheduler.cancelled.get().assert().isOne()
    }

    @Test
    fun `flux source error cancels its deadline timer and preserves the source error`() {
        val scheduler = TrackingScheduler(VirtualTimeScheduler.create())
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val sourceError = IllegalStateException("source")

        StepVerifier.create(
            guard.enforce(Flux.error<String>(sourceError), FROZEN.plusSeconds(1), QueryStage.EXECUTION)
        ).expectErrorSatisfies { error ->
            (error === sourceError).assert().isTrue()
        }.verify()

        scheduler.scheduled.get().assert().isOne()
        scheduler.cancelled.get().assert().isOne()
    }

    @Test
    fun `flux downstream cancellation disposes deadline timer and upstream once`() {
        val scheduler = TrackingScheduler(VirtualTimeScheduler.create())
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val upstream = Flux.defer {
            subscriptions.incrementAndGet()
            Flux.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(
            guard.enforce(upstream, FROZEN.plusSeconds(1), QueryStage.EXECUTION)
        ).expectSubscription().thenCancel().verify()

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
        scheduler.scheduled.get().assert().isOne()
        scheduler.cancelled.get().assert().isOne()
    }

    @Test
    fun `deadline guard preserves immediate zero and unbounded semantics`() {
        val scheduler = VirtualTimeScheduler.create()
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val boundedSubscriptions = AtomicInteger()
        val unboundedSubscriptions = AtomicInteger()

        StepVerifier.create(
            guard.enforce(
                Mono.defer {
                    boundedSubscriptions.incrementAndGet()
                    Mono.just("bounded")
                },
                FROZEN,
                QueryStage.POLICY
            )
        ).expectErrorSatisfies { error ->
            (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.POLICY)
        }.verify()
        guard.enforce(
            Mono.defer {
                unboundedSubscriptions.incrementAndGet()
                Mono.just("unbounded")
            },
            null,
            QueryStage.POLICY
        ).block().assert().isEqualTo("unbounded")

        boundedSubscriptions.get().assert().isZero()
        unboundedSubscriptions.get().assert().isOne()
    }

    @Test
    fun `deadline guard reuses the same monotonic anchor for later stages`() {
        val scheduler = VirtualTimeScheduler.create()
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val absoluteDeadline = FROZEN.plusSeconds(1)

        scheduler.advanceTimeBy(Duration.ofMillis(700))
        guard.enforce(Mono.just("policy"), absoluteDeadline, QueryStage.POLICY).block()
            .assert().isEqualTo("policy")
        scheduler.advanceTimeBy(Duration.ofMillis(300))
        StepVerifier.create(guard.enforce(Mono.just("plan"), absoluteDeadline, QueryStage.PLANNING))
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.PLANNING)
            }.verify()
    }

    @Test
    fun `deadline guard fails closed when monotonic elapsed arithmetic overflows`() {
        val scheduler = SequencedNowScheduler(Long.MIN_VALUE, Long.MAX_VALUE)
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)

        StepVerifier.create(
            guard.enforce(Mono.just("result"), FROZEN.plusSeconds(1), QueryStage.POLICY)
        ).expectErrorSatisfies { error ->
            (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.POLICY)
        }.verify()
    }

    @Test
    fun `deadline guard fails closed when monotonic ticker regresses`() {
        val scheduler = SequencedNowScheduler(100, 99)
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()

        StepVerifier.create(
            guard.enforce(
                Mono.defer {
                    subscriptions.incrementAndGet()
                    Mono.just("result")
                },
                FROZEN.plusSeconds(1),
                QueryStage.POLICY
            )
        ).expectErrorSatisfies { error ->
            (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.POLICY)
        }.verify()

        subscriptions.get().assert().isZero()
    }

    @Test
    fun `long finite deadline remains bounded instead of overflowing reactor timeout`() {
        val scheduler = WrappingTestScheduler(0)
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val threeHundredYears = Duration.ofDays(109_573)
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(guard.enforce(upstream, FROZEN.plus(threeHundredYears), QueryStage.POLICY))
            .expectSubscription()
            .then {
                scheduler.nextTimerDelayNanos().assert().isEqualTo(Duration.ofDays(365).toNanos())
            }
            .thenCancel()
            .verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `deadline guard keeps one upstream subscription across timer slices and expires at original boundary`() {
        val scheduler = VirtualTimeScheduler.create()
        val guard = QueryDeadlineGuard.anchor(
            frozenInstant = FROZEN,
            scheduler = scheduler,
            maximumTimerSlice = Duration.ofSeconds(1)
        )
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.withVirtualTime(
            { guard.enforce(upstream, FROZEN.plusSeconds(2).plusNanos(1), QueryStage.EXECUTION) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1))
            .then {
                subscriptions.get().assert().isOne()
                cancellations.get().assert().isZero()
            }
            .expectNoEvent(Duration.ofSeconds(1))
            .then {
                subscriptions.get().assert().isOne()
                cancellations.get().assert().isZero()
            }
            .thenAwait(Duration.ofNanos(1))
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.EXECUTION)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `long max plus one deadline survives the first slice and expires one nanosecond later`() {
        val scheduler = WrappingTestScheduler(1)
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val timeout = Duration.ofNanos(Long.MAX_VALUE).plusNanos(1)
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(guard.enforce(upstream, FROZEN.plus(timeout), QueryStage.POLICY))
            .expectSubscription()
            .then {
                scheduler.advanceBy(Long.MAX_VALUE)
                subscriptions.get().assert().isOne()
                cancellations.get().assert().isZero()
            }
            .then { scheduler.advanceBy(1) }
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.POLICY)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `long max deadline expires at its exact monotonic wrap boundary`() {
        val scheduler = WrappingTestScheduler(1)
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val timeout = Duration.ofNanos(Long.MAX_VALUE)
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(guard.enforce(upstream, FROZEN.plus(timeout), QueryStage.EXECUTION))
            .expectSubscription()
            .then { scheduler.advanceBy(Long.MAX_VALUE) }
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.EXECUTION)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `late timers subtract actual local elapsed and expire at the original boundary`() {
        val scheduler = WrappingTestScheduler(1)
        val guard = QueryDeadlineGuard.anchor(
            frozenInstant = FROZEN,
            scheduler = scheduler,
            maximumTimerSlice = Duration.ofSeconds(1)
        )
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val timeout = Duration.ofSeconds(2).plusNanos(2)
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(guard.enforce(upstream, FROZEN.plus(timeout), QueryStage.EXECUTION))
            .expectSubscription()
            .then {
                scheduler.runNextTimer(latenessNanos = 1)
                subscriptions.get().assert().isOne()
                cancellations.get().assert().isZero()
                scheduler.nextTimerDelayNanos().assert().isEqualTo(Duration.ofSeconds(1).toNanos())
            }
            .then { scheduler.runNextTimer(latenessNanos = 1) }
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.EXECUTION)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `segmented deadline remains accurate after cumulative elapsed wraps signed long`() {
        val scheduler = WrappingTestScheduler(1)
        val guard = QueryDeadlineGuard.anchor(
            frozenInstant = FROZEN,
            scheduler = scheduler,
            maximumTimerSlice = Duration.ofSeconds(1)
        )
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val localElapsedNanos = Long.MAX_VALUE / 3
        val localElapsed = Duration.ofNanos(localElapsedNanos)
        val timeout = localElapsed.multipliedBy(4).plusNanos(1)
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(guard.enforce(upstream, FROZEN.plus(timeout), QueryStage.EXECUTION))
            .expectSubscription()
            .then {
                repeat(4) {
                    scheduler.runNextTimer(
                        latenessNanos = localElapsedNanos - Duration.ofSeconds(1).toNanos()
                    )
                    subscriptions.get().assert().isOne()
                    cancellations.get().assert().isZero()
                }
            }
            .then { scheduler.runNextTimer(latenessNanos = 0) }
            .expectErrorSatisfies { error ->
                (error as QueryDeadlineExceededException).stage.assert().isEqualTo(QueryStage.EXECUTION)
            }.verify(Duration.ofSeconds(1))

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
    }

    @Test
    fun `timer slice rejects zero negative and above reactor nanosecond range`() {
        val scheduler = VirtualTimeScheduler.create()
        listOf(
            Duration.ZERO,
            Duration.ofNanos(-1),
            Duration.ofNanos(Long.MAX_VALUE).plusNanos(1)
        ).forEach { invalidSlice ->
            assertThrows<IllegalArgumentException> {
                QueryDeadlineGuard.anchor(FROZEN, scheduler, invalidSlice)
            }
        }

        QueryDeadlineGuard.anchor(FROZEN, scheduler, Duration.ofNanos(Long.MAX_VALUE))
    }

    @Test
    fun `downstream cancellation disposes long deadline timer and upstream once`() {
        val scheduler = TrackingScheduler(VirtualTimeScheduler.create())
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        val subscriptions = AtomicInteger()
        val cancellations = AtomicInteger()
        val upstream = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.never<String>().doOnCancel(cancellations::incrementAndGet)
        }

        StepVerifier.create(
            guard.enforce(upstream, FROZEN.plus(Duration.ofDays(109_573)), QueryStage.POLICY)
        ).expectSubscription()
            .thenCancel()
            .verify()

        subscriptions.get().assert().isOne()
        cancellations.get().assert().isOne()
        scheduler.scheduled.get().assert().isOne()
        scheduler.cancelled.get().assert().isOne()
    }

    private companion object {
        val FROZEN: Instant = Instant.parse("2026-08-12T00:00:00Z")
    }
}

private class SequencedNowScheduler(
    private val firstNanos: Long,
    private val subsequentNanos: Long,
    private val delegate: Scheduler = VirtualTimeScheduler.create()
) : Scheduler by delegate {
    private val reads = AtomicInteger()

    override fun now(unit: TimeUnit): Long {
        if (unit != TimeUnit.NANOSECONDS) {
            return delegate.now(unit)
        }
        return if (reads.getAndIncrement() == 0) firstNanos else subsequentNanos
    }
}

private class TrackingScheduler(
    private val delegate: Scheduler
) : Scheduler by delegate {
    val scheduled = AtomicInteger()
    val cancelled = AtomicInteger()

    override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable {
        scheduled.incrementAndGet()
        val disposable = delegate.schedule(task, delay, unit)
        return object : Disposable {
            override fun dispose() {
                if (!disposable.isDisposed) {
                    cancelled.incrementAndGet()
                }
                disposable.dispose()
            }

            override fun isDisposed(): Boolean = disposable.isDisposed
        }
    }
}

private class WrappingTestScheduler(
    initialNanos: Long
) : Scheduler {
    private var currentNanos: Long = initialNanos
    private val tasks = ArrayDeque<ScheduledTask>()
    private var disposed: Boolean = false

    override fun now(unit: TimeUnit): Long = unit.convert(currentNanos, TimeUnit.NANOSECONDS)

    override fun schedule(task: Runnable): Disposable {
        task.run()
        return object : Disposable {
            override fun dispose() = Unit

            override fun isDisposed(): Boolean = true
        }
    }

    override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable {
        check(!disposed) { "scheduler is disposed." }
        return ScheduledTask(task, unit.toNanos(delay)).also(tasks::addLast)
    }

    fun advanceBy(elapsedNanos: Long) {
        require(elapsedNanos >= 0) { "elapsedNanos cannot be negative." }
        currentNanos += elapsedNanos
        val scheduledBeforeAdvance = tasks.toList()
        tasks.clear()
        val dueTasks = scheduledBeforeAdvance.filter { !it.isDisposed && it.remainingNanos <= elapsedNanos }
        scheduledBeforeAdvance.filterNot(dueTasks::contains).forEach {
            it.remainingNanos -= elapsedNanos
            tasks.addLast(it)
        }
        dueTasks.forEach { it.run() }
    }

    fun runNextTimer(latenessNanos: Long) {
        require(latenessNanos >= 0) { "latenessNanos cannot be negative." }
        val task = tasks.removeFirst()
        currentNanos += task.remainingNanos + latenessNanos
        task.run()
    }

    fun nextTimerDelayNanos(): Long = tasks.first().remainingNanos

    override fun createWorker(): Scheduler.Worker = object : Scheduler.Worker {
        override fun schedule(task: Runnable): Disposable = this@WrappingTestScheduler.schedule(task)

        override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable =
            this@WrappingTestScheduler.schedule(task, delay, unit)

        override fun dispose() = Unit

        override fun isDisposed(): Boolean = disposed
    }

    override fun dispose() {
        disposed = true
        tasks.forEach(ScheduledTask::dispose)
        tasks.clear()
    }

    override fun isDisposed(): Boolean = disposed

    private class ScheduledTask(
        private val task: Runnable,
        var remainingNanos: Long
    ) : Disposable {
        private var disposed: Boolean = false

        fun run() {
            if (!disposed) {
                task.run()
            }
        }

        override fun dispose() {
            disposed = true
        }

        override fun isDisposed(): Boolean = disposed
    }
}

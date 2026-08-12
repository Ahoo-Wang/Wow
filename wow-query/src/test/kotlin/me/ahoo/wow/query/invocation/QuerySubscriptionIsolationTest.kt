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
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
            correlationIdFactory = { "correlation-${correlationSequence.incrementAndGet()}" }
        )
        val invocationPublisher = factory.admit(
            request = request,
            operation = QueryOperation.COUNT,
            entryProvenance = QueryProvenance.CALLER_REQUEST
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
        val invocation = first.toInvocation(schema, MatchAll)
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
    fun `keeps zero timeout immediate and unbounded timeout absent`() {
        val frozen = Instant.parse("2026-08-12T02:00:00Z")
        val zeroTimeoutSeed = factory(
            frozen,
            QueryBudgetLimit(timeout = Duration.ZERO)
        ).admit(request(), QueryOperation.COUNT, QueryProvenance.SYSTEM_METADATA).block()!!
        val unboundedSeed = factory(
            frozen,
            QueryBudgetLimit.UNBOUNDED
        ).admit(request(), QueryOperation.COUNT, QueryProvenance.SYSTEM_METADATA).block()!!

        zeroTimeoutSeed.admissionDeadline.assert().isSameAs(zeroTimeoutSeed.frozenInstant)
        unboundedSeed.admissionDeadline.assert().isNull()
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
            correlationIdFactory = { "cancelled" }
        )
        val prepared = factory.admit(request(), QueryOperation.COUNT, QueryProvenance.CALLER_REQUEST)
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

    private fun factory(frozen: Instant, systemBudget: QueryBudgetLimit): QueryInvocationFactory =
        QueryInvocationFactory(
            admission = trustedAdmission(),
            clock = Clock.fixed(frozen, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            systemBudgetLimit = systemBudget,
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
}

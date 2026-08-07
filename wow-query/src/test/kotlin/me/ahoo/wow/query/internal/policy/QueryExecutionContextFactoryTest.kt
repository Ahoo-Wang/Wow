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

package me.ahoo.wow.query.internal.policy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class QueryExecutionContextFactoryTest {
    private val now = Instant.parse("2026-08-07T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `authority should resolve once per subscription without creation-time capture`() {
        val subscriptions = AtomicInteger()
        val provider = QueryAuthorityProvider {
            Mono.defer {
                Mono.just(
                    QueryAuthority.Subject(
                        subjectId = "subject-${subscriptions.incrementAndGet()}",
                        tenantId = "tenant-1",
                        ownerGrant = QueryOwnerGrant.Unrestricted,
                        spaceGrant = QuerySpaceGrant.Unrestricted,
                    ),
                )
            }
        }
        val context = QueryExecutionContextFactory(provider, clock).resolve(request())

        context.test()
            .consumeNextWith { it.authority.principalId.assert().isEqualTo("subject-1") }
            .verifyComplete()
        context.test()
            .consumeNextWith { it.authority.principalId.assert().isEqualTo("subject-2") }
            .verifyComplete()
        subscriptions.get().assert().isEqualTo(2)
    }

    @Test
    fun `missing and failed authority should reject without fail-open fallback`() {
        assertRejected(QueryRejectionCode.AUTHORITY_REQUIRED, "$.executionContext.authority") {
            QueryExecutionContextFactory(QueryAuthorityProvider { Mono.empty() }, clock)
                .resolve(request())
        }
        val failure = IllegalStateException("identity provider unavailable")
        assertRejected(QueryRejectionCode.AUTHORITY_RESOLUTION_FAILED, "$.executionContext.authority", failure) {
            QueryExecutionContextFactory(QueryAuthorityProvider { Mono.error(failure) }, clock)
                .resolve(request())
        }
        assertRejected(QueryRejectionCode.AUTHORITY_RESOLUTION_FAILED, "$.executionContext.authority", failure) {
            QueryExecutionContextFactory(QueryAuthorityProvider { throw failure }, clock)
                .resolve(request())
        }
        val typedFailure = QueryRejectedException(
            QueryRejection(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT,
                QueryRejectionCode.INVALID_FIELD,
            ),
        )
        assertRejected(
            QueryRejectionCode.AUTHORITY_RESOLUTION_FAILED,
            "$.executionContext.authority",
            typedFailure,
        ) {
            QueryExecutionContextFactory(QueryAuthorityProvider { Mono.error(typedFailure) }, clock)
                .resolve(request())
        }
    }

    @Test
    fun `expired deadline should reject before resolving authority`() {
        val subscriptions = AtomicInteger()
        val provider = QueryAuthorityProvider {
            subscriptions.incrementAndGet()
            Mono.just(QueryAuthority.System("scheduler", "retention-job"))
        }

        assertRejected(
            QueryRejectionCode.DEADLINE_EXPIRED,
            "$.executionContext.deadline",
            category = QueryRejectionCategory.BUDGET_EXCEEDED,
        ) {
            QueryExecutionContextFactory(provider, clock)
                .resolve(request(deadline = now.minusSeconds(1)))
        }
        subscriptions.get().assert().isZero()
    }

    @Test
    fun `authority resolving after deadline should reject without emitting stale context`() {
        val advancingClock = MutableClock(now)
        val provider = QueryAuthorityProvider {
            Mono.fromSupplier {
                advancingClock.current = now.plusSeconds(31)
                subject()
            }
        }

        assertRejected(
            QueryRejectionCode.DEADLINE_EXPIRED,
            "$.executionContext.deadline",
            category = QueryRejectionCategory.BUDGET_EXCEEDED,
        ) {
            QueryExecutionContextFactory(provider, advancingClock).resolve(request())
        }
    }

    @Test
    fun `legacy authority should require an exact trusted grant`() {
        val caller = LegacyQueryCallerId("compensation-retry")
        val grant = LegacyQueryGrant(
            caller,
            PlanningFixtures.target,
            QueryPurpose("interactive-query"),
            QueryResourceScope("tenant-1"),
        )
        val denied = LegacyQueryAuthorityProvider()
        assertRejected(QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED, "$.executionContext.legacyGrant") {
            QueryExecutionContextFactory(denied, clock).resolve(request())
        }

        QueryExecutionContextFactory(LegacyQueryAuthorityProvider(grant), clock)
            .resolve(request())
            .test()
            .consumeNextWith { context -> context.authority.assert().isEqualTo(QueryAuthority.Legacy(grant)) }
            .verifyComplete()
        assertRejected(QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED, "$.executionContext.legacyGrant") {
            QueryExecutionContextFactory(LegacyQueryAuthorityProvider(grant), clock)
                .resolve(request().copy(purpose = QueryPurpose("another-purpose")))
        }
        assertRejected(QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED, "$.executionContext.legacyGrant") {
            QueryExecutionContextFactory(LegacyQueryAuthorityProvider(grant), clock)
                .resolve(request().copy(executionMode = QueryExecutionMode.PLANNED))
        }
        val mismatchedGrant = grant.copy(purpose = QueryPurpose("another-purpose"))
        assertRejected(QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED, "$.executionContext.legacyGrant") {
            QueryExecutionContextFactory(
                QueryAuthorityProvider { Mono.just(QueryAuthority.Legacy(mismatchedGrant)) },
                clock,
            ).resolve(request())
        }
    }

    @Test
    fun `authority collections should be defensive copies`() {
        val spaces = linkedSetOf("space-1")
        val authority = QueryAuthority.Subject(
            "subject-1",
            "tenant-1",
            QueryOwnerGrant.Unrestricted,
            QuerySpaceGrant.AllowList(spaces),
        )
        spaces += "space-2"

        (authority.spaceGrant as QuerySpaceGrant.AllowList).spaceIds.assert().containsExactly("space-1")
    }

    private fun request(
        deadline: Instant? = now.plusSeconds(30),
    ): QueryExecutionRequest = QueryExecutionRequest(
        target = PlanningFixtures.target,
        purpose = QueryPurpose("interactive-query"),
        executionMode = QueryExecutionMode.LEGACY,
        validationMode = QueryValidationMode.COMPATIBLE,
        resourceScope = QueryResourceScope(tenantId = "tenant-1"),
        deadline = deadline,
        budget = QueryExecutionBudget(maxReturnedRecords = 100),
    )

    private fun subject(): QueryAuthority.Subject = QueryAuthority.Subject(
        "subject-1",
        "tenant-1",
        QueryOwnerGrant.Unrestricted,
        QuerySpaceGrant.Unrestricted,
    )

    private fun assertRejected(
        code: QueryRejectionCode,
        path: String,
        cause: Throwable? = null,
        category: QueryRejectionCategory = QueryRejectionCategory.ACCESS_DENIED,
        publisher: () -> Mono<*>,
    ) {
        publisher().test()
            .consumeErrorWith { error ->
                (error as QueryRejectedException).rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
                if (cause != null) {
                    error.cause.assert().isSameAs(cause)
                }
            }
            .verify()
    }

    private class MutableClock(
        var current: Instant,
        private val zoneId: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current
    }
}

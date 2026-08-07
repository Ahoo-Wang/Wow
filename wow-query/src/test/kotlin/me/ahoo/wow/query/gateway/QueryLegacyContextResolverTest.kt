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

@file:OptIn(ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class QueryLegacyContextResolverTest {
    private val target = QueryTarget(
        MaterializedNamedAggregate("example", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val grant = QueryLegacyGrant(
        callerId = "compensation-retry",
        target = target,
        purpose = QueryPurpose("compensation-retry"),
        executionMode = QueryExecutionMode.LEGACY,
        resourceScope = QueryResourceScope(tenantId = "tenant-1"),
    )
    private val resolver = QueryLegacyContextResolver(listOf(grant))

    @Test
    fun `should resolve an exact pre-registered legacy grant`() {
        Mono.defer { resolver.resolve(request()) }
            .withLegacyQueryCaller("compensation-retry")
            .test()
            .consumeNextWith { context ->
                context.call.target.assert().isEqualTo(target)
                context.call.purpose.assert().isEqualTo(grant.purpose)
                context.call.resourceScope.assert().isEqualTo(grant.resourceScope)
                context.authority.assert().isEqualTo(QueryAuthority.Legacy(grant))
            }
            .verifyComplete()
    }

    @Test
    fun `should reject target and execution mode mismatch`() {
        val anotherTarget = QueryTarget(
            MaterializedNamedAggregate("example", "cart"),
            QueryDocumentKind.SNAPSHOT,
        )
        resolver.resolve(request(anotherTarget))
            .withLegacyQueryCaller("compensation-retry")
            .test()
            .expectErrorSatisfies(::assertLegacyRejected)
            .verify()

        resolver.resolve(request(executionMode = QueryExecutionMode.SHADOW))
            .withLegacyQueryCaller("compensation-retry")
            .test()
            .expectErrorSatisfies(::assertLegacyRejected)
            .verify()
    }

    @Test
    fun `should not grant legacy authority without its trusted context marker`() {
        resolver.resolve(request())
            .test()
            .verifyComplete()
    }

    @Test
    fun `should reject ambiguous grants`() {
        runCatching { QueryLegacyContextResolver(listOf(grant, grant.copy(purpose = QueryPurpose("other")))) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun assertLegacyRejected(error: Throwable) {
        error.assert().isInstanceOf(QueryExecutionException::class.java)
        (error as QueryExecutionException).category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
        error.path.assert().isEqualTo("$.executionContext.legacyGrant")
        error.code.assert().isEqualTo("LEGACY_CALLER_NOT_ALLOWED")
    }

    private fun request(
        target: QueryTarget = this.target,
        executionMode: QueryExecutionMode = QueryExecutionMode.LEGACY,
    ): QueryTrustedContextRequest = QueryTrustedContextRequest(
        QueryCallResolutionRequest(target, QueryType.COUNT),
        executionMode,
        QueryValidationMode.COMPATIBLE,
    )
}

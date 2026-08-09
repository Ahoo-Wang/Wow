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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.query.analytics.AnalyticsQueryTrustedContextRequest
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gateway.CompositeQueryTrustedContextResolver
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryCallResolutionRequest
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryLegacyContextResolver
import me.ahoo.wow.query.gateway.QueryLegacyGrant
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryResourceScope
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContext
import me.ahoo.wow.query.gateway.QueryTrustedContextRequest
import me.ahoo.wow.query.gateway.QueryValidationMode
import me.ahoo.wow.query.gateway.withLegacyQueryCaller
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicReference

class QueryWebTransportTest {
    private val target = QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.SNAPSHOT)

    @Test
    fun `should resolve exact call and authenticated authority from one frozen marker`() {
        val authorityRequest = AtomicReference<QueryWebAuthorityRequest>()
        val resolvers = QueryWebTransportResolvers { request ->
            authorityRequest.set(request)
            Mono.just(QueryAuthority.Subject("subject-1", "tenant-1"))
        }
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-1")
            .pathVariable(MessageRecords.OWNER_ID, "owner-1")
            .header(CommonComponent.Header.SPACE_ID, "space-1")
            .build()

        Mono.defer {
            resolvers.resolve(trustedRequest(QueryType.DYNAMIC_SINGLE))
                .map { context -> context.call to context.authority }
        }.writeQueryWebTransport(
            request,
            MOCK_AGGREGATE_METADATA,
            QueryDocumentKind.SNAPSHOT,
            QueryType.DYNAMIC_SINGLE,
        ).test()
            .consumeNextWith { (call, authority) ->
                call.target.assert().isEqualTo(target)
                call.purpose.value.assert().isEqualTo("interactive-query")
                call.resourceScope.tenantId.assert().isEqualTo("tenant-1")
                call.resourceScope.ownerId.assert().isEqualTo("owner-1")
                call.resourceScope.spaceId.assert().isEqualTo("space-1")
                authority.assert().isEqualTo(QueryAuthority.Subject("subject-1", "tenant-1"))
                authorityRequest.get().request.assert().isSameAs(request)
                authorityRequest.get().call.assert().isEqualTo(call)
            }
            .verifyComplete()
    }

    @Test
    fun `should resolve analytics authority from a dedicated marker without QueryType`() {
        val authorityRequest = AtomicReference<QueryWebAuthorityRequest>()
        val resolvers = QueryWebTransportResolvers { request ->
            authorityRequest.set(request)
            Mono.just(QueryAuthority.Subject("subject-1", "tenant-1"))
        }
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-1")
            .pathVariable(MessageRecords.OWNER_ID, "owner-1")
            .header(CommonComponent.Header.SPACE_ID, "space-1")
            .build()

        Mono.defer {
            resolvers.resolve(
                AnalyticsQueryTrustedContextRequest(
                    target,
                    QueryExecutionMode.PLANNED,
                    QueryValidationMode.STRICT,
                ),
            )
        }.writeAnalyticsQueryWebTransport(request, MOCK_AGGREGATE_METADATA)
            .test()
            .consumeNextWith { context ->
                context.call.target.assert().isEqualTo(target)
                context.call.resourceScope.assert().isEqualTo(
                    QueryResourceScope("tenant-1", "owner-1", "space-1"),
                )
                context.authority.assert().isEqualTo(QueryAuthority.Subject("subject-1", "tenant-1"))
                authorityRequest.get().request.assert().isSameAs(request)
                authorityRequest.get().call.assert().isEqualTo(context.call)
            }
            .verifyComplete()
    }

    @Test
    fun `should reject a marker for another query operation`() {
        val resolvers = QueryWebTransportResolvers { Mono.empty() }
        val request = MockServerRequest.builder().build()

        resolvers.resolve(trustedRequest(QueryType.COUNT))
            .writeQueryWebTransport(
                request,
                MOCK_AGGREGATE_METADATA,
                QueryDocumentKind.SNAPSHOT,
                QueryType.DYNAMIC_SINGLE,
            )
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).category.name.assert().isEqualTo("ACCESS_DENIED")
                error.path.assert().isEqualTo("$.executionContext.transport")
                error.code.assert().isEqualTo("QUERY_TRANSPORT_CALL_MISMATCH")
            }
            .verify()
    }

    @Test
    fun `should not fabricate call or authority without a transport marker`() {
        val resolvers = QueryWebTransportResolvers { Mono.error(AssertionError("must not resolve")) }

        resolvers.resolve(trustedRequest(QueryType.COUNT))
            .test()
            .verifyComplete()
    }

    @Test
    fun `web marker with missing authority must not fall through to a legacy grant`() {
        val webResolvers = QueryWebTransportResolvers { Mono.empty() }
        val legacyResolvers = QueryLegacyContextResolver(
            listOf(
                QueryLegacyGrant(
                    callerId = "legacy-caller",
                    target = target,
                    purpose = QueryPurpose("legacy-purpose"),
                    executionMode = QueryExecutionMode.LEGACY,
                    resourceScope = QueryResourceScope(),
                ),
            ),
        )
        val composite = CompositeQueryTrustedContextResolver(listOf(webResolvers, legacyResolvers))
        val request = MockServerRequest.builder().build()

        Mono.defer { composite.resolve(trustedRequest(QueryType.DYNAMIC_SINGLE)) }
            .withLegacyQueryCaller("legacy-caller")
            .writeQueryWebTransport(
                request,
                MOCK_AGGREGATE_METADATA,
                QueryDocumentKind.SNAPSHOT,
                QueryType.DYNAMIC_SINGLE,
            )
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).code.assert().isEqualTo("AUTHORITY_REQUIRED")
                error.path.assert().isEqualTo("$.executionContext.authority")
            }
            .verify()
    }

    @Test
    fun `should normalize authority resolver failure`() {
        val failure = IllegalStateException("authentication store unavailable")
        val resolvers = QueryWebTransportResolvers { Mono.error(failure) }

        resolveWithMarker(resolvers)
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_RESOLUTION_FAILED")
                error.cause.assert().isSameAs(failure)
            }
            .verify()
    }

    @Test
    fun `should not trust authority resolver query rejection`() {
        val failure = QueryExecutionException(
            QueryErrorCategory.INVALID_QUERY,
            "$.forged",
            "FORGED_QUERY_REJECTION",
        )
        val resolvers = QueryWebTransportResolvers { Mono.error(failure) }

        resolveWithMarker(resolvers)
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_RESOLUTION_FAILED")
                error.cause.assert().isSameAs(failure)
            }
            .verify()
    }

    private fun resolveWithMarker(resolvers: QueryWebTransportResolvers): Mono<QueryTrustedContext> =
        Mono.defer { resolvers.resolve(trustedRequest(QueryType.DYNAMIC_SINGLE)) }
            .writeQueryWebTransport(
                MockServerRequest.builder().build(),
                MOCK_AGGREGATE_METADATA,
                QueryDocumentKind.SNAPSHOT,
                QueryType.DYNAMIC_SINGLE,
            )

    private fun trustedRequest(queryType: QueryType): QueryTrustedContextRequest = QueryTrustedContextRequest(
        QueryCallResolutionRequest(target, queryType),
        QueryExecutionMode.LEGACY,
        QueryValidationMode.COMPATIBLE,
    )
}

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

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.invocation.QueryAdmissionContext
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.RouteTestFixtures
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.security.Principal

class WebFluxQueryAdmissionTest {

    @Test
    fun `default resolver trusts authenticated principal subject only`() {
        val admission = admission(WebFluxQueryAuthorityResolver.SUBJECT)
        val request = MockServerRequest.builder()
            .principal(Principal { "subject" })
            .header("Wow-Tenant-Id", "forged-header-tenant")
            .header("CoSec-Space-Id", "forged-header-space")
            .pathVariable(MessageRecords.TENANT_ID, "forged-route-tenant")
            .build()

        admission.bind(request, admission.admit(context()))
            .test()
            .consumeNextWith { scope ->
                scope.trustedAuthority.subjectId.assert().isEqualTo("subject")
                scope.trustedAuthority.tenantId.assert().isNull()
                scope.trustedAuthority.ownerId.assert().isNull()
                scope.trustedAuthority.spaceIds.assert().isEmpty()
                scope.trustedAuthority.permissions.assert().isEmpty()
            }
            .verifyComplete()
    }

    @Test
    fun `verified resolver may project trusted claims from authenticated principal`() {
        val resolver = WebFluxQueryAuthorityResolver { principal ->
            principal.name.assert().isEqualTo("verified-subject")
            Mono.just(QueryAuthorityView(principal.name, "verified-tenant", null, setOf("space"), emptySet()))
        }
        val admission = admission(resolver)
        val request = MockServerRequest.builder().principal(Principal { "verified-subject" }).build()

        admission.bind(request, admission.admit(context()))
            .test()
            .consumeNextWith { scope ->
                scope.trustedAuthority.tenantId.assert().isEqualTo("verified-tenant")
                scope.trustedAuthority.spaceIds.assert().containsExactly("space")
            }
            .verifyComplete()
    }

    @Test
    fun `anonymous web request never falls back to non-web authority`() {
        val fallback = QueryAuthorityProvider {
            Mono.just(QueryAuthorityView("fallback", "fallback-tenant", null, emptySet(), emptySet()))
        }
        val admission = WebFluxQueryAdmission(WebFluxQueryAuthorityResolver.SUBJECT, fallback)

        admission.bind(MockServerRequest.builder().build(), admission.admit(context()))
            .test()
            .consumeNextWith { scope ->
                scope.trustedAuthority.assert().isEqualTo(ANONYMOUS_AUTHORITY)
            }
            .verifyComplete()
    }

    @Test
    fun `non-web invocation delegates to configured authority provider`() {
        val fallback = QueryAuthorityView("fallback", "tenant", "owner", setOf("space"), setOf("read"))
        val admission = WebFluxQueryAdmission(
            WebFluxQueryAuthorityResolver.SUBJECT,
            QueryAuthorityProvider { Mono.just(fallback) }
        )

        admission.admit(context())
            .test()
            .consumeNextWith { scope -> scope.trustedAuthority.assert().isSameAs(fallback) }
            .verifyComplete()
    }

    private fun admission(resolver: WebFluxQueryAuthorityResolver): WebFluxQueryAdmission = WebFluxQueryAdmission(
        resolver,
        QueryAuthorityProvider { Mono.just(ANONYMOUS_AUTHORITY) }
    )

    private fun context(): QueryAdmissionContext = QueryAdmissionContext(
        request = CountQueryRequest(TARGET, MatchAll),
        operation = QueryOperation.COUNT,
        entryProvenances = setOf(QueryProvenance.CALLER_REQUEST),
        correlationId = "correlation"
    )

    private companion object {
        val TARGET = QueryTarget(
            RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA.aggregateMetadata,
            QueryDocumentKind.SNAPSHOT
        )
        val ANONYMOUS_AUTHORITY = QueryAuthorityView(null, null, null, emptySet(), emptySet())
    }
}

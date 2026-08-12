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
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DefaultQueryAdmissionTest {
    @Test
    fun `uses only the server authority and preserves caller scope as requested`() {
        val request = CountQueryRequest(
            target = queryTarget(),
            requestedScope = RequestedQueryScope(
                tenantId = "caller-tenant",
                ownerId = "caller-owner",
                spaceId = "caller-space"
            )
        )
        val trusted = QueryAuthorityView(
            subjectId = "trusted-subject",
            tenantId = "trusted-tenant",
            ownerId = "trusted-owner",
            spaceIds = setOf("trusted-space"),
            permissions = setOf("query:read")
        )
        var observedContext: QueryAdmissionContext? = null
        val admission = DefaultQueryAdmission(
            QueryAuthorityProvider { context ->
                observedContext = context
                Mono.just(trusted)
            }
        )
        val context = QueryAdmissionContext(
            request = request,
            operation = QueryOperation.COUNT,
            entryProvenance = QueryProvenance.CALLER_REQUEST,
            correlationId = "correlation-1"
        )

        StepVerifier.create(admission.admit(context))
            .assertNext { scope ->
                scope.trustedAuthority.assert().isSameAs(trusted)
                scope.requestedScope.assert().isSameAs(request.requestedScope)
                scope.correlationId.assert().isEqualTo("correlation-1")
                scope.trustedAuthority.tenantId.assert().isEqualTo("trusted-tenant")
                scope.trustedAuthority.spaceIds.assert().containsExactly("trusted-space")
            }
            .verifyComplete()

        observedContext.assert().isSameAs(context)
    }

    @Test
    fun `authority collections are defensive immutable snapshots`() {
        val spaces = linkedSetOf("space-1")
        val permissions = linkedSetOf("query:read")
        val authority = QueryAuthorityView(
            subjectId = "subject",
            tenantId = "tenant",
            ownerId = "owner",
            spaceIds = spaces,
            permissions = permissions
        )

        spaces += "space-2"
        permissions += "query:write"

        authority.spaceIds.assert().containsExactly("space-1")
        authority.permissions.assert().containsExactly("query:read")
        StepVerifier.create(
            Mono.fromCallable {
                @Suppress("UNCHECKED_CAST")
                (authority.spaceIds as MutableSet<String>).add("space-3")
            }
        ).expectError(UnsupportedOperationException::class.java).verify()
    }

    private fun queryTarget(): QueryTarget = QueryTarget(
        namedAggregate = object : NamedAggregate {
            override val contextName: String = "example"
            override val aggregateName: String = "order"
        },
        documentKind = QueryDocumentKind.SNAPSHOT
    )
}

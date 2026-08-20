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
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class QueryInvocationScopeTest {
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

    @Test
    fun `scope authority correlation and native values stay redacted`() {
        val secret = "secret-sentinel-924713"
        val request = CountQueryRequest(
            target = queryTarget(),
            expression = NativeExpression(
                capabilityId = QueryCapabilityId("native"),
                backendId = "backend",
                templateId = "template",
                parameters = mapOf("secret" to QueryValue.StringValue(secret)),
                declaredFields = setOf(LogicalField("state.secret"))
            ),
            requestedScope = RequestedQueryScope(
                tenantId = "tenant-$secret",
                ownerId = "owner-$secret",
                spaceId = "space-$secret"
            )
        )
        val authority = QueryAuthorityView(
            subjectId = "subject-$secret",
            tenantId = "trusted-$secret",
            ownerId = "trusted-owner-$secret",
            spaceIds = setOf("trusted-space-$secret"),
            permissions = setOf("permission-$secret")
        )
        val context = QueryAdmissionContext(
            request,
            QueryOperation.COUNT,
            setOf(QueryProvenance.CALLER_REQUEST),
            "correlation-$secret"
        )
        val scope = QueryInvocationScope(authority, request.requestedScope, "correlation-$secret")

        listOf(context.toString(), scope.toString(), authority.toString()).forEach { rendered ->
            rendered.contains(secret).assert().isFalse()
        }
        context.toString().assert().isEqualTo(
            "QueryAdmissionContext(operation=COUNT, entryProvenances=[CALLER_REQUEST], request=<redacted>, " +
                "correlationId=<redacted>)"
        )
        scope.toString().assert().isEqualTo(
            "QueryInvocationScope(trustedAuthority=<redacted>, requestedScope=<redacted>, correlationId=<redacted>)"
        )
        authority.toString().assert().isEqualTo("QueryAuthorityView(<redacted>)")
    }

    private fun queryTarget(): QueryTarget = QueryTarget(
        namedAggregate = object : NamedAggregate {
            override val contextName: String = "example"
            override val aggregateName: String = "order"
        },
        documentKind = QueryDocumentKind.SNAPSHOT
    )
}

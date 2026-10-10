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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class CompositeQueryRequestScopeTest {
    private fun app(appId: String): FilterExpression = filter { "state.appId" eq appId }

    private val appScope = ScopeContributor { _, request ->
        request.headers().firstHeader("App-Id")?.let { QueryScope(declared = app(it)) } ?: QueryScope.NONE
    }
    private val authenticatedTenant = ScopeContributor { _, _ -> QueryScope(authenticated = TenantIdFilter("t0")) }

    @Test
    fun `without contributors the host's scope is used as it is`() {
        CompositeQueryRequestScope.of(DefaultQueryRequestScope, emptyList())
            .assert().isSameAs(DefaultQueryRequestScope)
    }

    @Test
    fun `a contributor adds its dimension to the host's scope, keeping each provenance`() {
        val scope = CompositeQueryRequestScope.of(DefaultQueryRequestScope, listOf(appScope, authenticatedTenant))
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "t1")
            .header(CommandHeaders.OWNER_ID, "alice")
            .header("App-Id", "console")
            .build()
        scope.resolve(MOCK_AGGREGATE_METADATA, request).assert().isEqualTo(
            QueryScope(
                authenticated = TenantIdFilter("t0"),
                declared = TenantIdFilter("t1").appendFilter(OwnerIdFilter("alice")).appendFilter(app("console")),
            )
        )
    }

    @Test
    fun `a contributor with nothing to add leaves the host's scope unchanged`() {
        val scope = CompositeQueryRequestScope.of(DefaultQueryRequestScope, listOf(appScope))
        val request = MockServerRequest.builder().header(CommandHeaders.TENANT_ID, "t1").build()
        scope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request))
    }

    @Test
    fun `the host's scope runs first, so a blank path segment is reported before a contributor refuses`() {
        val refusing = ScopeContributor { _, _ -> throw IllegalStateException("refused") }
        val scope = CompositeQueryRequestScope.of(DefaultQueryRequestScope, listOf(refusing))
        val blank = MockServerRequest.builder().pathVariable(MessageRecords.TENANT_ID, " ").build()
        assertThrows<IllegalArgumentException> { scope.resolve(MOCK_AGGREGATE_METADATA, blank) }
        assertThrows<IllegalStateException> {
            scope.resolve(MOCK_AGGREGATE_METADATA, MockServerRequest.builder().build())
        }
    }
}

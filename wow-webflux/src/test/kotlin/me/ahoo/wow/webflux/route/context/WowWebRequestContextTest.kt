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

package me.ahoo.wow.webflux.route.context

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.identity.IdentityHeaderAliases
import me.ahoo.wow.webflux.route.identity.RouteIdentity
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class WowWebRequestContextTest {

    @Test
    fun `should create context from request`() {
        val aggregateId = generateGlobalId()
        val tenantId = generateGlobalId()
        val requestId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, tenantId)
            .header(CommandHeaders.REQUEST_ID, requestId)
            .header(HttpHeaders.ACCEPT, "text/event-stream")
            .build()

        val context = WowWebRequestContext.of(request, MOCK_AGGREGATE_METADATA)

        context.request.assert().isSameAs(request)
        context.aggregateMetadata.assert().isSameAs(MOCK_AGGREGATE_METADATA)
        context.aggregateId.id.assert().isEqualTo(aggregateId)
        context.aggregateId.tenantId.assert().isEqualTo(tenantId)
        context.requestId.assert().isEqualTo(requestId)
        context.sse.assert().isTrue()
    }

    @Test
    fun `should use default tenant id when tenant is missing`() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()

        val context = WowWebRequestContext.of(request, MOCK_AGGREGATE_METADATA)

        context.aggregateId.id.assert().isEqualTo(aggregateId)
        context.aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        context.requestId.assert().isNull()
        context.sse.assert().isFalse()
    }

    /** The request ID is read as the command routes read it: header aliases apply and a blank header is absent. */
    @Test
    fun `should read the request id through the route identity`() {
        val blank = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .header(CommandHeaders.REQUEST_ID, " ")
            .build()
        WowWebRequestContext.of(blank, MOCK_AGGREGATE_METADATA).requestId.assert().isNull()

        val aliased = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .header("X-Alias-Request-Id", "request-a")
            .build()
        RouteIdentity.withAliases(aliased, IdentityHeaderAliases(requestId = listOf("X-Alias-Request-Id")))
        WowWebRequestContext.of(aliased, MOCK_AGGREGATE_METADATA).requestId.assert().isEqualTo("request-a")
    }
}

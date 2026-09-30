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

package me.ahoo.wow.viewstore.starter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.webflux.route.writeRawRequest
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ViewStoreQueryPolicyTest {
    private val view = "view-store.view".toNamedAggregate()
    private val policy = ViewStoreQueryPolicy(setOf(view))

    private fun context(
        entry: QueryEntry = QueryEntry.HTTP,
        model: QueryModel = QueryModel.SNAPSHOT,
        namedAggregate: me.ahoo.wow.api.modeling.NamedAggregate = view,
    ): QueryContext<*> = mockk {
        every { this@mockk.entry } returns entry
        every { this@mockk.namedAggregate } returns namedAggregate
        every { schema.model } returns model
    }

    private fun request(
        tenantId: String? = "t1",
        ownerId: String? = "alice",
        appId: String? = "console",
    ): ServerRequest {
        val builder = MockServerRequest.builder()
        tenantId?.let { builder.pathVariable("tenantId", it) }
        ownerId?.let { builder.pathVariable("ownerId", it) }
        appId?.let { builder.header(ViewStoreService.APP_ID_HEADER, it) }
        return builder.build()
    }

    private fun evaluate(context: QueryContext<*>, request: ServerRequest? = request()): Mono<FilterExpression> {
        val evaluated = Mono.deferContextual { policy.evaluate(it, context) }
        return if (request == null) evaluated else evaluated.writeRawRequest(request)
    }

    private fun Mono<FilterExpression>.expectCode(errorCode: String) = test()
        .expectErrorMatches { it is ViewStoreException && it.errorCode == errorCode }
        .verify()

    @Test
    fun `restricts an HTTP snapshot query to the request's application`() {
        evaluate(context()).test()
            .consumeNextWith { it.toString().assert().contains("state.appId").contains("console") }
            .verifyComplete()
    }

    @Test
    fun `leaves in-process queries and other aggregates alone`() {
        evaluate(context(entry = QueryEntry.IN_PROCESS), null).test().expectNext(MatchAllFilter).verifyComplete()
        evaluate(context(namedAggregate = "example-service.order".toNamedAggregate()), null).test()
            .expectNext(MatchAllFilter).verifyComplete()
    }

    @Test
    fun `refuses HTTP event stream queries`() {
        evaluate(context(model = QueryModel.EVENT_STREAM)).expectCode(ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED)
    }

    @Test
    fun `refuses a query without tenant and owner in its path`() {
        evaluate(context(), request(tenantId = null)).expectCode(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        evaluate(context(), request(ownerId = null)).expectCode(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        evaluate(context(), null).expectCode(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
    }

    @Test
    fun `refuses a query without an application`() {
        evaluate(context(), request(appId = null)).expectCode(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
    }
}

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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ViewStoreQueryPolicyTest {
    private val view = "view-store.view".toNamedAggregate()
    private val policy = ViewStoreQueryPolicy(setOf(view))
    private val contributor = ViewStoreScopeContributor(setOf(view))

    private fun context(
        entry: QueryEntry = QueryEntry.HTTP,
        model: QueryModel = QueryModel.SNAPSHOT,
        namedAggregate: NamedAggregate = view,
    ): QueryContext<*> = mockk {
        every { this@mockk.entry } returns entry
        every { this@mockk.namedAggregate } returns namedAggregate
        every { schema.model } returns model
    }

    private fun evaluate(context: QueryContext<*>): Mono<FilterExpression> =
        Mono.deferContextual { policy.evaluate(it, context) }

    private fun metadata(namedAggregate: NamedAggregate = view): AggregateMetadata<*, *> = mockk {
        every { this@mockk.namedAggregate } returns namedAggregate
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

    private fun contribute(request: ServerRequest, namedAggregate: NamedAggregate = view): QueryScope =
        contributor.contribute(metadata(namedAggregate), request)

    private fun refusal(request: ServerRequest): String =
        assertThrows<ViewStoreException> { contribute(request) }.errorCode

    @Test
    fun `refuses HTTP event stream queries`() {
        evaluate(context(model = QueryModel.EVENT_STREAM)).test()
            .expectErrorMatches {
                it is ViewStoreException && it.errorCode == ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED
            }
            .verify()
    }

    @Test
    fun `leaves snapshot queries, in-process queries and other aggregates to their scope`() {
        evaluate(context()).test().expectNext(MatchAllFilter).verifyComplete()
        evaluate(context(entry = QueryEntry.IN_PROCESS, model = QueryModel.EVENT_STREAM)).test()
            .expectNext(MatchAllFilter).verifyComplete()
        evaluate(context(model = QueryModel.EVENT_STREAM, namedAggregate = "example-service.order".toNamedAggregate()))
            .test().expectNext(MatchAllFilter).verifyComplete()
    }

    @Test
    fun `restricts an HTTP query to the request's application, as declared scope`() {
        val scope = contribute(request())
        scope.authenticated.assert().isEqualTo(MatchAllFilter)
        scope.declared.toString().assert().contains("state.appId").contains("console")
    }

    @Test
    fun `adds nothing for other aggregates`() {
        contribute(request(tenantId = null, appId = null), "example-service.order".toNamedAggregate())
            .assert().isEqualTo(QueryScope.NONE)
    }

    @Test
    fun `refuses a query without tenant and owner in its path`() {
        refusal(request(tenantId = null)).assert().isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        refusal(request(ownerId = null)).assert().isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        refusal(request(tenantId = null, ownerId = null, appId = null))
            .assert().isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
    }

    @Test
    fun `refuses a query without an application`() {
        refusal(request(appId = null)).assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
        refusal(request(appId = " ")).assert().isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
    }
}

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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

class QueryGatewayHandlerFunctionTest {

    @Test
    fun `four HTTP operations preserve wire shape and invoke gateway`() {
        val gateway = RecordingGateway()
        val admission = WebFluxQueryAdmission(
            WebFluxQueryAuthorityResolver.SUBJECT,
            QueryAuthorityProvider { Mono.just(QueryAuthorityView(null, null, null, emptySet(), emptySet())) }
        )
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val single = SingleSnapshotHandlerFunctionFactory(
            gateway,
            DefaultRewriteRequestCondition,
            admission,
            exceptionHandler
        ).create(contract(BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE))
        val list = ListQuerySnapshotHandlerFunctionFactory(
            gateway,
            DefaultRewriteRequestCondition,
            admission,
            exceptionHandler
        ).create(contract(BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY))
        val page = PagedQuerySnapshotHandlerFunctionFactory(
            gateway,
            DefaultRewriteRequestCondition,
            admission,
            exceptionHandler
        ).create(contract(BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY))
        val count = CountSnapshotHandlerFunctionFactory(
            gateway,
            DefaultRewriteRequestCondition,
            admission,
            exceptionHandler
        ).create(contract(BuiltInHttpRouteHandlerKeys.Snapshot.COUNT))

        single.handle(request(SingleQuery(Condition.ALL).toMono()))
            .test()
            .consumeNextWith { response -> response.writeBody().assert().isEqualTo("{\"value\":\"single\"}") }
            .verifyComplete()
        list.handle(request(ListQuery(Condition.ALL).toMono()))
            .test()
            .consumeNextWith { response -> response.writeBody().assert().isEqualTo("[{\"value\":\"list\"}]") }
            .verifyComplete()
        page.handle(request(PagedQuery(Condition.ALL).toMono()))
            .test()
            .consumeNextWith { response ->
                response.writeBody().assert().isEqualTo("{\"total\":1,\"list\":[{\"value\":\"page\"}]}")
            }
            .verifyComplete()
        count.handle(request(Condition.ALL.toMono()))
            .test()
            .consumeNextWith { response -> response.writeBody().assert().isEqualTo("7") }
            .verifyComplete()

        gateway.singleRequests.single().target.documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
        gateway.listRequests.single().target.documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
        gateway.pageRequests.single().target.documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
        gateway.countRequests.single().target.documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
    }

    private fun contract(handlerKey: String) = testAggregateRouteContract(
        handlerKey = handlerKey,
        aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
    )

    private fun request(body: Mono<*>): MockServerRequest = MockServerRequest.builder()
        .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
        .body(body)

    private fun ServerResponse.writeBody(): String {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
        val strategies = HandlerStrategies.withDefaults()
        val context = object : ServerResponse.Context {
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
        writeTo(exchange, context).block()
        return exchange.response.bodyAsString.block()!!
    }

    private class RecordingGateway : QueryGateway {
        val singleRequests = CopyOnWriteArrayList<SingleQueryRequest<*>>()
        val listRequests = CopyOnWriteArrayList<ListQueryRequest<*>>()
        val pageRequests = CopyOnWriteArrayList<PageQueryRequest<*>>()
        val countRequests = CopyOnWriteArrayList<CountQueryRequest>()

        override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> {
            singleRequests += request
            @Suppress("UNCHECKED_CAST")
            return Mono.just(document("single") as R)
        }

        override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> {
            listRequests += request
            @Suppress("UNCHECKED_CAST")
            return Flux.just(document("list") as R)
        }

        override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> {
            pageRequests += request
            @Suppress("UNCHECKED_CAST")
            return Mono.just(QueryPage(listOf(document("page") as R), 1, QueryConsistency.EXACT))
        }

        override fun count(request: CountQueryRequest): Mono<Long> {
            countRequests += request
            return Mono.just(7)
        }

        private fun document(value: String) = ImmutableDynamicDocument.copyOf(mapOf("value" to value))
    }
}

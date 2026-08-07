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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class QueryServiceFacadeTest {
    private val requests = mutableListOf<QueryCallResolutionRequest>()
    private val gateway = EmptyQueryGateway()
    private val resolver = QueryCallResolver { request ->
        requests += request
        Mono.just(QueryCall(request.target, PURPOSE))
    }

    @Test
    fun `all compatibility methods should remain cold and resolve exact calls per subscription`() {
        val snapshot = GatewaySnapshotQueryServiceFactory(gateway, resolver).create<Any>(AGGREGATE)
        val eventStream = GatewayEventStreamQueryServiceFactory(gateway, resolver).create(AGGREGATE)
        val snapshotPublishers = listOf(
            snapshot.single(SINGLE_QUERY),
            snapshot.dynamicSingle(SINGLE_QUERY),
            snapshot.list(LIST_QUERY),
            snapshot.dynamicList(LIST_QUERY),
            snapshot.paged(PAGED_QUERY),
            snapshot.dynamicPaged(PAGED_QUERY),
            snapshot.count(CONDITION),
        )
        val eventPublishers = listOf(
            eventStream.single(SINGLE_QUERY),
            eventStream.dynamicSingle(SINGLE_QUERY),
            eventStream.list(LIST_QUERY),
            eventStream.dynamicList(LIST_QUERY),
            eventStream.paged(PAGED_QUERY),
            eventStream.dynamicPaged(PAGED_QUERY),
            eventStream.count(CONDITION),
        )

        requests.assert().isEmpty()
        snapshotPublishers.forEach(::verifyPublisher)
        eventPublishers.forEach(::verifyPublisher)

        requests.map(QueryCallResolutionRequest::queryType).assert().containsExactly(
            QueryType.SINGLE,
            QueryType.DYNAMIC_SINGLE,
            QueryType.LIST,
            QueryType.DYNAMIC_LIST,
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
            QueryType.SINGLE,
            QueryType.DYNAMIC_SINGLE,
            QueryType.LIST,
            QueryType.DYNAMIC_LIST,
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
        )
        requests.take(7).map { request -> request.target.documentKind }.assert()
            .allMatch { documentKind -> documentKind == QueryDocumentKind.SNAPSHOT }
        requests.drop(7).map { request -> request.target.documentKind }.assert()
            .allMatch { documentKind -> documentKind == QueryDocumentKind.EVENT_STREAM }
        requests.map { request -> request.target.namedAggregate }.assert()
            .allMatch { namedAggregate -> namedAggregate == AGGREGATE }
    }

    @Test
    fun `mismatched call target should fail before gateway execution`() {
        val mismatchedResolver = QueryCallResolver {
            Mono.just(
                QueryCall(
                    QueryTarget(MaterializedNamedAggregate("test", "other"), QueryDocumentKind.SNAPSHOT),
                    PURPOSE,
                ),
            )
        }
        val service = GatewaySnapshotQueryServiceFactory(gateway, mismatchedResolver).create<Any>(AGGREGATE)

        StepVerifier.create(service.count(CONDITION))
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).code.assert().isEqualTo("QUERY_CALL_TARGET_MISMATCH")
                error.path.assert().isEqualTo("$.executionContext.call")
            }
            .verify()
        gateway.countCalls.assert().isZero()
    }

    private fun verifyPublisher(publisher: org.reactivestreams.Publisher<*>) {
        StepVerifier.create(publisher).thenConsumeWhile { true }.verifyComplete()
    }

    private class EmptyQueryGateway : QueryGateway {
        var countCalls: Int = 0

        override fun single(call: QueryCall, query: ISingleQuery): Mono<DynamicDocument> = Mono.empty()

        override fun <R : Any> single(call: QueryCall, query: ISingleQuery, resultType: Class<R>): Mono<R> = Mono.empty()

        override fun stream(call: QueryCall, query: IListQuery): Flux<DynamicDocument> = Flux.empty()

        override fun <R : Any> stream(call: QueryCall, query: IListQuery, resultType: Class<R>): Flux<R> = Flux.empty()

        override fun page(call: QueryCall, query: IPagedQuery): Mono<PagedList<DynamicDocument>> = Mono.empty()

        override fun <R : Any> page(
            call: QueryCall,
            query: IPagedQuery,
            resultType: Class<R>,
        ): Mono<PagedList<R>> = Mono.empty()

        override fun count(call: QueryCall, condition: Condition): Mono<Long> {
            countCalls++
            return Mono.empty()
        }
    }

    private companion object {
        val AGGREGATE = MaterializedNamedAggregate("test", "order")
        val CONDITION = Condition.all()
        val SINGLE_QUERY = SingleQuery(CONDITION)
        val LIST_QUERY = ListQuery(CONDITION)
        val PAGED_QUERY = PagedQuery(CONDITION)
        val PURPOSE = QueryPurpose("facade-test")
    }
}

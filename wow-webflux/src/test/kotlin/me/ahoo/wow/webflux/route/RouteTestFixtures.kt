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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.query.WebFluxQueryAdmission
import me.ahoo.wow.webflux.route.query.WebFluxQueryAuthorityResolver
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal object RouteTestFixtures {
    val MOCK_AGGREGATE_ROUTE_METADATA =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

    val queryGateway: QueryGateway = EmptyQueryGateway
    val queryAdmission = WebFluxQueryAdmission(
        WebFluxQueryAuthorityResolver.SUBJECT,
        QueryAuthorityProvider { Mono.just(QueryAuthorityView(null, null, null, emptySet(), emptySet())) }
    )
}

private object EmptyQueryGateway : QueryGateway {
    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.empty()

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.empty()

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> =
        Mono.just(QueryPage(emptyList(), 0, QueryConsistency.EXACT))

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.just(0)
}

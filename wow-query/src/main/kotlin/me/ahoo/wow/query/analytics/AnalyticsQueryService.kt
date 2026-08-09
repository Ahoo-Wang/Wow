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

package me.ahoo.wow.query.analytics

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.analytics.AnalyticsPage
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContext
import me.ahoo.wow.query.gateway.QueryValidationMode
import reactor.core.publisher.Mono

/** Target-bound Snapshot analytics service. It is intentionally separate from the seven-method QueryService. */
interface AnalyticsQueryService : NamedAggregateDecorator {
    fun analyze(query: AnalyticsQuery): Mono<AnalyticsPage>
}

interface AnalyticsQueryServiceFactory {
    fun create(namedAggregate: me.ahoo.wow.api.modeling.NamedAggregate): AnalyticsQueryService
}

@ExperimentalQueryGatewayApi
data class AnalyticsQueryTrustedContextRequest(
    val target: QueryTarget,
    val executionMode: QueryExecutionMode,
    val validationMode: QueryValidationMode,
)

@ExperimentalQueryGatewayApi
fun interface AnalyticsQueryTrustedContextResolver {
    fun resolve(request: AnalyticsQueryTrustedContextRequest): Mono<QueryTrustedContext>
}

@ExperimentalQueryGatewayApi
class CompositeAnalyticsQueryTrustedContextResolver(
    resolvers: Iterable<AnalyticsQueryTrustedContextResolver>,
) : AnalyticsQueryTrustedContextResolver {
    private val resolvers = resolvers.toList()

    init {
        require(this.resolvers.isNotEmpty()) { "At least one Analytics trusted context resolver is required." }
    }

    override fun resolve(request: AnalyticsQueryTrustedContextRequest): Mono<QueryTrustedContext> =
        reactor.core.publisher.Flux.fromIterable(resolvers)
            .concatMap { resolver -> Mono.defer { resolver.resolve(request) } }
            .next()
}

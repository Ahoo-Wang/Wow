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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

class SnapshotAggregationQueryContext(
    val namedAggregate: NamedAggregate,
    query: AggregationQuery,
    val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) {
    var query: AggregationQuery = query
        private set

    private var result: Flux<DynamicDocument>? = null

    fun rewriteQuery(rewrite: (AggregationQuery) -> AggregationQuery) {
        query = rewrite(query)
    }

    fun setResult(result: Flux<DynamicDocument>) {
        this.result = result
    }

    fun rewriteResult(rewrite: (Flux<DynamicDocument>) -> Flux<DynamicDocument>) {
        result = rewrite(getRequiredResult())
    }

    fun getRequiredResult(): Flux<DynamicDocument> = checkNotNull(result) {
        "Snapshot aggregation filter chain did not provide a result."
    }
}

interface SnapshotAggregationQueryFilter : Filter<SnapshotAggregationQueryContext>

/**
 * Supplies the aggregation policy equivalent of a Snapshot query filter.
 * Query filters without this contract disable Snapshot aggregation fail-closed.
 */
fun interface SnapshotAggregationQueryFilterProvider {
    fun createSnapshotAggregationQueryFilter(): SnapshotAggregationQueryFilter
}

@Order(ORDER_LAST)
class TailSnapshotAggregationQueryFilter(
    private val queryServiceFactory: SnapshotQueryServiceFactory,
) : SnapshotAggregationQueryFilter {
    override fun filter(
        context: SnapshotAggregationQueryContext,
        next: FilterChain<SnapshotAggregationQueryContext>,
    ): Mono<Void> {
        context.setResult(
            queryServiceFactory.create<Any>(context.namedAggregate).aggregate(context.query),
        )
        return next.filter(context)
    }
}

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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import reactor.core.publisher.Mono

@FilterType(SnapshotQueryHandler::class)
interface SnapshotQueryFilter : Filter<SnapshotQueryContext<*, *, *>>

@Order(ORDER_LAST)
@FilterType(SnapshotQueryHandler::class)
@Suppress("UNCHECKED_CAST")
class TailSnapshotQueryFilter<S : Any>(private val snapshotQueryServiceFactory: SnapshotQueryServiceFactory) :
    SnapshotQueryFilter {
    override fun filter(
        context: SnapshotQueryContext<*, *, *>,
        next: FilterChain<SnapshotQueryContext<*, *, *>>
    ): Mono<Void> {
        val snapshotQueryService = snapshotQueryServiceFactory.create<S>(context.namedAggregate)
        when (context.queryType) {
            QueryType.SINGLE -> {
                context as SingleSnapshotQueryContext<MaterializedSnapshot<S>>
                context.setResult(snapshotQueryService.single(context.getQuery()))
            }

            QueryType.DYNAMIC_SINGLE -> {
                context as SingleSnapshotQueryContext<DynamicDocument>
                context.setResult(snapshotQueryService.dynamicSingle(context.getQuery()))
            }

            QueryType.LIST -> {
                context as ListSnapshotQueryContext<MaterializedSnapshot<S>>
                context.setResult(snapshotQueryService.list(context.getQuery()))
            }

            QueryType.DYNAMIC_LIST -> {
                context as ListSnapshotQueryContext<DynamicDocument>
                context.setResult(snapshotQueryService.dynamicList(context.getQuery()))
            }

            QueryType.PAGED -> {
                context as PagedSnapshotQueryContext<MaterializedSnapshot<S>>
                context.setResult(snapshotQueryService.paged(context.getQuery()))
            }

            QueryType.DYNAMIC_PAGED -> {
                context as PagedSnapshotQueryContext<DynamicDocument>
                context.setResult(snapshotQueryService.dynamicPaged(context.getQuery()))
            }

            QueryType.COUNT -> {
                context as CountSnapshotQueryContext
                context.setResult(snapshotQueryService.count(context.getQuery()))
            }
        }
        return next.filter(context)
    }
}

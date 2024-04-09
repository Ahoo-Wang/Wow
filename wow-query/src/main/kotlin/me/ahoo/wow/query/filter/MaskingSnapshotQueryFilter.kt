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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.tryMask
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
object MaskingSnapshotQueryFilter : SnapshotQueryFilter {
    override fun filter(
        context: SnapshotQueryContext<*, *, *>,
        next: FilterChain<SnapshotQueryContext<*, *, *>>
    ): Mono<Void> {
        return next.filter(context).then(
            Mono.defer {
                tryMask(context)
                Mono.empty()
            }
        )
    }

    private fun tryMask(context: SnapshotQueryContext<*, *, *>) {
        when (context.queryType) {
            QueryType.SINGLE -> {
                context as SingleSnapshotQueryContext<Any>
                val result = context.getRequiredResult().map {
                    it.tryMask()
                }
                context.setResult(result)
            }

            QueryType.QUERY -> {
                context as QuerySnapshotQueryContext<Any>
                val result = context.getRequiredResult().map {
                    it.tryMask()
                }
                context.setResult(result)
            }

            QueryType.PAGED_QUERY -> {
                context as PagedSnapshotQueryContext<Any>
                val result = context.getRequiredResult().map {
                    it.tryMask()
                }
                context.setResult(result)
            }

            QueryType.COUNT -> {
                context as CountSnapshotQueryContext
            }
        }
    }
}

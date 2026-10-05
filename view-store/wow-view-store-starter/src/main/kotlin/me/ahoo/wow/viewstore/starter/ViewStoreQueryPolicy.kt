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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Refuses HTTP queries of the view store's event streams with [ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED]: they
 * hold other users' personal configs. The tenant, owner and application of the snapshot queries are their scope
 * ([ViewStoreScopeContributor]). Other models, other aggregates and in-process queries pass untouched.
 */
internal class ViewStoreQueryPolicy(private val namedAggregates: Set<NamedAggregate>) : QueryPolicy {
    override fun evaluate(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> {
        if (context.entry != QueryEntry.HTTP ||
            context.schema.model == QueryModel.SNAPSHOT ||
            namedAggregates.none { it.isSameAggregateName(context.namedAggregate) }
        ) {
            return Mono.just(MatchAllFilter)
        }
        return Mono.error(
            ViewStoreException(
                ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED,
                "The view store's event streams are not open to HTTP queries."
            )
        )
    }
}

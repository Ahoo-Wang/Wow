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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * The HTTP rules of the view store's aggregates that need no request:
 * - an HTTP query of their event streams is refused with [ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED]: they hold
 *   other users' personal configs;
 * - an HTTP snapshot query must carry the application restriction ([ViewStoreScopeContributor]) in its caller scope,
 *   else it is refused with [ViewStoreErrorCodes.VIEW_APP_REQUIRED]. The contributor adds it on Wow's query routes;
 *   a host that builds the query routes or handlers with its own `QueryRequestScope` skips contributors, and its
 *   queries fail closed here instead of reading every application's views.
 *
 * Other aggregates and in-process queries pass untouched.
 */
internal class ViewStoreQueryPolicy(private val namedAggregates: Set<NamedAggregate>) : QueryPolicy {
    override fun evaluate(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> {
        if (context.entry != QueryEntry.HTTP ||
            namedAggregates.none { it.isSameAggregateName(context.namedAggregate) }
        ) {
            return Mono.just(MatchAllFilter)
        }
        if (context.schema.model != QueryModel.SNAPSHOT) {
            return Mono.error(
                ViewStoreException(
                    ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED,
                    "The view store's event streams are not open to HTTP queries."
                )
            )
        }
        if (!contextView.queryScope().restrictsApplication()) {
            return Mono.error(ViewStoreException.appRequired())
        }
        return Mono.just(MatchAllFilter)
    }

    /** Whether this scope, a conjunction, pins `state.appId` to one value. */
    private fun FilterExpression.restrictsApplication(): Boolean = when (this) {
        is AndFilter -> operands.any { it.restrictsApplication() }
        is EqualFilter -> field.path == APP_ID_PATH
        else -> false
    }

    private companion object {
        const val APP_ID_PATH = "${StateAggregateRecords.STATE}.${ViewStoreScopeContributor.APP_ID_FIELD}"
    }
}

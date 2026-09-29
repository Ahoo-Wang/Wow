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
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.webflux.route.getRawRequest
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Keeps HTTP queries of the view store's aggregates within one tenant, one owner and one application:
 * - only the routes with `tenant/{tenantId}/owner/{ownerId}` in their path are open (the path is what the gateway
 *   checks, and Wow turns it into the query scope);
 * - the query is restricted to the request's `CoSec-App-Id`, and the caller cannot take the restriction off;
 * - their event streams hold other users' personal configs, so HTTP queries of them are refused.
 *
 * Other aggregates, and in-process queries, pass untouched. A point read admitted without the raw request fails
 * closed.
 */
class ViewStoreQueryPolicy(private val namedAggregates: Set<NamedAggregate>) : QueryPolicy {
    companion object {
        const val APP_ID_FIELD = "appId"
    }

    override fun evaluate(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> {
        if (context.entry != QueryEntry.HTTP || namedAggregates.none { it.isSameAggregateName(context.namedAggregate) }) {
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
        val request = contextView.getRawRequest()
        val pathVariables = request?.pathVariables().orEmpty()
        if (pathVariables[ViewStorePaths.TENANT_ID].isNullOrBlank() || pathVariables[ViewStorePaths.OWNER_ID].isNullOrBlank()) {
            return Mono.error(
                ViewStoreException(
                    ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED,
                    "Query the view store under tenant/{tenantId}/owner/{ownerId}."
                )
            )
        }
        val appId = request?.headers()?.firstHeader(ViewStoreService.APP_ID_HEADER)
        if (appId.isNullOrBlank()) {
            return Mono.error(ViewStoreException.appRequired())
        }
        return Mono.just(
            filter {
                pathState {
                    APP_ID_FIELD eq appId
                }
            }
        )
    }
}

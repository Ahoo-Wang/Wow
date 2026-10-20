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
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.webflux.route.query.ScopeContributor
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Keeps HTTP queries of the view store's aggregates within one tenant, one owner and one application. It is a
 * [ScopeContributor], so it adds the application beside the host's scope, which already holds the path's tenant and
 * owner:
 * - only the routes with `tenant/{tenantId}/owner/{ownerId}` in their path are served (the path is what the gateway
 *   checks); a query route without them is refused with [ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED];
 * - the query is restricted to the request's `CoSec-App-Id`, and the caller cannot take the restriction off; a query
 *   without one is refused with [ViewStoreErrorCodes.VIEW_APP_REQUIRED].
 *
 * It adds nothing for other aggregates, and in-process queries never reach a scope contributor. Point reads under
 * point-read admission get the same scope.
 */
internal class ViewStoreScopeContributor(private val namedAggregates: Set<NamedAggregate>) : ScopeContributor {
    companion object {
        const val APP_ID_FIELD = "appId"
    }

    override fun contribute(aggregateMetadata: AggregateMetadata<*, *>, request: ServerRequest): QueryScope {
        if (namedAggregates.none { it.isSameAggregateName(aggregateMetadata.namedAggregate) }) {
            return QueryScope.NONE
        }
        val pathVariables = request.pathVariables()
        if (pathVariables[ViewStorePaths.TENANT_ID].isNullOrBlank() ||
            pathVariables[ViewStorePaths.OWNER_ID].isNullOrBlank()
        ) {
            throw ViewStoreException(
                ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED,
                "Query the view store under tenant/{tenantId}/owner/{ownerId}."
            )
        }
        val appId = request.headers().firstHeader(ViewStoreService.APP_ID_HEADER)
        if (appId.isNullOrBlank()) {
            throw ViewStoreException.appRequired()
        }
        return QueryScope(
            declared = filter {
                pathState {
                    APP_ID_FIELD eq appId
                }
            }
        )
    }
}

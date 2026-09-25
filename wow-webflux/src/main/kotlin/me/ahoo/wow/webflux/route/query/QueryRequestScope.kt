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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.QueryScopeProvenance
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.command.getTenantId
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The HTTP adapter's scope provider: the caller scope of a query route, each part tagged with where it came from.
 * Only an [authenticated][QueryScopeProvenance.AUTHENTICATED] part is a security boundary; see
 * [me.ahoo.wow.query.QueryEntryPolicy.requireAuthenticatedScope].
 */
fun interface QueryRequestScope {
    fun resolve(aggregateMetadata: AggregateMetadata<*, *>, request: ServerRequest): QueryScope
}

/**
 * Resolves the tenant, owner and space of a request. Values read from the request itself (path variables, headers)
 * are [declared][QueryScopeProvenance.DECLARED]; an aggregate's static tenant is a server fact and
 * [authenticated][QueryScopeProvenance.AUTHENTICATED]. Override the `*Provenance` functions when a trusted
 * component (an authenticating gateway that owns these headers, say) vouches for them.
 */
abstract class AbstractQueryRequestScope : QueryRequestScope {
    protected open fun ServerRequest.resolveTenantId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getTenantId(aggregateMetadata)
    }

    protected open fun ServerRequest.resolveOwnerId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getOwnerId()
    }

    protected open fun ServerRequest.resolveSpaceId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getSpaceId()
    }

    protected open fun ServerRequest.tenantIdProvenance(
        aggregateMetadata: AggregateMetadata<*, *>,
        tenantId: String,
    ): QueryScopeProvenance = if (tenantId == aggregateMetadata.staticTenantId) {
        QueryScopeProvenance.AUTHENTICATED
    } else {
        QueryScopeProvenance.DECLARED
    }

    protected open fun ServerRequest.ownerIdProvenance(
        aggregateMetadata: AggregateMetadata<*, *>,
        ownerId: String,
    ): QueryScopeProvenance = QueryScopeProvenance.DECLARED

    protected open fun ServerRequest.spaceIdProvenance(
        aggregateMetadata: AggregateMetadata<*, *>,
        spaceId: String,
    ): QueryScopeProvenance = QueryScopeProvenance.DECLARED

    override fun resolve(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
    ): QueryScope {
        val parts = listOfNotNull(
            request.resolveTenantId(aggregateMetadata).nonBlank()?.let {
                request.tenantIdProvenance(aggregateMetadata, it) to TenantIdFilter(it)
            },
            request.resolveOwnerId(aggregateMetadata).nonBlank()?.let {
                request.ownerIdProvenance(aggregateMetadata, it) to OwnerIdFilter(it)
            },
            request.resolveSpaceId(aggregateMetadata).nonBlank()?.let {
                request.spaceIdProvenance(aggregateMetadata, it) to SpaceIdFilter(it)
            },
        )
        return QueryScope(
            authenticated = parts.scopeOf(QueryScopeProvenance.AUTHENTICATED),
            declared = parts.scopeOf(QueryScopeProvenance.DECLARED),
        )
    }

    private fun String?.nonBlank(): String? = takeUnless { it.isNullOrBlank() }

    private fun List<Pair<QueryScopeProvenance, FilterExpression>>.scopeOf(
        provenance: QueryScopeProvenance
    ): FilterExpression = filter { it.first == provenance }
        .fold(MatchAllFilter as FilterExpression) { scope, (_, part) -> scope.appendFilter(part) }
}

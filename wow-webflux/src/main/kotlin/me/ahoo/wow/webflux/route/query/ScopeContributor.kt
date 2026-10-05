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
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.QueryScopeProvenance
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Adds one dimension to the caller scope of a query route: a restriction derived from the request, tagged with its
 * [provenance][QueryScopeProvenance] by the [QueryScope] half it is returned in. An embedded library (the view store's
 * application, say) adds its dimension this way beside the host's [QueryRequestScope], instead of replacing it.
 *
 * Return a value in the [authenticated][QueryScope.authenticated] half only when it was checked against the caller's
 * credentials (or comes from them); anything the request merely states (a header, a path variable) belongs in the
 * [declared][QueryScope.declared] half, which restricts the query but is no security boundary.
 *
 * Only the query routes and point-read admission the starter builds apply contributors. A host that builds them with
 * its own [QueryRequestScope] skips them, so a contributor whose restriction is a security rule should also check for
 * it where it is enforced (a [QueryPolicy][me.ahoo.wow.query.QueryPolicy] reading the caller scope fails closed).
 *
 * Contributors run after the host's [QueryRequestScope] (so a blank identity path segment is reported first), in
 * their order, on every query route and on point reads under point-read admission; their scopes are appended to the
 * host's. A contributor that has nothing to add for an aggregate returns [QueryScope.NONE]; one that throws refuses
 * the request with its error. Since 9.3.0.
 */
fun interface ScopeContributor {
    fun contribute(aggregateMetadata: AggregateMetadata<*, *>, request: ServerRequest): QueryScope
}

/**
 * The [QueryRequestScope] of the query routes: the host's [identity] scope (tenant, owner and space, see
 * [AbstractQueryRequestScope]) followed by every [contributor][ScopeContributor]'s, each half appended to the same
 * half. Since 9.3.0.
 */
class CompositeQueryRequestScope(
    val identity: QueryRequestScope,
    val contributors: List<ScopeContributor>,
) : QueryRequestScope {
    override fun resolve(aggregateMetadata: AggregateMetadata<*, *>, request: ServerRequest): QueryScope =
        contributors.fold(identity.resolve(aggregateMetadata, request)) { scope, contributor ->
            scope.and(contributor.contribute(aggregateMetadata, request))
        }

    companion object {
        /** [identity] with [contributors] appended; [identity] itself when there are none. */
        fun of(identity: QueryRequestScope, contributors: List<ScopeContributor>): QueryRequestScope =
            if (contributors.isEmpty()) identity else CompositeQueryRequestScope(identity, contributors)
    }
}

private fun QueryScope.and(other: QueryScope): QueryScope {
    if (other == QueryScope.NONE) {
        return this
    }
    return QueryScope(
        authenticated = authenticated.and(other.authenticated),
        declared = declared.and(other.declared),
    )
}

private fun FilterExpression.and(other: FilterExpression): FilterExpression =
    if (other == MatchAllFilter) this else appendFilter(other)

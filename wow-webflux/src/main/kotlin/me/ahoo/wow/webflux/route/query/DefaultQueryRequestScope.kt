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

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.webflux.route.identity.IdentityHeaderAliases
import me.ahoo.wow.webflux.route.identity.RouteIdentity
import org.springframework.web.reactive.function.server.ServerRequest

object DefaultQueryRequestScope : AbstractQueryRequestScope()

/**
 * [delegate] with [identityHeaderAliases] (CoSec's, say) applied also to a request whose handler was invoked outside
 * the router; a request the router dispatched already carries its route's aliases. Since 9.3.0.
 */
class IdentityHeaderAliasesQueryRequestScope(
    private val delegate: QueryRequestScope,
    private val identityHeaderAliases: IdentityHeaderAliases,
) : QueryRequestScope {
    override fun resolve(aggregateMetadata: AggregateMetadata<*, *>, request: ServerRequest): QueryScope {
        RouteIdentity.withAliases(request, identityHeaderAliases)
        return delegate.resolve(aggregateMetadata, request)
    }
}

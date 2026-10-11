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

package me.ahoo.wow.analytics.server

import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.cosec.webflux.ServerWebExchanges
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.webflux.route.dataset.DatasetQueryRequestScope
import me.ahoo.wow.webflux.route.dataset.DefaultDatasetQueryRequestScope
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The caller's tenant on a dataset route is the one CoSec authenticated, not the one the request declares: a principal
 * of a tenant reads that tenant's rows whatever path or header it sends, as an
 * [authenticated][me.ahoo.wow.query.QueryScopeProvenance.AUTHENTICATED] scope admission enforces. A principal without
 * a tenant, or of the platform tenant, falls back to the [declared] scope.
 */
class CoSecDatasetQueryRequestScope(
    private val declared: DatasetQueryRequestScope = DefaultDatasetQueryRequestScope.INSTANCE,
) : DatasetQueryRequestScope {
    override fun resolve(dataset: QueryDataset, request: ServerRequest): QueryScope {
        val principal = with(ServerWebExchanges) { request.exchange().getSecurityContext() }?.principal
        val tenant = (principal as? TenantPrincipal)?.tenant?.takeIf { principal.authenticated && it.isUserTenant }
        return tenant?.let { QueryScope(authenticated = TenantIdFilter(it.tenantId)) }
            ?: declared.resolve(dataset, request)
    }
}

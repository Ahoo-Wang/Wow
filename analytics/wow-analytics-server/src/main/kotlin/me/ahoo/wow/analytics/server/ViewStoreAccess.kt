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

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.wow.viewstore.ViewStoreService
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The view store's gateway rules (view store guide, Security model), which the analytics service enforces itself since
 * no gateway stands in front of it: the path's tenant is the token's, and its owner the token's subject, except
 * that
 *
 * - the shared views of the tenant (`owner/(shared)`) are read by anyone of the tenant and written — created,
 *   changed, removed, their preferences set — by [sharedViewRole] only, as are a personal view's claim and share;
 * - the system views (`tenant/(platform)/owner/(system)`) are written by [systemViewRole] of the platform tenant
 *   only;
 * - any other view store path is refused.
 *
 * [decide] answers `null` to let the request through, or the status to refuse it with.
 */
class ViewStoreAccess(private val sharedViewRole: String, private val systemViewRole: String) {
    private val scoped = PATTERNS.parse("/view-store/tenant/{tenantId}/owner/{ownerId}/**")
    private val handover = PATTERNS.parse(
        "/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/{action:claim|share}"
    )

    fun decide(method: HttpMethod, path: PathContainer, principal: CoSecPrincipal): HttpStatus? {
        val variables = scoped.matchAndExtract(path)?.uriVariables ?: return HttpStatus.FORBIDDEN
        val tenantId = variables.getValue("tenantId")
        val ownerId = variables.getValue("ownerId")
        val tenant = (principal as? TenantPrincipal)?.tenant
        return when {
            ownerId == ViewStoreService.SYSTEM_OWNER_ID ->
                allowIf(
                    tenantId == ViewStoreService.SYSTEM_TENANT_ID && tenant?.isPlatformTenant == true &&
                        systemViewRole in principal.roles,
                )
            tenant?.tenantId != tenantId -> HttpStatus.FORBIDDEN
            ownerId == ViewStoreService.SHARED_OWNER_ID ->
                allowIf(method.reads(path) || sharedViewRole in principal.roles)
            ownerId != principal.id -> HttpStatus.FORBIDDEN
            handover.matches(path) -> allowIf(sharedViewRole in principal.roles)
            else -> null
        }
    }

    /** A read of the shared views: a GET, or a snapshot query (sent as POST to a `…/snapshot/…` path). */
    private fun HttpMethod.reads(path: PathContainer): Boolean =
        this == HttpMethod.GET ||
            (this == HttpMethod.POST && path.elements().any { it.value() == SNAPSHOT_SEGMENT })

    private fun allowIf(allowed: Boolean): HttpStatus? = if (allowed) null else HttpStatus.FORBIDDEN

    companion object {
        private val PATTERNS = PathPatternParser.defaultInstance
        private const val SNAPSHOT_SEGMENT = "snapshot"
    }
}

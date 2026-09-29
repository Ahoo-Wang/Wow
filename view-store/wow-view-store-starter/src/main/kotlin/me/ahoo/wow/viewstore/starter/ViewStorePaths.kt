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

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.viewstore.ViewStoreService

/**
 * Where the view store's routes live. Wow prefixes an aggregate's routes with its context's alias when the host's
 * own context is another, so a host (and the standalone server, whose context is not `view-store`) serves them under
 * `/view-store`; the custom routes follow the same rule.
 */
class ViewStorePaths(currentContext: NamedBoundedContext) {
    companion object {
        const val TENANT_ID = MessageRecords.TENANT_ID
        const val OWNER_ID = MessageRecords.OWNER_ID
        const val DEFINITION_ID = "definitionId"
        const val REQUEST_ID = "requestId"
        const val ID = MessageRecords.ID
    }

    /** `/view-store`, or empty when the host's own context is the view store's. */
    val prefix: String = if (currentContext.contextName == ViewStoreService.SERVICE_NAME) {
        ""
    } else {
        "/${ViewStoreService.SERVICE_ALIAS}"
    }

    /** The tenant and owner every route starts with. */
    val scope: String = "$prefix/tenant/{$TENANT_ID}/owner/{$OWNER_ID}"

    val systemViews: String = "$scope/system-views"
    val systemView: String = "$systemViews/{$ID}"
    val preferences: String = "$scope/definitions/{$DEFINITION_ID}/preferences"
    val replay: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/requests/{$REQUEST_ID}"

    /** Wow's route of `ShareView`, which the starter answers first (see [ViewAudienceHandlers.share]). */
    val share: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/{$ID}/share"

    /** The view store's route of `ClaimView` (see [ViewAudienceHandlers.claim]). */
    val claim: String = "$scope/${ViewStoreService.VIEW_AGGREGATE_NAME}/{$ID}/claim"

    private val scopePattern = Regex("^${Regex.escape(prefix)}/tenant/([^/]+)/owner/([^/]+)(/.*)?$")
    private val viewPattern = Regex(
        "^${Regex.escape(prefix)}/tenant/([^/]+)/owner/([^/]+)/${ViewStoreService.VIEW_AGGREGATE_NAME}/([^/]+)(/.*)?$"
    )

    /** Whether [path] is one of the view store's tenant-and-owner routes. */
    fun isViewStorePath(path: String): Boolean = scopePattern.matches(path)

    /** The tenant and id [path] addresses a view by, or `null` when it addresses none. */
    fun viewTarget(path: String): ViewTarget? {
        val match = viewPattern.matchEntire(path) ?: return null
        return ViewTarget(tenantId = match.groupValues[1], viewId = match.groupValues[3])
    }
}

data class ViewTarget(val tenantId: String, val viewId: String)

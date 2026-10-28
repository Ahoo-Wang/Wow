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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.wow.api.annotation.RouteMethod
import me.ahoo.wow.api.annotation.RouteQuery
import me.ahoo.wow.api.annotation.ServiceRoute
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewEntry
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.ViewStoreRejections

/**
 * The view store's own reads, in the caller's tenant and application ([me.ahoo.wow.api.caller.Caller.app]; refused
 * with `view-store.app-required` without one), through [reads].
 */
class ViewStoreRoutes(private val reads: ViewReads) {
    /** The system views of the caller's tenant and application, of [definitionId] when it is given and not blank. */
    @ServiceRoute(
        method = RouteMethod.GET,
        path = "system-views",
        errors = [ViewStoreRejections.APP_REQUIRED],
    )
    suspend fun systemViews(call: RouteCall, @RouteQuery definitionId: String?): List<SystemView> =
        reads.systemViews(call.tenantId, call.appId(), definitionId?.ifBlank { null })

    /**
     * The view [id] as the caller reads it: its own personal view, else a shared view of its tenant, else a system
     * view of its tenant and application; `view-store.not-found` when none is.
     */
    @ServiceRoute(
        method = RouteMethod.GET,
        path = "views/{id}",
        name = "view",
        errors = [ViewStoreRejections.APP_REQUIRED, ViewStoreRejections.NOT_FOUND],
    )
    suspend fun view(call: RouteCall, id: String): ViewEntry {
        val appId = call.appId()
        // A caller acting as a reserved owner has no personal view.
        val owner = call.scope.ownerId?.takeUnless(ViewStoreContext::isReserved)
        val owners = setOfNotNull(owner, ViewStoreContext.SHARED_OWNER_ID)
        val saved = reads.savedViews(call.tenantId, appId, id, owners)
        return PRECEDENCE.firstNotNullOfOrNull { scope -> saved.firstOrNull { it.scope == scope } }
            ?: reads.systemView(call.tenantId, appId, id)?.toEntry()
            ?: throw RouteRefusal(ViewStoreRejections.NOT_FOUND, "View [$id] is not found.")
    }

    private fun RouteCall.appId(): String = caller.app?.trim() ?: throw RouteRefusal(
        ViewStoreRejections.APP_REQUIRED,
        "The request carries no authenticated application (${ViewStoreContext.APP_ID_HEADER}).",
    )

    private fun SystemView.toEntry(): ViewEntry = ViewEntry(
        id = id,
        definitionId = definitionId,
        title = title,
        kind = kind,
        scope = ViewAudience.SYSTEM,
        revision = revision,
        config = config,
        version = version,
        source = source,
    )

    private companion object {
        /** The saved views' audiences, the one a caller reads first first. */
        val PRECEDENCE = listOf(ViewAudience.PERSONAL, ViewAudience.SHARED)
    }
}

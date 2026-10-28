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

import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.ViewEntry

/**
 * The view store's reads of views that [ViewStoreRoutes] answers: the snapshot queries and the configured system
 * views, kept behind this port so the domain does not depend on the query stack. The service binds it. Every read is
 * of one application: a view another application wrote reads as none.
 */
interface ViewReads {
    /**
     * The system views of [tenantId] and [appId], configured and stored, of [definitionId] when it is given; a stored
     * one wins over a configured one with the same id.
     */
    suspend fun systemViews(tenantId: String, appId: String, definitionId: String? = null): List<SystemView>

    /** System view [id] of [tenantId] and [appId]: the stored one, else the configured one; null when neither is. */
    suspend fun systemView(tenantId: String, appId: String, id: String): SystemView?

    /** The saved views [id] of [tenantId] and [appId] that one of [owners] owns, not deleted. */
    suspend fun savedViews(tenantId: String, appId: String, id: String, owners: Set<String>): List<ViewEntry>
}

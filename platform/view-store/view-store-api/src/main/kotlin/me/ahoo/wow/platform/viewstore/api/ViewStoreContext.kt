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

package me.ahoo.wow.platform.viewstore.api

/**
 * The view store context (roadmap 7.2a): the view engine's saved views, view preferences and the wording a tenant
 * sets over the datasets it reads, ported from the 9.x view store. Commands and events are named
 * `view-store.<aggregate>.<Name>`, routes start with `/view-store/<aggregate>`.
 *
 * A view belongs to its tenant and its owner (decision D189). The owner is the audience: a user for a personal view,
 * [SHARED_OWNER_ID] for a shared one, [SYSTEM_OWNER_ID] (under [SYSTEM_TENANT_ID] only) for a stored system view. A
 * caller acts as one of the reserved owners by naming it in `Wow-Scope` (decision D95), as far as the service's
 * caller resolver lets it.
 */
object ViewStoreContext {
    const val NAME: String = "view-store"
    const val VIEW: String = "view"
    const val VIEW_PREFERENCES: String = "view_preferences"
    const val DATASET_WORDING: String = "dataset_wording"

    /** The reserved owner of shared views and shared preferences; the parentheses keep it apart from every user id. */
    const val SHARED_OWNER_ID: String = "(shared)"

    /** The reserved owner of stored system views, which live under [SYSTEM_TENANT_ID] only. */
    const val SYSTEM_OWNER_ID: String = "(system)"

    /**
     * The one tenant stored system views live under: CoSec's platform tenant, by value. It is not the default tenant
     * `(0)` of a deployment without tenants.
     */
    const val SYSTEM_TENANT_ID: String = "(platform)"

    /**
     * The request header naming the calling application, which CoSec authenticates. The service carries it into each
     * command as a request fact (`wow.http.request-facts`), under this name.
     */
    const val APP_ID_HEADER: String = "CoSec-App-Id"

    /** Whether [ownerId] is a reserved owner: one in parentheses, such as [SHARED_OWNER_ID]. */
    fun isReserved(ownerId: String): Boolean = ownerId.startsWith("(") && ownerId.endsWith(")")
}

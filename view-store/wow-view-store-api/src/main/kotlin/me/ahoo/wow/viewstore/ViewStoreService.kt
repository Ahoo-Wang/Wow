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

package me.ahoo.wow.viewstore

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.view.CreateView

/**
 * The view store: the saved views and view preferences of the view engine's `ViewStore` port.
 *
 * Neither aggregate has a static tenant, so every route carries `tenant/{tenantId}`; both require an owner, so every
 * route carries `owner/{ownerId}` too. The owner segment is the audience: a user id for a personal view,
 * [SHARED_OWNER_ID] for a shared one, [SYSTEM_OWNER_ID] for a stored system view. Neither aggregate is spaced.
 */
@BoundedContext(
    name = ViewStoreService.SERVICE_NAME,
    alias = ViewStoreService.SERVICE_ALIAS,
    description = "The view engine's saved views and view preferences.",
    aggregates = [
        BoundedContext.Aggregate(
            name = ViewStoreService.VIEW_AGGREGATE_NAME,
            packageScopes = [CreateView::class],
        ),
        BoundedContext.Aggregate(
            name = ViewStoreService.VIEW_PREFERENCES_AGGREGATE_NAME,
            packageScopes = [SetViewPreferences::class],
        ),
    ],
)
object ViewStoreService {
    const val SERVICE_ALIAS = "view-store"
    const val SERVICE_NAME = SERVICE_ALIAS
    const val VIEW_AGGREGATE_NAME = "view"
    const val VIEW_PREFERENCES_AGGREGATE_NAME = "view_preferences"

    /**
     * The reserved owner of shared views and shared preferences. The parentheses keep it apart from every user id.
     */
    const val SHARED_OWNER_ID = "(shared)"

    /**
     * The reserved owner of stored system views: the views every user of an application reads, whatever their
     * tenant. They live under [SYSTEM_TENANT_ID] only, so every write of one is a request under
     * `…/tenant/(platform)/owner/(system)/…`; the security gateway decides who may send it.
     */
    const val SYSTEM_OWNER_ID = "(system)"

    /**
     * The one tenant stored system views live under. System views are global; each request tenant reads them beside
     * the configured ones. It is not Wow's default tenant `(0)`, the tenant of a deployment without tenants: its
     * value is CoSec's platform tenant (`Tenant.PLATFORM_TENANT_ID`), matched by value, without a dependency on
     * CoSec, so a gateway rule and a platform administrator's token name the same tenant.
     */
    const val SYSTEM_TENANT_ID = "(platform)"

    /** The request header naming the calling application; CoSec authenticates it. */
    const val APP_ID_HEADER = "CoSec-App-Id"

    /** The command header the application is carried in, the same key CoSec's message propagator uses. */
    const val APP_ID_MESSAGE_HEADER = "app_id"
}

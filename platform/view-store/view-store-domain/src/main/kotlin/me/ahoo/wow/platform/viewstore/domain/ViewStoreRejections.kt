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

package me.ahoo.wow.platform.viewstore.domain

import me.ahoo.wow.api.annotation.ErrorCode

/**
 * The codes the view store rejects a command with: the 9.x `ViewStoreErrorCodes` as V10 rejections. A rule the 9.x
 * starter checked on the path (the scope ids, the closed routes) has no code: V10 takes the identity from the
 * principal, and its routes are the Manifest's.
 */
object ViewStoreRejections {
    @ErrorCode(
        description = "The request is not a valid write: its config, title, preferences or wording.",
        status = 400
    )
    const val INVALID = "view-store.invalid"

    @ErrorCode(description = "The request carries no CoSec-App-Id.", status = 400)
    const val APP_REQUIRED = "view-store.app-required"

    @ErrorCode(
        description = "A view is saved by an owner: a user, (shared), or (system) under (platform).",
        status = 400
    )
    const val OWNER_REQUIRED = "view-store.owner-required"

    @ErrorCode(description = "The aggregate belongs to another application, which reads as none.", status = 404)
    const val NOT_FOUND = "view-store.not-found"

    @ErrorCode(description = "A system view never moves audience: it is neither shared nor claimed.", status = 403)
    const val SYSTEM_VIEW_READ_ONLY = "view-store.system-view-read-only"

    @ErrorCode(description = "A shared dashboard shows the view, which stays shared.", status = 409)
    const val REFERENCED_BY_SHARED_DASHBOARD = "view-store.referenced-by-shared-dashboard"
}

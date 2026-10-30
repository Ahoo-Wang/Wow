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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.api.caller.Caller

/**
 * The analytics catalog's roles, which a caller lists in its attribute [ROLES_ATTRIBUTE]: from the identity the CoSec
 * gateway verified and forwarded (the `cosec` profile), or, in development, from the `Wow-Roles` header (the host's
 * development callers). Its callers are of the platform's tenant, `(platform)`, and have no owner.
 */
object AnalyticsCatalogRoles {
    /** The caller attribute listing its roles, as CoSec's resolver names it. */
    const val ROLES_ATTRIBUTE: String = "roles"

    /** Manages the warehouse connections and the trusted writers, and syncs the sources; acts as an editor too. */
    const val ADMIN: String = "analytics.catalog.admin"

    /**
     * Approves: marks an analyst's source reviewed, and accepts a lower protection of a Wow source's values
     * (the privileged review).
     */
    const val APPROVER: String = "analytics.catalog.approver"

    /** An analyst: drafts, revises and reviews the sensitivity of the sources. */
    const val EDITOR: String = "analytics.catalog.editor"

    /** Reads the sources. */
    const val VIEWER: String = "analytics.catalog.viewer"

    /** The roles that read the sources. */
    @JvmField
    val READERS: Set<String> = setOf(ADMIN, APPROVER, EDITOR, VIEWER)

    /** The roles of [caller]. */
    fun rolesOf(caller: Caller): List<String> = caller.attributes[ROLES_ATTRIBUTE].orEmpty()
}

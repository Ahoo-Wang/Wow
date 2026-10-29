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

package me.ahoo.wow.viewstore.server

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.viewstore.api.ViewStoreContext

/**
 * The view store's roles, which a caller lists in its attribute [ROLES_ATTRIBUTE] (from the identity the CoSec gateway
 * forwards, or the `Wow-Roles` header in development), and its reserved owners, which a caller acts as by naming them
 * in `Wow-Scope` ([ReservedScopeCallers]).
 */
object ViewStoreRoles {
    /** The caller attribute listing its roles, as CoSec's resolver names it. */
    const val ROLES_ATTRIBUTE: String = "roles"

    /** The role that writes the owner `(shared)`'s views and preferences and the tenant's dataset wording. */
    const val SHARED_WRITER: String = "view-store.shared-writer"

    /** The role that writes the stored system views, under the tenant `(platform)`. */
    const val SYSTEM_WRITER: String = "view-store.system-writer"

    /** The `Wow-Scope` of the shared views and of the stored system views. */
    val SHARED: TargetScope = TargetScope.of(scope = Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))
    val SYSTEM: TargetScope =
        TargetScope.of(ViewStoreContext.SYSTEM_TENANT_ID, Scope(ownerId = ViewStoreContext.SYSTEM_OWNER_ID))
    private val RESERVED = setOf(SHARED, SYSTEM)

    /**
     * The reserved scope [value] names, when it names only one: the owner `(shared)` alone, or the tenant
     * `(platform)` with the owner `(system)`. Null for no header, one that does not parse (admission answers it)
     * and any other.
     */
    internal fun reservedTarget(value: String?): TargetScope? {
        val target = value?.let { runCatching { ScopeHeader.parse(it) }.getOrNull() } ?: return null
        return target.takeIf { it in RESERVED }
    }
}

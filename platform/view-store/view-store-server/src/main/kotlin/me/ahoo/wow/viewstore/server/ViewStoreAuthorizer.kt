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

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.viewstore.api.ViewStoreContext

/**
 * Who writes what in the view store (decision D92), the 9.x gateway's write rules:
 *
 * - `wow.lifecycle.TransferOwner` is never sent over HTTP: an owner changes only by sharing or claiming, which the view
 *   decides and its saga carries out.
 * - The owner `(shared)`'s writes (a shared view's save, rename, delete and claim; shared preferences) need
 *   [ViewStoreCallers.SHARED_WRITER], as the dataset wording, the whole tenant's, does.
 * - The owner `(system)`'s writes need [ViewStoreCallers.SYSTEM_WRITER].
 * - A user writes its own views; the kernel keeps it to them (decision D94).
 * - A write in a scope without an owner is refused (review R2.3-2): the kernel checks only the parts of the scope a
 *   command has (decision D189), so a tenant-level or anonymous caller, or `Wow-Scope` naming a tenant alone, would
 *   reach every owner's views and preferences, the reserved owners' among them, without the roles above.
 * - Preferences are written in the caller's own scope alone (review R2.3-3): [PreferencesTarget] derives their id
 *   from the caller's owner, as a transformer does not see the scope `Wow-Scope` targets, so a caller acting as
 *   another owner would write preferences of the wrong id. The callers of [ViewStoreCallers] act as `(shared)` with
 *   that owner as their own, so they are not affected; a CoSec administrator writes the shared preferences once the
 *   transformers see the target scope.
 */
class ViewStoreAuthorizer : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        refusal(caller, action)?.let { return Authorization.Denied(it) }
        val role = when {
            action.ref.type.name == ViewStoreContext.DATASET_WORDING -> ViewStoreCallers.SHARED_WRITER
            action.scope.ownerId == ViewStoreContext.SHARED_OWNER_ID -> ViewStoreCallers.SHARED_WRITER
            action.scope.ownerId == ViewStoreContext.SYSTEM_OWNER_ID -> ViewStoreCallers.SYSTEM_WRITER
            else -> return Authorization.Allowed
        }
        val roles = caller.attributes[ViewStoreCallers.ROLES_ATTRIBUTE].orEmpty()
        return if (role in roles) {
            Authorization.Allowed
        } else {
            Authorization.Denied("${nameOf(caller)} may not ${action.type.name}: it needs $role.")
        }
    }

    /** Why [action] is refused to [caller] whatever its roles, or null when its roles decide. */
    private fun refusal(caller: Caller, action: CommandAction): String? = when {
        action.type.name == Lifecycle.TRANSFER_OWNER -> "A view's owner changes only by sharing or claiming it."
        action.ref.type.name == ViewStoreContext.VIEW_PREFERENCES && action.scope.ownerId != caller.scope.ownerId ->
            "${nameOf(caller)} may not ${action.type.name} as ${action.scope.ownerId ?: "no owner"}: preferences are " +
                "found by their caller's own owner (${caller.scope.ownerId ?: "none"}), so they are written in its " +
                "own scope alone."

        action.ref.type.name != ViewStoreContext.DATASET_WORDING && action.scope.ownerId == null ->
            "${nameOf(caller)} may not ${action.type.name}: a write to the view store acts as an owner, a user or " +
                "${ViewStoreContext.SHARED_OWNER_ID} or ${ViewStoreContext.SYSTEM_OWNER_ID}, and this one names none."

        else -> null
    }

    private fun nameOf(caller: Caller): String = caller.subject ?: "An anonymous caller"
}

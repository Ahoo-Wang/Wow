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
 */
class ViewStoreAuthorizer : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        if (action.type.name == Lifecycle.TRANSFER_OWNER) {
            return Authorization.Denied("A view's owner changes only by sharing or claiming it.")
        }
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
            Authorization.Denied(
                "${caller.subject ?: "An anonymous caller"} may not ${action.type.name}: it needs $role."
            )
        }
    }
}

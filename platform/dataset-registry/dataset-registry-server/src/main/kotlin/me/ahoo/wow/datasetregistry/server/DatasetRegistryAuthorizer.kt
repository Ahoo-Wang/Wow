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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.datasetregistry.api.source.ApproveSensitivityChange
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * Who sends what to the dataset registry: every command changes what the platform's datasets are built on, so only
 * the registry's roles send them ([DatasetRegistryCallers]):
 *
 * - `ApproveSensitivityChange`, the privileged review of a lower protection (decision D241 ③), only an
 *   [DatasetRegistryCallers.APPROVER];
 * - every other command of the registry, and the framework's own system commands (`wow.*`), an
 *   [DatasetRegistryCallers.ADMIN].
 *
 * Where CoSec authorizes the requests itself, [policies] decides instead, by the command's type.
 */
class DatasetRegistryAuthorizer(private val policies: CommandAuthorizer? = null) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        policies?.let { return it.authorize(caller, action) }
        val needed = if (action.type.name == APPROVE) DatasetRegistryCallers.APPROVER else DatasetRegistryCallers.ADMIN
        return if (needed in DatasetRegistryCallers.rolesOf(caller)) {
            Authorization.Allowed
        } else {
            Authorization.Denied(
                "${caller.subject ?: "An anonymous caller"} may not ${action.type.name}: it needs $needed."
            )
        }
    }

    companion object {
        /** The name of the privileged review's command type (decision D55). */
        val APPROVE: String =
            "${DatasetRegistryContext.NAME}.${DatasetRegistryContext.SOURCE}.${ApproveSensitivityChange::class.java.simpleName}"
    }
}

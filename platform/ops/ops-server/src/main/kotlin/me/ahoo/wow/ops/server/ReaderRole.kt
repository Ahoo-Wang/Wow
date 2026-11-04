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

package me.ahoo.wow.ops.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.query.policy.ReadAction
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.policy.ReadAuthorizer

/**
 * Who reads the entries where the roles decide: a caller with one of [OpsRoles.READERS] reads every entry of its
 * tenant, and any other caller's read of them, a query of any model, a state read or a trace, is refused as
 * `wow.auth.forbidden`, before anything is read; trusted in-process code, which reads with no caller, is not asked.
 * Every aggregate this service serves is an entry.
 *
 * An entry is a resource of its tenant (`Ownership.TENANT`): no caller's owner restricts its reads, so the default
 * rule lets every signed-in caller of the tenant read them all, an owner-level one (a principal with no ops role)
 * as a tenant-level one. The roles are what keeps them to the operators and viewers: who reads, not which rows.
 */
class ReaderRole : ReadAuthorizer {
    override suspend fun authorize(caller: Caller, read: ReadAction): ReadAuthorization =
        if (OpsRoles.rolesOf(caller).any { it in OpsRoles.READERS }) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of(
                "${caller.subject ?: "An anonymous caller"} reads no parked entry: it needs ${OpsRoles.VIEWER} or " +
                    "${OpsRoles.OPERATOR}.",
            )
        }
}

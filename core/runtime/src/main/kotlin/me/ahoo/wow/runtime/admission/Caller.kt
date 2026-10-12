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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.Scope

/**
 * Who sends a command, as the edge authenticated it (decision D93): the facts admission takes the command's identity
 * from (design 05 §3: identity comes from the principal, never from the path or the body).
 *
 * - [tenantId] is the caller's tenant; a principal without one is in [DEFAULT_TENANT] (decision D97).
 * - [scope] is the caller's owner and space (decisions D56, D96): the scope its commands act in.
 * - [subject] names the principal, for authorizers and logs; null for an anonymous caller.
 * - [crossScope] says the caller may act in another tenant or scope by naming it ([CommandRequest.target], the
 *   `Wow-Scope` header, decision D95).
 * - [attributes] are further facts about the principal an authorizer may read (roles, policies, `abac.` attributes).
 */
data class Caller(
    val tenantId: String = DEFAULT_TENANT,
    val scope: Scope = Scope.NONE,
    val subject: String? = null,
    val crossScope: Boolean = false,
    val attributes: Map<String, List<String>> = emptyMap(),
) {
    init {
        require(tenantId.isNotBlank()) { "A caller's tenant is not blank." }
        require(subject == null || subject.isNotBlank()) { "A caller's subject, when present, is not blank." }
    }

    companion object {
        /** The tenant of a principal that has none (decision D97). */
        const val DEFAULT_TENANT: String = "(0)"

        /** An unauthenticated caller: the default tenant, no scope, no subject (decision D97). */
        @JvmField
        val ANONYMOUS: Caller = Caller()
    }
}

/**
 * The tenant and scope a caller with [Caller.crossScope] asks to act in instead of its own (decision D95; the
 * `Wow-Scope` header): [tenantId], when given, replaces the caller's tenant, and [scope] replaces the caller's scope as
 * a whole, so a part it leaves out is not checked against the aggregate (decision D94).
 */
data class TargetScope(val tenantId: String? = null, val scope: Scope = Scope.NONE) {
    init {
        require(tenantId == null || tenantId.isNotBlank()) { "A target tenant, when present, is not blank." }
    }
}

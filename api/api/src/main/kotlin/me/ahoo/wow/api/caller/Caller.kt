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

package me.ahoo.wow.api.caller

import me.ahoo.wow.api.aggregate.Scope

/**
 * Who sends a command or runs a query, as the edge authenticated it (decisions D93, D165): the facts admission takes a
 * command's identity from (design 05 §3: identity comes from the principal, never from the path or the body), and the
 * facts a query policy restricts a query by. Commands and queries share it: the edge resolves one per request.
 *
 * - [tenantId] is the caller's tenant; a principal without one is in [DEFAULT_TENANT] (decision D97).
 * - [scope] is the caller's owner and space (decisions D56, D96): the scope it acts in.
 * - [subject] is the principal's opaque id, for authorizers, logs and the [Operator] of what it commits; null for an
 *   anonymous caller.
 * - [crossScope] says the caller may act in another tenant or scope by naming it (the `Wow-Scope` header, decision
 *   D95).
 * - [attributes] are further facts about the principal an authorizer or a query policy may read (roles, policies,
 *   `abac.` attributes). They live only as long as the request: none of them is written into a fact (decision D163).
 * - [service] says the caller is a service identity (decision D193): another service of the system, authenticated by
 *   its service credential, calling for a saga of its own. Only such a caller may assert the root command a call
 *   acts for (its tenant, scope, operator and causation, the `Wow-Call` header); the edge refuses that header from
 *   any other caller. A service identity has a [subject].
 *
 * A caller is the runtime's view of one request; the [Operator] is what a commit records of it ([toOperator]).
 */
data class Caller(
    val tenantId: String = DEFAULT_TENANT,
    val scope: Scope = Scope.NONE,
    val subject: String? = null,
    val crossScope: Boolean = false,
    val attributes: Map<String, List<String>> = emptyMap(),
    val service: Boolean = false,
) {
    init {
        require(tenantId.isNotBlank()) { "A caller's tenant is not blank." }
        require(subject == null || subject.isNotBlank()) { "A caller's subject, when present, is not blank." }
        require(!service || subject != null) { "A service identity names its subject." }
    }

    /**
     * The operator a command of this caller records (decision D163): its [subject] as the id and its own [tenantId];
     * [actingElsewhere] when the command acts in another tenant or scope than the caller's own, through `Wow-Scope`.
     * Null for an anonymous caller.
     */
    fun toOperator(actingElsewhere: Boolean = false): Operator? =
        subject?.let { Operator(it, tenantId, actingElsewhere) }

    companion object {
        /** The tenant of a principal that has none (decision D97). */
        const val DEFAULT_TENANT: String = "(0)"

        /** An unauthenticated caller: the default tenant, no scope, no subject (decision D97). */
        @JvmField
        val ANONYMOUS: Caller = Caller()
    }
}

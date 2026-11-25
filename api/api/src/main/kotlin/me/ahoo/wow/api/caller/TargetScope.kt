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
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The tenant and scope a request asks to act in instead of its sender's own: [tenantId], when given, replaces the
 * sender's tenant; [scope], when given, replaces the sender's scope as a whole, so a part it leaves out is not checked
 * against the aggregate ([Scope.NONE]: replaced by none, nothing of it checked); a null [scope] keeps the sender's own
 * (for a command a saga derives, the commit's). A target that names only a tenant therefore acts in that tenant with
 * the sender's own scope, on every path: an in-process client, an HTTP client and a saga alike.
 *
 * Only a caller with [me.ahoo.wow.api.caller.Caller.crossScope] may act in another tenant or scope. On the wire it is
 * the `Wow-Scope` header ([me.ahoo.wow.api.wire.ScopeHeader]), which always names a scope: an owner or a space it
 * leaves out is none, so a header read gives a [scope] that is never null, and a client writes the scope it acts in.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class TargetScope @InternalWowApi constructor(val tenantId: String? = null, val scope: Scope? = null) {
    init {
        require(tenantId == null || tenantId.isNotBlank()) { "A target tenant, when present, is not blank." }
    }

    /** The scope this target acts in for a sender whose own is [own]: [scope], else [own]. */
    fun scopeFor(own: Scope): Scope = scope ?: own

    override fun equals(other: Any?): Boolean =
        this === other || other is TargetScope && tenantId == other.tenantId && scope == other.scope

    override fun hashCode(): Int = 31 * tenantId.hashCode() + scope.hashCode()

    override fun toString(): String = "TargetScope(tenantId=$tenantId, scope=$scope)"

    companion object {
        /**
         * The target [tenantId], when given, and [scope], when given (null keeps the sender's own); parameters added
         * later come last, with a default.
         */
        @JvmStatic
        @JvmOverloads
        fun of(tenantId: String? = null, scope: Scope? = null): TargetScope = TargetScope(tenantId, scope)
    }
}

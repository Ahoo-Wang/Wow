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
 * The tenant and scope a caller with [me.ahoo.wow.api.caller.Caller.crossScope] asks to act in instead of its own
 * (the `Wow-Scope` header): [tenantId], when given, replaces the caller's tenant, and [scope] replaces
 * the caller's scope as a whole, so a part it leaves out is not checked against the aggregate.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class TargetScope @InternalWowApi constructor(val tenantId: String? = null, val scope: Scope = Scope.NONE) {
    init {
        require(tenantId == null || tenantId.isNotBlank()) { "A target tenant, when present, is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TargetScope && tenantId == other.tenantId && scope == other.scope

    override fun hashCode(): Int = 31 * tenantId.hashCode() + scope.hashCode()

    override fun toString(): String = "TargetScope(tenantId=$tenantId, scope=$scope)"

    companion object {
        /** The target [tenantId], when given, and [scope]; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(tenantId: String? = null, scope: Scope = Scope.NONE): TargetScope = TargetScope(tenantId, scope)
    }
}

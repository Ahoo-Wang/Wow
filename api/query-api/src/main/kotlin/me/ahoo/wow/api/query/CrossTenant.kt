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

package me.ahoo.wow.api.query

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The tenants a cross-tenant query reads: every tenant when [tenantIds] is null ([ALL]), else those.
 * Only the HTTP edge states one, for a caller that may act across scopes and names `Wow-Scope: tenant=*` or
 * `tenant=("t1" "t2")` while `wow.query.allow-cross-tenant` is on; trusted in-process code that must read every
 * tenant uses `QueryApi.trusted()`. A type whose tenant is fixed is still read in that tenant alone, and every
 * cross-tenant query is audited, whether or not a `QueryObserver` audits.
 */
class CrossTenant @InternalWowApi constructor(val tenantIds: Set<String>? = null) {
    init {
        require(tenantIds == null || tenantIds.isNotEmpty()) { "A cross-tenant query names its tenants, or every one." }
        require(tenantIds.orEmpty().none { it.isBlank() }) { "A cross-tenant query names no blank tenant." }
    }

    /** Whether the query reads [tenantId]. */
    fun covers(tenantId: String): Boolean = tenantIds?.contains(tenantId) ?: true

    companion object {
        /** Every tenant: `tenant=*`. */
        @JvmField
        val ALL: CrossTenant = CrossTenant()

        /** A [CrossTenant] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(tenantIds: Set<String>? = null): CrossTenant = CrossTenant(tenantIds)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CrossTenant && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CrossTenant(tenantIds=$tenantIds)"

    private fun valueFields(): List<Any?> = listOf(tenantIds)
}

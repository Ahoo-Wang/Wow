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

package me.ahoo.wow.models.common

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller

/**
 * An [id] within a tenant, [tenantId].
 *
 * 9.x extended `TenantId` and `IdCapable` of 9.x wow-api, which the 10.x wow-api leaves out, so this declares both
 * properties itself.
 */
interface TenantAwareIdCapable<ID : Any> {
    val id: ID
    val tenantId: String
}

typealias StringTenantAwareIdCapable = TenantAwareIdCapable<String>

/** An [id] within [tenantId], the default tenant ([Caller.DEFAULT_TENANT]) unless given. */
class TenantAwareId<ID : Any> @InternalWowApi constructor(
    override val id: ID,
    override val tenantId: String = Caller.DEFAULT_TENANT,
) : TenantAwareIdCapable<ID> {
    override fun equals(other: Any?): Boolean =
        this === other || other is TenantAwareId<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TenantAwareId(id=$id, tenantId=$tenantId)"

    private fun valueFields(): List<Any?> = listOf(id, tenantId)

    companion object {
        /** A TenantAwareId of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun <ID : Any> of(
            id: ID,
            tenantId: String = Caller.DEFAULT_TENANT
        ): TenantAwareId<ID> = TenantAwareId(id, tenantId)
    }
}

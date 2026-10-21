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
data class TenantAwareId<ID : Any>(
    override val id: ID,
    override val tenantId: String = Caller.DEFAULT_TENANT,
) : TenantAwareIdCapable<ID>

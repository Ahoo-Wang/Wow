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

import me.ahoo.wow.api.modeling.IdCapable
import me.ahoo.wow.api.modeling.TenantId

interface TenantAwareIdCapable<ID : Any> : TenantId, IdCapable<ID>

typealias StringTenantAwareIdCapable = TenantAwareIdCapable<String>

data class TenantAwareId<ID : Any>(
    override val id: ID,
    override val tenantId: String = TenantId.DEFAULT_TENANT_ID
) : TenantAwareIdCapable<ID>

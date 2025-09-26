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

package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order

@Order(4)
@CommandRoute(
    method = CommandRoute.Method.POST,
    appendIdPath = CommandRoute.AppendPath.ALWAYS,
    appendTenantPath = CommandRoute.AppendPath.ALWAYS,
    appendOwnerPath = CommandRoute.AppendPath.ALWAYS,
    action = "{id}/{customerId}/{mockEnum}"
)
data class MockVariableCommand(
    @field:CommandRoute.PathVariable
    val tenantId: String,
    @field:CommandRoute.PathVariable
    val ownerId: String,
    @field:CommandRoute.PathVariable
    val id: String,
    @field:CommandRoute.PathVariable
    val customerId: Int,
    @field:CommandRoute.PathVariable
    val mockEnum: MockEnum,
    @field:CommandRoute.HeaderVariable
    val headerParameter: String,
    @field:CommandRoute.HeaderVariable
    val headerEnumParameter: MockEnum
) {
    enum class MockEnum {
        First, Second, Third
    }
}

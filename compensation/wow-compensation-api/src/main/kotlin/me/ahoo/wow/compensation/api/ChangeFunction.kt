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

package me.ahoo.wow.compensation.api

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionKind

@Order(6)
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeFunction(
    @field:CommandRoute.PathVariable
    override val id: String,
    override val functionKind: FunctionKind,
    override val processorName: String,
    override val contextName: String,
    override val name: String
) : Identifier, FunctionInfo

data class FunctionChanged(
    override val name: String,
    override val processorName: String,
    override val contextName: String,
    override val functionKind: FunctionKind
) : FunctionInfo

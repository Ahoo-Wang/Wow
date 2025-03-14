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

package me.ahoo.wow.models.tree.command

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.models.tree.Flat

@AllowCreate
@CommandRoute(
    method = CommandRoute.Method.POST,
    appendIdPath = CommandRoute.AppendPath.ALWAYS,
    action = "",
    summary = "Create tree node",
    description = "Id is the tenant ID."
)
interface Create<E : Created> : Named {
    val parentCode: String

    fun toEvent(code: String, sortId: Int): E
}

interface Created : Flat

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

@AllowCreate
@CommandRoute(
    method = CommandRoute.Method.POST,
    appendIdPath = CommandRoute.AppendPath.ALWAYS,
    action = "",
    summary = "添加树节点",
    description = "Id 为租户ID."
)
data class CreateCategory(override val name: String, override val parentCode: String) : Create<CategoryCreated> {

    override fun toEvent(code: String, sortId: Int): CategoryCreated {
        return CategoryCreated(name = name, code = code, sortId = sortId)
    }
}

data class CategoryCreated(
    override val name: String,
    override val code: String,
    override val sortId: Int
) : Created

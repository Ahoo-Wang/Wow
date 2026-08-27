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

import jakarta.validation.constraints.NotBlank
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.models.tree.Flat

@Summary("删除树节点")
@Description("Id 为租户ID.")
@CommandRoute(
    method = CommandRoute.Method.DELETE,
    appendIdPath = CommandRoute.AppendPath.ALWAYS,
    action = "{code}"
)
data class DeleteCategory(
    @field:NotBlank
    @CommandRoute.PathVariable
    override val code: String
) : Delete<CategoryDeleted> {
    override fun toEvent(previous: Flat): CategoryDeleted {
        return CategoryDeleted(code = code)
    }
}

data class CategoryDeleted(override val code: String) : Deleted

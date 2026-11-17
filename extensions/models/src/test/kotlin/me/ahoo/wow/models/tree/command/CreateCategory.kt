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
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary

@Summary("Create a category")
@Description("The aggregate id is the tenant's id.")
@AllowCreate
data class CreateCategory(
    /** The domain's own constraint, with its own message: the library declares none on a create command's name. */
    @get:NotBlank(message = NAME_REQUIRED)
    override val name: String,
    override val parentCode: String,
) : Create<CategoryCreated> {
    override fun toEvent(code: String, sortId: Int): CategoryCreated =
        CategoryCreated(name = name, code = code, sortId = sortId)

    companion object {
        const val NAME_REQUIRED = "A category needs a name."
    }
}

data class CategoryCreated(
    override val name: String,
    override val code: String,
    override val sortId: Int,
) : Created

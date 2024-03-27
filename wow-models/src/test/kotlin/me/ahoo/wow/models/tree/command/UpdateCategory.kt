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

data class UpdateCategory(
    override val name: String,
    override val code: String,
    override val sortId: Int
) : Update {
    override fun <E : Updated> toEvent(): E {
        @Suppress("UNCHECKED_CAST")
        return CategoryUpdated(name = name, code = code, sortId = sortId) as E
    }
}

data class CategoryUpdated(
    override val name: String,
    override val code: String,
    override val sortId: Int
) : Updated

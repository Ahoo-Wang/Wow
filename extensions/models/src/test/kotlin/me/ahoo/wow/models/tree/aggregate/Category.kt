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

package me.ahoo.wow.models.tree.aggregate

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.models.tree.command.CreateCategory
import me.ahoo.wow.models.tree.command.DeleteCategory
import me.ahoo.wow.models.tree.command.MoveCategory
import me.ahoo.wow.models.tree.command.UpdateCategory

/**
 * A tenant's category tree: every handler is the library's [Tree]'s, bound with the concrete commands. The verify
 * hooks add a rule of the domain, and one of the tree's codes is declared to the Manifest with its status.
 */
@AggregateRoot(ownership = Ownership.TENANT)
class Category(state: CategoryState) :
    Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
    override fun maxLevel(): Int = MAX_LEVEL

    override suspend fun verifyCreate(command: CreateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    override suspend fun verifyUpdate(command: UpdateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    private fun reserved(name: String): Rejection? =
        if (name.equals(RESERVED_NAME, ignoreCase = true)) {
            Rejection.of(NAME_RESERVED, "The category name [$name] is reserved.")
        } else {
            null
        }

    companion object {
        const val MAX_LEVEL = 3
        const val RESERVED_NAME = "uncategorized"

        @ErrorCode(description = "The category name is reserved.", status = 409)
        const val NAME_RESERVED = "category.name-reserved"

        /** [TreeRejections.PARENT_NOT_FOUND], declared to the Manifest with its status. */
        @ErrorCode(description = "The parent category does not exist.", status = 404)
        const val PARENT_NOT_FOUND = "tree.parent-not-found"
    }
}

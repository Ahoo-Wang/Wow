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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.models.tree.command.CreateCategory
import me.ahoo.wow.models.tree.command.DeleteCategory
import me.ahoo.wow.models.tree.command.MoveCategory
import me.ahoo.wow.models.tree.command.UpdateCategory

@AggregateRoot
class Category(state: CategoryState) :
    Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
    override fun generateCode(): String {
        return GlobalIdGenerator.generateAsString()
    }

    override fun maxLevel(): Int {
        return 3
    }
}
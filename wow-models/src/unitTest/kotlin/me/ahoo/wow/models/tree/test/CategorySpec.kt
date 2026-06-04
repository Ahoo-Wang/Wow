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

package me.ahoo.wow.models.tree.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.aggregate.Category
import me.ahoo.wow.models.tree.aggregate.CategoryState
import me.ahoo.wow.models.tree.command.CategoryCreated
import me.ahoo.wow.models.tree.command.CategoryDeleted
import me.ahoo.wow.models.tree.command.CategoryMoved
import me.ahoo.wow.models.tree.command.CategoryUpdated
import me.ahoo.wow.models.tree.command.CreateCategory
import me.ahoo.wow.models.tree.command.DeleteCategory
import me.ahoo.wow.models.tree.command.MoveCategory
import me.ahoo.wow.models.tree.command.UpdateCategory
import me.ahoo.wow.test.AggregateSpec

class CategorySpec : AggregateSpec<Category, CategoryState>({
    on {
        val createCategory = CreateCategory("name", "")
        whenCommand(createCategory) {
            expectNoError()
            expectEventType(CategoryCreated::class)
            expectEventBody<CategoryCreated> {
                name.assert().isEqualTo(createCategory.name)
                level.assert().isOne()
                sortId.assert().isZero()
            }
            expect {
                exchange.getCommandResult().assert().hasSize(1)
                val result = exchange.getCommandResult<String>(Flat::code.name)
                result.assert().isNotNull()
            }
        }
    }
    on {
        name("ParentNotFound")
        whenCommand(CreateCategory("name", "parent")) {
            expectErrorType(IllegalStateException::class)
        }
    }
    on {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        givenEvent(arrayOf(l1Category, l2Category)) {
            whenCommand(CreateCategory("name", l1Category.code)) {
                expectNoError()
                expectEventType(CategoryCreated::class)
                expectEventBody<CategoryCreated> {
                    name.assert().isEqualTo("name")
                    l1Category.isDirectChild(this).assert().isTrue()
                    level.assert().isEqualTo(2)
                    sortId.assert().isEqualTo(l2Category.sortId + 1)
                }
            }
        }
    }
    on {
        name("Max")
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        val l3Category = CategoryCreated(name = "l3", code = "l1-l2-l3", sortId = 0)
        givenEvent(arrayOf(l1Category, l2Category, l3Category)) {
            whenCommand((CreateCategory("name", l3Category.code))) {
                expectErrorType(IllegalArgumentException::class)
            }
        }
    }
    on {
        val category = CategoryCreated(name = "parent", code = "parent", sortId = 0)
        givenEvent(category) {
            whenCommand(DeleteCategory(category.code)) {
                expectNoError()
                expectEventType(CategoryDeleted::class)
                expectEventBody<CategoryDeleted> {
                    code.assert().isEqualTo(category.code)
                }
                expectState {
                    children.assert().isEmpty()
                }
            }
        }
    }
    on {
        name("NotFound")
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        givenEvent(l1Category) {
            whenCommand(DeleteCategory("code")) {
                expectErrorType(IllegalStateException::class)
            }
        }
    }
    on {
        name("HasChild")
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        givenEvent(arrayOf(l1Category, l2Category)) {
            whenCommand(DeleteCategory(l1Category.code)) {
                expectErrorType(IllegalStateException::class)
            }
        }
    }

    on {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val updateCategory = UpdateCategory(name = "name", code = l1Category.code)
        givenEvent(l1Category) {
            whenCommand(updateCategory) {
                expectNoError()
                expectEventType(CategoryUpdated::class)
                expectEventBody<CategoryUpdated> {
                    name.assert().isEqualTo(updateCategory.name)
                    code.assert().isEqualTo(updateCategory.code)
                }
            }
        }
    }
    on {
        name("NotFound")
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        givenEvent(l1Category) {
            whenCommand(UpdateCategory(name = "name", code = "notFound")) {
                expectErrorType(IllegalStateException::class)
            }
        }
    }
    on {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l11Category = CategoryCreated(name = "l11", code = "l11", sortId = 0)
        val moveCategory = MoveCategory(listOf("l11", "l1"))
        givenEvent(arrayOf(l1Category, l11Category)) {
            whenCommand(moveCategory) {
                expectNoError()
                expectEventType(CategoryMoved::class)
                expectEventBody<CategoryMoved> {
                    codes.assert().contains("l11", "l1")
                }
                expectState {
                    children.map { it.code }.assert().contains("l11", "l1")
                }
            }
        }
    }
})

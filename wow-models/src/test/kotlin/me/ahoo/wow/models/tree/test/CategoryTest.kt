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
import me.ahoo.wow.test.aggregate.`when`
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test

class CategoryTest {
    var data: String = "data"
        private set

    @Test
    fun onCreate() {
        val command = CreateCategory("name", "")
        aggregateVerifier<Category, CategoryState>()
            .`when`(command)
            .expectNoError()
            .expectEventType(CategoryCreated::class.java)
            .expectEventBody<CategoryCreated> {
                name.assert().isEqualTo(command.name)
                level.assert().isOne()
                sortId.assert().isZero()
            }
            .expect {
                exchange.getCommandResult().assert().hasSize(1)
                val result = exchange.getCommandResult<String>(Flat::code.name)
                result.assert().isNotNull()
            }
            .verify()
    }

    @Test
    fun onCreateIfNoParent() {
        aggregateVerifier<Category, CategoryState>()
            .`when`(CreateCategory("name", "parent"))
            .expectErrorType(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun onCreateIfParent() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category, l2Category)
            .`when`(CreateCategory("name", l1Category.code))
            .expectNoError()
            .expectEventType(CategoryCreated::class.java)
            .expectEventBody<CategoryCreated> {
                name.assert().isEqualTo("name")
                l1Category.isDirectChild(this).assert().isTrue()
                level.assert().isEqualTo(2)
                sortId.assert().isEqualTo(l2Category.sortId + 1)
            }
            .verify()
    }

    @Test
    fun onCreateIfMax() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        val l3Category = CategoryCreated(name = "l3", code = "l1-l2-l3", sortId = 0)
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category, l2Category, l3Category)
            .`when`(CreateCategory("name", l3Category.code))
            .expectErrorType(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun onDelete() {
        val category = CategoryCreated(name = "parent", code = "parent", sortId = 0)
        aggregateVerifier<Category, CategoryState>()
            .given(category)
            .`when`(DeleteCategory(category.code))
            .expectNoError()
            .expectEventType(CategoryDeleted::class.java)
            .expectEventBody<CategoryDeleted> {
                code.assert().isEqualTo(category.code)
            }
            .expectState {
                children.assert().isEmpty()
            }
            .verify()
    }

    @Test
    fun onDeleteIfNoFound() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category)
            .`when`(DeleteCategory("code"))
            .expectErrorType(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun onDeleteIfHasChild() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l2Category = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category, l2Category)
            .`when`(DeleteCategory(l1Category.code))
            .expectErrorType(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun onUpdate() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val command = UpdateCategory(name = "name", code = l1Category.code)
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category)
            .`when`(command)
            .expectEventType(CategoryUpdated::class.java)
            .expectEventBody<CategoryUpdated> {
                name.assert().isEqualTo(command.name)
                code.assert().isEqualTo(command.code)
            }
            .verify()
    }

    @Test
    fun onUpdateIfNotFound() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val command = UpdateCategory(name = "name", code = "notFound")
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category)
            .`when`(command)
            .expectErrorType(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun onMove() {
        val l1Category = CategoryCreated(name = "l1", code = "l1", sortId = 0)
        val l11Category = CategoryCreated(name = "l11", code = "l11", sortId = 0)
        val command = MoveCategory(listOf("l11", "l1"))
        aggregateVerifier<Category, CategoryState>()
            .given(l1Category, l11Category)
            .`when`(command)
            .expectEventType(CategoryMoved::class.java)
            .expectEventBody<CategoryMoved> {
                codes.assert().contains("l11", "l1")
            }
            .expectState {
                children.map { it.code }.assert().contains("l11", "l1")
            }
            .verify()
    }
}

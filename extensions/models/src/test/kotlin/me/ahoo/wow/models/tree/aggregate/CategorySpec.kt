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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.models.tree.CategoryContext
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.FlatCategory
import me.ahoo.wow.models.tree.command.CategoryCreated
import me.ahoo.wow.models.tree.command.CategoryDeleted
import me.ahoo.wow.models.tree.command.CategoryMoved
import me.ahoo.wow.models.tree.command.CategoryUpdated
import me.ahoo.wow.models.tree.command.CreateCategory
import me.ahoo.wow.models.tree.command.DeleteCategory
import me.ahoo.wow.models.tree.command.MoveCategory
import me.ahoo.wow.models.tree.command.UpdateCategory
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The library's [Tree] through a domain's subclass, [Category], on the kernel (decision D187). */
class CategorySpec {
    private val model = categoryModel(CategoryContext.NAME)

    private val l1 = CategoryCreated(name = "l1", code = "l1", sortId = 0)
    private val l11 = CategoryCreated(name = "l11", code = "l11", sortId = 1)
    private val l2 = CategoryCreated(name = "l2", code = "l1-l2", sortId = 0)
    private val l3 = CategoryCreated(name = "l3", code = "l1-l2-l3", sortId = 0)

    @Test
    fun `a first node should be created at the root and answer its code`() = aggregateSpec(model) {
        whenCommand(CreateCategory("name", "")) {
            // The decision's first global id, `id-1`, without the delimiter of the tree's codes.
            expectEvents(CategoryCreated(name = "name", code = "id1", sortId = 0))
            expectEvent<CategoryCreated> { level.assert().isOne() }
            (outcome as CommandOutcome.Committed).results.assert().isEqualTo(mapOf(Flat::code.name to "id1"))
            expectState {
                id.assert().isEqualTo("aggregate-1")
                children.assert().containsExactly(FlatCategory("name", "id1", 0))
            }
        }
        whenCommand(CreateCategory("second", "")) {
            expectEvents(CategoryCreated(name = "second", code = "id2", sortId = 1))
        }
    }

    @Test
    fun `a node should be created under its parent, after its siblings`() = aggregateSpec(model) {
        given(l1, l2)
        whenCommand(CreateCategory("name", l1.code)) {
            expectEvent<CategoryCreated> {
                code.assert().isEqualTo("l1-id1")
                l1.isDirectChild(this).assert().isTrue()
                level.assert().isEqualTo(2)
                sortId.assert().isEqualTo(l2.sortId + 1)
            }
            expectState { children.map { it.code }.assert().containsExactly("l1", "l1-l2", "l1-id1") }
        }
    }

    @Test
    fun `a node should not be created under a parent that does not exist`() = aggregateSpec(model) {
        whenCommand(CreateCategory("name", "parent")) {
            expectRejected(TreeRejections.PARENT_NOT_FOUND) {
                message.assert().isEqualTo("Parent node not found. parentCode:parent.")
                extensions.assert().isEqualTo(mapOf("parentCode" to "parent"))
            }
        }
    }

    @Test
    fun `a node should not be created below the maximum level`() = aggregateSpec(model) {
        given(l1, l2, l3)
        whenCommand(CreateCategory("name", l3.code)) {
            expectRejected(TreeRejections.MAX_LEVEL_EXCEEDED) {
                extensions.assert().isEqualTo(mapOf("level" to 4, "maxLevel" to Category.MAX_LEVEL))
            }
        }
    }

    @Test
    fun `the domain's verify hooks should reject what the tree's rules allow`() = aggregateSpec(model) {
        whenCommand(CreateCategory(Category.RESERVED_NAME, "")) { expectRejected(Category.NAME_RESERVED) }
        given(l1)
        whenCommand(UpdateCategory(name = "Uncategorized", code = l1.code)) { expectRejected(Category.NAME_RESERVED) }
    }

    @Test
    fun `a node should be updated in place`() = aggregateSpec(model) {
        given(l1, l11)
        whenCommand(UpdateCategory(name = "renamed", code = l11.code)) {
            expectEvents(CategoryUpdated(name = "renamed", code = l11.code, sortId = l11.sortId))
            expectState {
                children.assert().containsExactly(FlatCategory("l1", "l1", 0), FlatCategory("renamed", "l11", 1))
            }
        }
        whenCommand(UpdateCategory(name = "name", code = "notFound")) {
            expectRejected(TreeRejections.NODE_NOT_FOUND) {
                message.assert().isEqualTo("Tree node not found. code:notFound")
                extensions.assert().isEqualTo(mapOf("code" to "notFound"))
            }
        }
    }

    @Test
    fun `a node without children should be deleted`() = aggregateSpec(model) {
        given(l1, l2)
        whenCommand(DeleteCategory(l1.code)) {
            expectRejected(TreeRejections.NODE_HAS_CHILDREN) {
                message.assert().isEqualTo("Tree node has children. code:l1")
            }
        }
        whenCommand(DeleteCategory(l2.code)) {
            expectEvents(CategoryDeleted(code = l2.code))
            expectState { children.map { it.code }.assert().containsExactly(l1.code) }
        }
        whenCommand(DeleteCategory(l1.code)) {
            expectEvents(CategoryDeleted(code = l1.code))
            expectState { children.assert().isEmpty() }
        }
        whenCommand(DeleteCategory("code")) { expectRejected(TreeRejections.NODE_NOT_FOUND) }
    }

    @Test
    fun `sibling nodes should be ordered as moved`() = aggregateSpec(model) {
        given(l1, l11, l2)
        whenCommand(MoveCategory(listOf("l11", "l1"))) {
            expectEvents(CategoryMoved(codes = listOf("l11", "l1")))
            expectState {
                children.map { it.code to it.sortId }.assert()
                    .containsExactly("l11" to 0, "l1" to 1, "l1-l2" to 0)
            }
        }
    }

    @Test
    fun `only existing siblings should be moved, each once`() = aggregateSpec(model) {
        given(l1, l11, l2)
        listOf(
            emptyList(),
            listOf("l1", "unknown"),
            listOf("l1", "l1"),
            listOf("l1", "l1-l2"),
        ).forEach { codes ->
            whenCommand(MoveCategory(codes)) {
                expectRejected(TreeRejections.INVALID_MOVE) { extensions.assert().isEqualTo(mapOf("codes" to codes)) }
            }
        }
    }

    @Test
    fun `the tree's state should come back from its snapshot`() = aggregateSpec(model) {
        given(l1, l11, l2)
        whenCommand(MoveCategory(listOf("l11", "l1"))) {
            expectState {
                val codec = EventCodec()
                val restored = codec.decodeState(model, codec.stateText(this))
                restored.id.assert().isEqualTo(id)
                restored.children.toList().assert().isEqualTo(children.toList())
            }
        }
    }
}

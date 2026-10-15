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

package me.ahoo.wow.models.tree

import me.ahoo.test.asserts.assert
import me.ahoo.wow.models.tree.TreeCoded.Companion.childCodePrefix
import me.ahoo.wow.models.tree.TreeCoded.Companion.isDirectChild
import me.ahoo.wow.models.tree.TreeCoded.Companion.isRootNode
import me.ahoo.wow.models.tree.TreeCoded.Companion.nodeLevel
import me.ahoo.wow.models.tree.TreeCoded.Companion.treeCode
import org.junit.jupiter.api.Test

class TreeCodedTest {
    @Test
    fun `a code should know its level and the root`() {
        ROOT_CODE.isRootNode().assert().isTrue()
        ROOT_CODE.nodeLevel().assert().isZero()
        "l1-l2-l3".nodeLevel().assert().isEqualTo(3)
        FlatCategory("root", ROOT_CODE, 0).isRoot().assert().isTrue()
        FlatCategory("l2", "l1-l2", 0).level.assert().isEqualTo(2)
    }

    @Test
    fun `a child's code should extend its parent's`() {
        ROOT_CODE.childCodePrefix().assert().isEmpty()
        ROOT_CODE.treeCode("l1").assert().isEqualTo("l1")
        "l1".childCodePrefix().assert().isEqualTo("l1-")
        "l1".treeCode("l2").assert().isEqualTo("l1-l2")
    }

    @Test
    fun `only the next level under a code should be its direct children`() {
        ROOT_CODE.isDirectChild("l1").assert().isTrue()
        ROOT_CODE.isDirectChild("l1-l2").assert().isFalse()
        "l1".isDirectChild("l1").assert().isFalse()
        "l1".isDirectChild("l1-l2").assert().isTrue()
        "l1".isDirectChild("l1-l2-l3").assert().isFalse()
        "l1".isDirectChild("l11-l2").assert().isFalse()
    }

    @Test
    fun `nodes should order by level, then sort id, then code`() {
        val nodes = listOf(
            FlatCategory("b", "l1-b", 0),
            FlatCategory("z", "z", 1),
            FlatCategory("a", "a", 1),
            FlatCategory("y", "y", 0),
        )
        nodes.sorted().map { it.code }.assert().containsExactly("y", "a", "z", "l1-b")
        FlatCategory("y", "y", 0).withSortId(2).sortId.assert().isEqualTo(2)
    }
}

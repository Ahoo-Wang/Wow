package me.ahoo.wow.models.tree

import me.ahoo.test.asserts.assert
import me.ahoo.wow.models.tree.TreeCoded.Companion.parentCode
import me.ahoo.wow.models.tree.Trees.toTree
import org.junit.jupiter.api.Test

class TreesTest {

    @Test
    fun toTree() {
        val l1 = FlatCategory("l1", "l1", 0)
        val l11 = FlatCategory("l11", "l11", 1)
        val l111 = FlatCategory("l111", "l111", 2)
        val l2 = FlatCategory("l2", "l1-l2", 0)
        val l3 = FlatCategory("l3", "l1-l2-l3", 0)

        val tree = listOf(l1, l11, l111, l2, l3).toTree(LeafCategory.ROOT, FlatCategory::toLeaf)
        tree.children.size.assert().isEqualTo(3)
        tree.children.first().children.first().code.assert().isEqualTo(l2.code)
        tree.children.first().children.first().children.first().code.assert().isEqualTo(l3.code)
    }

    @Test
    fun parentCode() {
        ROOT_CODE.parentCode().assert().isEqualTo(ROOT_CODE)
        "l1-l2-l3".parentCode().assert().isEqualTo("l1-l2")
    }
}

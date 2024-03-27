package me.ahoo.wow.models.tree

import me.ahoo.wow.models.tree.Trees.toTree
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(tree.children.size, equalTo(3))
        assertThat(tree.children.first().children.first().code, equalTo(l2.code))
        assertThat(tree.children.first().children.first().children.first().code, equalTo(l3.code))
    }
}

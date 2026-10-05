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

package me.ahoo.wow.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.Ordered
import me.ahoo.wow.api.annotation.Order
import org.junit.jupiter.api.Test

class SortedByOrderTest {

    @Test
    fun `should sort by current order values including default order`() {
        val ordered = OrderedFoundationItem("ordered", Order(value = 1))
        val annotated = AnnotatedFoundationItem()
        val unordered = UnorderedFoundationItem()

        listOf(unordered, annotated, ordered).sortedByOrder().map { it.label }.assert()
            .isEqualTo(listOf("unordered", "ordered", "annotated"))
    }

    @Test
    fun `should honor before dependencies that contradict value sorting`() {
        val sorted = listOf(BeforeDependencyTargetItem(), BeforeDependencySourceItem()).sortedByOrder()

        sorted.map { it.label }.assert().isEqualTo(listOf("source-before-target", "target"))
    }

    @Test
    fun `should honor after dependencies that contradict value sorting`() {
        val sorted = listOf(AfterDependencySourceItem(), AfterDependencyTargetItem()).sortedByOrder()

        sorted.map { it.label }.assert().isEqualTo(listOf("target", "source-after-target"))
    }

    /** B13: a chain of `after` constraints is satisfied as a whole, not one move at a time. */
    @Test
    fun `should satisfy a chain of after dependencies`() {
        val sorted = listOf(ChainHead(), ChainMiddle(), ChainTail()).sortedByOrder()

        sorted.map { it.label }.assert().isEqualTo(listOf("tail", "middle", "head"))
    }

    @Test
    fun `should keep value order and input order among unconstrained items`() {
        val sorted = listOf(
            OrderedFoundationItem("b", Order(value = 1)),
            OrderedFoundationItem("a", Order(value = 1)),
            OrderedFoundationItem("first", Order(value = 0)),
        ).sortedByOrder()

        sorted.map { it.label }.assert().isEqualTo(listOf("first", "b", "a"))
    }

    @Test
    fun `should ignore dependencies on items that are not present`() {
        listOf(AfterDependencySourceItem()).sortedByOrder().map { it.label }.assert()
            .isEqualTo(listOf("source-after-target"))
    }

    @Test
    fun `should fail fast on a dependency cycle and name it`() {
        assertThrownBy<IllegalStateException> {
            listOf(CycleFirst(), CycleSecond(), UnorderedFoundationItem()).sortedByOrder()
        }.hasMessageContaining(CycleFirst::class.java.name)
            .hasMessageContaining(CycleSecond::class.java.name)
    }

    @Test
    fun `should sort class references by order annotations`() {
        listOf(ClassOrderLast::class, ClassOrderFirst::class).sortedByOrder().assert()
            .isEqualTo(listOf(ClassOrderFirst::class, ClassOrderLast::class))
        listOf(ClassOrderLast::class.java, ClassOrderFirst::class.java).sortedByOrder().assert()
            .isEqualTo(listOf(ClassOrderFirst::class.java, ClassOrderLast::class.java))
    }

    @Test
    fun `should sort annotated methods by order annotations`() {
        val sorted = listOf(
            OrderedMethods::class.java.getDeclaredMethod("last"),
            OrderedMethods::class.java.getDeclaredMethod("first")
        ).sortedByOrder()

        sorted.map { it.name }.assert().isEqualTo(listOf("first", "last"))
    }
}

private interface FoundationOrderLabel {
    val label: String
}

private class OrderedFoundationItem(
    override val label: String,
    override val order: Order
) : Ordered,
    FoundationOrderLabel

@Order(2)
private class AnnotatedFoundationItem : FoundationOrderLabel {
    override val label: String = "annotated"
}

private class UnorderedFoundationItem : FoundationOrderLabel {
    override val label: String = "unordered"
}

@Order(value = 300, before = [BeforeDependencyTargetItem::class])
private class BeforeDependencySourceItem : FoundationOrderLabel {
    override val label: String = "source-before-target"
}

@Order(100)
private class BeforeDependencyTargetItem : FoundationOrderLabel {
    override val label: String = "target"
}

@Order(300)
private class AfterDependencyTargetItem : FoundationOrderLabel {
    override val label: String = "target"
}

@Order(value = 100, after = [AfterDependencyTargetItem::class])
private class AfterDependencySourceItem : FoundationOrderLabel {
    override val label: String = "source-after-target"
}

@Order(1)
private class ClassOrderFirst

@Order(2)
private class ClassOrderLast

private class OrderedMethods {
    @Order(2)
    fun last() = Unit

    @Order(1)
    fun first() = Unit
}

@Order(value = 0, after = [ChainMiddle::class])
private class ChainHead : FoundationOrderLabel {
    override val label: String = "head"
}

@Order(value = 1, after = [ChainTail::class])
private class ChainMiddle : FoundationOrderLabel {
    override val label: String = "middle"
}

@Order(value = 2)
private class ChainTail : FoundationOrderLabel {
    override val label: String = "tail"
}

@Order(before = [CycleSecond::class])
private class CycleFirst : FoundationOrderLabel {
    override val label: String = "cycle-first"
}

@Order(before = [CycleFirst::class])
private class CycleSecond : FoundationOrderLabel {
    override val label: String = "cycle-second"
}

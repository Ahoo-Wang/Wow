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

package me.ahoo.wow.tck.values

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.time.Duration

/** The guard itself: it passes a class whose equals compares every field, and names the field another leaves out. */
class ValueSemanticsTest {
    enum class Color { RED, BLUE }

    sealed interface Shape {
        data class Circle(val radius: Int) : Shape

        object Dot : Shape
    }

    class Complete(
        val name: String,
        val count: Int,
        val tags: List<String>,
        val color: Color,
        val shape: Shape,
        val timeout: Duration,
        val nested: Complete?,
        val cause: Throwable?,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || other is Complete && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        private fun valueFields(): List<Any?> = listOf(name, count, tags, color, shape, timeout, nested, cause)
    }

    class Forgetful(val name: String, val count: Int, val tags: List<String>) {
        override fun equals(other: Any?): Boolean =
            this === other || other is Forgetful && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        // `tags` was added later and left out.
        private fun valueFields(): List<Any?> = listOf(name, count)
    }

    class Cached(val name: String) {
        val length: Int by lazy { name.length }

        override fun equals(other: Any?): Boolean = this === other || other is Cached && name == other.name

        override fun hashCode(): Int = name.hashCode()
    }

    @Test
    fun `a class whose equals compares every field passes, its lazy caches left out`() {
        ValueSemantics.check(Complete::class.java, emptyMap())
        ValueSemantics.check(Cached::class.java, emptyMap())
    }

    @Test
    fun `a field equals leaves out fails the class, unless it is excluded with its reason`() {
        val failure = runCatching { ValueSemantics.check(Forgetful::class.java, emptyMap()) }.exceptionOrNull()
        failure!!.message.assert().contains("equals does not compare tags")
        ValueSemantics.check(Forgetful::class.java, mapOf("${Forgetful::class.java.name}#tags" to "a test"))
    }

    @Test
    fun `the classes found are those of this module that override equals, not data classes`() {
        val found = ValueSemantics.classesIn(listOf("me.ahoo.wow.tck.values"), javaClass).map { it.simpleName }
        found.assert().contains("Complete", "Forgetful", "Cached").doesNotContain("Circle", "Dot", "Color")
    }
}

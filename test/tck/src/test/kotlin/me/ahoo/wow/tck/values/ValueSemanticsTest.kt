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

    class Streaming(val name: String, val input: java.io.InputStream) {
        override fun equals(other: Any?): Boolean =
            this === other || other is Streaming && name == other.name && input == other.input

        override fun hashCode(): Int = name.hashCode()
    }

    class Unequal(val name: String) {
        // Equal to itself only, though it hashes its name.
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = name.hashCode()
    }

    class Unhashed(val name: String) {
        override fun equals(other: Any?): Boolean = other is Unhashed && name == other.name

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    @Test
    fun `an equals that is not reflexive over equal fields, or a hash code that differs, fails the class`() {
        runCatching { ValueSemantics.check(Unequal::class.java, emptyMap()) }.exceptionOrNull()!!.message.assert()
            .contains("two instances with the same fields are not equal")
        runCatching { ValueSemantics.check(Unhashed::class.java, emptyMap()) }.exceptionOrNull()!!.message.assert()
            .contains("equal instances have different hash codes")
    }

    @Test
    fun `an exclusion names a field exactly, or the field of every class of a package`() {
        ValueSemantics.matches("a.B#x", "a.B#x").assert().isTrue()
        ValueSemantics.matches("a.*#x", "a.b.C#x").assert().isTrue()
        ValueSemantics.matches("a.*#x", "b.C#x").assert().isFalse()
        ValueSemantics.matches("a.*#x", "a.C#y").assert().isFalse()
        ValueSemantics.matches("a.B", "a.B#x").assert().isFalse()
    }

    @Test
    fun `a field of no type the guard can build fails the class, unless it is excluded`() {
        val failure = runCatching { ValueSemantics.check(Streaming::class.java, emptyMap()) }.exceptionOrNull()
        failure!!.message.assert().contains("no value of input: java.io.InputStream can be built")
        ValueSemantics.check(Streaming::class.java, mapOf("${Streaming::class.java.name}#input" to "a test"))
    }

    @Test
    fun `an exclusion that names no field or class checked is stale`() {
        val spec = object : ValueSemanticsSpec() {
            override val packages = listOf("me.ahoo.wow.tck.values.sample")
            override val excluded = mapOf("me.ahoo.wow.tck.values.sample.Rich#gone" to "removed")
            override val excludedClasses = mapOf("me.ahoo.wow.tck.values.sample.Gone" to "removed")
        }
        val failure = runCatching {
            spec.`the module has classes to check, and every exclusion names a field of one`()
        }.exceptionOrNull()
        failure!!.message.assert().contains("Rich#gone", "sample.Gone")
        val empty = object : ValueSemanticsSpec() {
            override val packages = listOf("me.ahoo.wow.tck.nothing")
        }
        runCatching { empty.`the module has classes to check, and every exclusion names a field of one`() }
            .exceptionOrNull()!!.message.assert().contains("the packages are wrong")
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

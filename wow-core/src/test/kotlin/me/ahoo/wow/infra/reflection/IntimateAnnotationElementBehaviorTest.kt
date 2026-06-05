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

package me.ahoo.wow.infra.reflection

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import org.junit.jupiter.api.Test
import java.lang.annotation.Inherited
import kotlin.reflect.jvm.kotlinProperty

class IntimateAnnotationElementBehaviorTest {

    @Test
    fun `should expose property getter setter field and declaring class`() {
        val readElement = IntimateAnnotationFixture::annotated.toIntimateAnnotationElement()
        val writeElement = IntimateAnnotationFixture::mutable.toIntimateAnnotationElement()

        readElement.element.assert().isEqualTo(IntimateAnnotationFixture::annotated)
        readElement.property.assert().isEqualTo(IntimateAnnotationFixture::annotated)
        readElement.getter.assert().isEqualTo(IntimateAnnotationFixture::annotated.getter)
        readElement.javaField!!.name.assert().isEqualTo("annotated")
        readElement.declaringClass.assert().isEqualTo(IntimateAnnotationFixture::class)
        writeElement.setter.assert().isEqualTo(IntimateAnnotationFixture::mutable.setter)
    }

    @Test
    fun `should merge annotations from property getter and field`() {
        val element = IntimateAnnotationFixture::annotated.toIntimateAnnotationElement()
        val values = element.intimatedAnnotations
            .filterIsInstance<IntimateMarker>()
            .map { it.value }

        values.assert().isEqualTo(listOf("property", "getter", "field"))
    }

    @Test
    fun `should flatten repeatable annotations into intimate annotations`() {
        val element = RepeatableIntimateFixture::repeated.toIntimateAnnotationElement()
        val values = element.intimatedAnnotations
            .filterIsInstance<RepeatableIntimateMarker>()
            .map { it.value }

        values.assert().isEqualTo(listOf("first", "second"))
    }

    @Test
    fun `should expose java repeatable annotation container from java field fixture`() {
        val element = MockRepeatableClass::class.java.getDeclaredField("field")
            .kotlinProperty!!
            .toIntimateAnnotationElement()
        val tags = element.intimatedAnnotations
            .filterIsInstance<JvmRepeatableTags>()
            .single()
            .value
            .map { it.value }

        tags.assert().isEqualTo(listOf("tag1", "tag2"))
    }

    @Test
    fun `should include inherited meta annotations`() {
        val element = IntimateAnnotationFixture::annotated.toIntimateAnnotationElement()

        element.inheritedAnnotations.filterIsInstance<IntimateMarker>().map { it.value }.assert()
            .isEqualTo(listOf("property", "getter", "field"))
        element.inheritedAnnotations.filterIsInstance<InheritedMetaMarker>().map { it.value }.assert()
            .isEqualTo(listOf("intimate-meta"))
    }
}

private data class IntimateAnnotationFixture(
    @IntimateMarker("property")
    @get:IntimateMarker("getter")
    @field:IntimateMarker("field")
    val annotated: String,
    @set:IntimateMarker("setter")
    var mutable: String
)

private data class RepeatableIntimateFixture(
    @RepeatableIntimateMarker("first")
    @RepeatableIntimateMarker("second")
    val repeated: String
)

@Inherited
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
private annotation class InheritedMetaMarker(val value: String)

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS
)
@Retention(AnnotationRetention.RUNTIME)
@InheritedMetaMarker("intimate-meta")
private annotation class IntimateMarker(val value: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class RepeatableIntimateMarker(val value: String)

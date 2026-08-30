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
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.inheritedAnnotations
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.reflect.full.declaredFunctions

class MergedAnnotationTest {

    @Test
    fun `should merge class annotations from inherited class`() {
        val annotations = ChildMergedClass::class.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("base-class"))
    }

    @Test
    fun `should merge property annotations from inherited property`() {
        val annotations = ChildMergedState::id.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("base-property"))
    }

    @Test
    fun `should retain different annotations from sibling properties independent of interface order`() {
        val annotations = listOf(
            SiblingMergedState::token,
            ReversedSiblingMergedState::token,
        ).map { property ->
            property.toMergedAnnotation().mergedAnnotations
                .filterIsInstance<MergedMarker>()
                .mapTo(hashSetOf()) { it.value }
        }

        annotations.assert().isEqualTo(
            listOf(setOf("left", "right"), setOf("left", "right")),
        )
    }

    @Test
    fun `should let local property annotation override sibling annotations`() {
        val annotations = LocallyMergedState::token.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("local"))
    }

    @Test
    fun `should deduplicate equal annotations from sibling properties`() {
        val annotations = EqualSiblingMergedState::token.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()

        annotations.assert().hasSize(1)
        annotations.single().value.assert().isEqualTo("same")
    }

    @Test
    fun `should let nearer property annotation override farther ancestor`() {
        val annotations = TransitiveChildMergedState::token.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("mid-property"))
    }

    @Test
    fun `should merge function annotations from matching inherited signature`() {
        val annotations = ChildMergedOperation::execute.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("base-function"))
    }

    @Test
    fun `should not merge function annotations from same name with different parameter count`() {
        val function = ChildMergedParameterCountOperation::class.declaredFunctions.first {
            it.name == "publish" && it.parameters.size == 3
        }
        val annotations = function.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()

        annotations.assert().isEmpty()
    }

    @Test
    fun `should not merge function annotations from same name with different parameter type`() {
        val function = ChildMergedParameterTypeOperation::class.declaredFunctions.first {
            it.name == "dispatch" && it.parameters.last().type.classifier == String::class
        }
        val annotations = function.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()

        annotations.assert().isEmpty()
    }

    @Test
    fun `should let nearer function annotation override farther ancestor`() {
        val annotations = TransitiveChildMergedOperation::execute.toMergedAnnotation().mergedAnnotations
            .filterIsInstance<MergedMarker>()
            .map { it.value }

        annotations.assert().isEqualTo(listOf("mid-function"))
    }

    @Test
    fun `java method should let local annotation override inherited annotation`() {
        JavaMergedAnnotationFixture.LocalChild::class.java.getMethod("value")
            .mergedMarkerValues()
            .assert().isEqualTo(setOf("local"))
    }

    @Test
    fun `java method should let nearer annotation override farther ancestor`() {
        JavaMergedAnnotationFixture.TransitiveChild::class.java.getMethod("value")
            .mergedMarkerValues()
            .assert().isEqualTo(setOf("mid"))
    }

    @Test
    fun `java method should deduplicate equal diamond annotations`() {
        JavaMergedAnnotationFixture.EqualDiamond::class.java.getMethod("value")
            .inheritedAnnotations()
            .filterIsInstance<JavaMergedAnnotationFixture.Marker>()
            .assert().hasSize(1)
    }

    @Test
    fun `java method should retain different diamond annotations`() {
        JavaMergedAnnotationFixture.DifferentDiamond::class.java.getMethod("value")
            .mergedMarkerValues()
            .assert().isEqualTo(setOf("left", "right"))
    }

    @Test
    fun `java method should not inherit annotation from overload`() {
        JavaMergedAnnotationFixture.OverloadedChild::class.java.getMethod("convert", Int::class.javaObjectType)
            .mergedMarkerValues()
            .assert().isEmpty()
    }

    @Test
    fun `java method should resolve covariant bridge without duplicate annotations`() {
        val methods = JavaMergedAnnotationFixture.CovariantChild::class.java.declaredMethods
            .filter { it.name == "value" }

        methods.any { it.isBridge }.assert().isTrue()
        methods.forEach { method ->
            method.mergedMarkerValues().assert().isEqualTo(setOf("mid"))
        }
    }
}

private fun Method.mergedMarkerValues(): Set<String> = inheritedAnnotations()
    .filterIsInstance<JavaMergedAnnotationFixture.Marker>()
    .mapTo(linkedSetOf()) { it.value }

@MergedMarker("base-class")
private open class BaseMergedClass

private class ChildMergedClass : BaseMergedClass()

private open class BaseMergedState(
    @get:MergedMarker("base-property")
    open val id: String
)

private class ChildMergedState(override val id: String) : BaseMergedState(id)

private interface LeftMergedState {
    @get:MergedMarker("left")
    val token: String
}

private interface RightMergedState {
    @get:MergedMarker("right")
    val token: String
}

private data class SiblingMergedState(override val token: String) : LeftMergedState, RightMergedState

private data class ReversedSiblingMergedState(override val token: String) : RightMergedState, LeftMergedState

private data class LocallyMergedState(
    @get:MergedMarker("local") override val token: String,
) : LeftMergedState, RightMergedState

private interface FirstEqualMergedState {
    @get:MergedMarker("same")
    val token: String
}

private interface SecondEqualMergedState {
    @get:MergedMarker("same")
    val token: String
}

private data class EqualSiblingMergedState(
    override val token: String,
) : FirstEqualMergedState, SecondEqualMergedState

private interface TransitiveBaseMergedState {
    @get:MergedMarker("base-property")
    val token: String
}

private interface TransitiveMidMergedState : TransitiveBaseMergedState {
    @get:MergedMarker("mid-property")
    override val token: String
}

private data class TransitiveChildMergedState(
    override val token: String,
) : TransitiveMidMergedState

private interface BaseMergedOperation {
    @MergedMarker("base-function")
    fun execute(command: String)
}

private class ChildMergedOperation : BaseMergedOperation {
    override fun execute(command: String) = Unit
}

private interface TransitiveBaseMergedOperation {
    @MergedMarker("base-function")
    fun execute(command: String)
}

private interface TransitiveMidMergedOperation : TransitiveBaseMergedOperation {
    @MergedMarker("mid-function")
    override fun execute(command: String)
}

private class TransitiveChildMergedOperation : TransitiveMidMergedOperation {
    override fun execute(command: String) = Unit
}

private interface BaseMergedParameterCountOperation {
    @MergedMarker("base-parameter-count")
    fun publish(command: String)
}

private class ChildMergedParameterCountOperation : BaseMergedParameterCountOperation {
    override fun publish(command: String) = Unit

    @Suppress("UnusedParameter")
    fun publish(command: String, version: Int) = Unit
}

private interface BaseMergedParameterTypeOperation {
    @MergedMarker("base-parameter-type")
    fun dispatch(command: Int)
}

private class ChildMergedParameterTypeOperation : BaseMergedParameterTypeOperation {
    override fun dispatch(command: Int) = Unit

    @Suppress("UnusedParameter")
    fun dispatch(command: String) = Unit
}

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
private annotation class MergedMarker(val value: String)

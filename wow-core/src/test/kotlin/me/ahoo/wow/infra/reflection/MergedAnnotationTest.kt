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
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import org.junit.jupiter.api.Test
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
}

@MergedMarker("base-class")
private open class BaseMergedClass

private class ChildMergedClass : BaseMergedClass()

private open class BaseMergedState(
    @get:MergedMarker("base-property")
    open val id: String
)

private class ChildMergedState(override val id: String) : BaseMergedState(id)

private interface BaseMergedOperation {
    @MergedMarker("base-function")
    fun execute(command: String)
}

private class ChildMergedOperation : BaseMergedOperation {
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

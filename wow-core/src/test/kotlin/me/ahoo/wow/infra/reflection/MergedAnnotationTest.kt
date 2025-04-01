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

import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor

class MergedAnnotationTest {

    @Test
    fun getElement() {
        val mergedAnnotation = MockClass::class.toMergedAnnotation()
        assertThat(mergedAnnotation.element, equalTo(MockClass::class))
    }

    @Test
    fun getInheritedAnnotations() {
        val mergedAnnotation = MockClass::class.toMergedAnnotation()
        assertThat(mergedAnnotation.mergedAnnotations.isEmpty(), equalTo(true))
    }

    @Test
    fun getInheritedAnnotationsIfProperty() {
        val mergedAnnotation = MockClass::property.toMergedAnnotation()
        assertThat(mergedAnnotation.mergedAnnotations.first(), equalTo(MockAnnotation()))
    }

    @Test
    fun getInheritedAnnotationsIfCtor() {
        val mergedAnnotation = MockClass::class.primaryConstructor!!.parameters.first().toMergedAnnotation()
        assertThat(mergedAnnotation.mergedAnnotations.isEmpty(), equalTo(true))
    }

    @Test
    fun getInheritedAnnotationsIfFunctionNotSame() {
        val mergedAnnotation = MockClass::class.declaredFunctions.first {
            it.name == "interfaceFunction" &&
                    it.parameters.last().type.classifier == String::class
        }.toMergedAnnotation()
        assertThat(mergedAnnotation.mergedAnnotations.isEmpty(), equalTo(true))
    }

    @Test
    fun getInheritedAnnotationsIfFunctionNotSame2() {
        val mergedAnnotation = MockClass::class.declaredFunctions.first {
            it.name == "interfaceFunction" &&
                    it.parameters.size == 3
        }.toMergedAnnotation()
        assertThat(mergedAnnotation.mergedAnnotations.isEmpty(), equalTo(true))
    }

    interface MockInterface {
        @OnCommand
        fun onCommand()

        @get:MockAnnotation
        val property: String

        fun interfaceFunction(num: Int)
    }

    class MockClass(override val property: String) : MockInterface {
        override fun onCommand() = Unit
        override fun interfaceFunction(num: Int) = Unit

        @Suppress("UnusedParameter")
        fun interfaceFunction(str: String) = Unit

        @Suppress("UnusedParameter")
        fun interfaceFunction(num: Int, str: String) = Unit
    }
}

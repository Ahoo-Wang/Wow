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

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class MockClass(val id: String)

internal class ClassMetadataTest {
    @Test
    fun visitField() {
        val fields = mutableSetOf<Field>()
        ClassMetadata.visitField(MockClass::class.java) {
            fields.add(it)
        }
        assertThat(fields, hasItem(MockClass::class.java.getDeclaredField("id")))
    }

    @Test
    fun visitMethod() {
        ClassMetadata.visitMethod(MockClass::class.java) { }
    }

    @Test
    fun visit() {
        ClassMetadata.visit(
            MockClass::class.java,
            object :
                ClassVisitor {
                override fun start() = Unit
                override fun visitClass(currentClass: Class<*>) = Unit
                override fun visitField(field: Field) = Unit
                override fun visitConstructor(constructor: Constructor<*>) = Unit
                override fun visitMethod(method: Method) = Unit
                override fun end() = Unit
            },
        )
    }
}

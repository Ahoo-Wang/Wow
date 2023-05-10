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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
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
                override fun start() {}
                override fun visitClass(currentClass: Class<*>) {}
                override fun visitField(field: Field) {}
                override fun visitConstructor(constructor: Constructor<*>) {}
                override fun visitMethod(method: Method) {}
                override fun end() {}
            },
        )
    }
}

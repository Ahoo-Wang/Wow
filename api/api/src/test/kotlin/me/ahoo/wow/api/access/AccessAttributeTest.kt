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

package me.ahoo.wow.api.access

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class AccessAttributeTest {
    enum class Level { LOW, HIGH }

    class DocumentState

    private object DocumentStateAccess : AccessAttributes<DocumentState>() {
        val DEPARTMENT: AccessAttribute.Single<DocumentState, String> = single("department")
        val SHARED_WITH: AccessAttribute.Multiple<DocumentState, String> =
            AccessAttribute.multiple("sharedWith", String::class.java)
    }

    @Test
    fun `a reference names its attribute, the class of its values and whether it holds several`() {
        DocumentStateAccess.DEPARTMENT.name.assert().isEqualTo("department")
        DocumentStateAccess.DEPARTMENT.type.assert().isEqualTo(String::class.java)
        DocumentStateAccess.DEPARTMENT.multiple.assert().isFalse()
        DocumentStateAccess.SHARED_WITH.multiple.assert().isTrue()
        DocumentStateAccess.SHARED_WITH.toString()
            .assert().isEqualTo("AccessAttribute(name=sharedWith, type=java.lang.String, multiple=true)")
    }

    @Test
    fun `two references are equal when they name the same attribute alike`() {
        val name: Any = "department"
        (DocumentStateAccess.DEPARTMENT == DocumentStateAccess.DEPARTMENT).assert().isTrue()
        (DocumentStateAccess.DEPARTMENT == name).assert().isFalse()
        single("department").assert().isEqualTo(DocumentStateAccess.DEPARTMENT)
        single("department").hashCode().assert().isEqualTo(DocumentStateAccess.DEPARTMENT.hashCode())
        single("division").assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
        val several = AccessAttribute.multiple<DocumentState, String>("department", String::class.java)
        several.assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
        val level = AccessAttribute.single<DocumentState, Level>("department", Level::class.java)
        level.assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
    }

    @Test
    fun `every generated object carries the built-in owner and space`() {
        DocumentStateAccess.OWNER.assert().isSameAs(AccessAttribute.OWNER)
        DocumentStateAccess.SPACE.assert().isSameAs(AccessAttribute.SPACE)
        AccessAttribute.BUILT_IN.assert().containsExactly("owner", "space")
        assertThrownBy<IllegalArgumentException> { single("owner") }.hasMessageContaining("built in")
        assertThrownBy<IllegalArgumentException> {
            AccessAttribute.multiple<DocumentState, String>("space", String::class.java)
        }
    }

    @Test
    fun `the values are strings, integers, booleans or enums`() {
        listOf(String::class.java, Int::class.javaObjectType, Long::class.javaObjectType, Boolean::class.javaObjectType)
            .forEach { AccessAttribute.isValueType(it).assert().describedAs(it.name).isTrue() }
        AccessAttribute.isValueType(Level::class.java).assert().isTrue()
        val level = AccessAttribute.single<DocumentState, Level>("level", Level::class.java)
        level.type.assert().isEqualTo(Level::class.java)
        listOf(Double::class.javaObjectType, java.math.BigDecimal::class.java, List::class.java, Any::class.java)
            .forEach { type ->
                AccessAttribute.isValueType(type).assert().describedAs(type.name).isFalse()
                @Suppress("UNCHECKED_CAST")
                val values = type as Class<Any>
                assertThrownBy<IllegalArgumentException> { multipleOf(values) }.hasMessageContaining(type.name)
            }
    }

    private companion object {
        /** The attribute [name] of a document, of one string. */
        fun single(name: String): AccessAttribute.Single<DocumentState, String> =
            AccessAttribute.single(name, String::class.java)

        fun multipleOf(type: Class<Any>): AccessAttribute.Multiple<DocumentState, Any> =
            AccessAttribute.multiple("values", type)
    }
}

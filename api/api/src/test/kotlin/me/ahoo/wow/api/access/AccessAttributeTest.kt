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

    private object DocumentStateAccess : AccessAttributes() {
        val DEPARTMENT = AccessAttribute.single("department", String::class.java)
        val SHARED_WITH = AccessAttribute.multiple("sharedWith", String::class.java)
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
        AccessAttribute.single("department", String::class.java).assert().isEqualTo(DocumentStateAccess.DEPARTMENT)
        AccessAttribute.single("department", String::class.java).hashCode()
            .assert().isEqualTo(DocumentStateAccess.DEPARTMENT.hashCode())
        AccessAttribute.multiple("department", String::class.java).assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
        AccessAttribute.single("department", Level::class.java).assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
        AccessAttribute.single("division", String::class.java).assert().isNotEqualTo(DocumentStateAccess.DEPARTMENT)
    }

    @Test
    fun `every generated object carries the built-in owner and space`() {
        DocumentStateAccess.OWNER.assert().isSameAs(AccessAttribute.OWNER)
        DocumentStateAccess.SPACE.assert().isSameAs(AccessAttribute.SPACE)
        AccessAttribute.BUILT_IN.assert().containsExactly("owner", "space")
        assertThrownBy<IllegalArgumentException> { AccessAttribute.single("owner", String::class.java) }
            .hasMessageContaining("built in")
        assertThrownBy<IllegalArgumentException> { AccessAttribute.multiple("space", String::class.java) }
    }

    @Test
    fun `the values are strings, integers, booleans or enums`() {
        listOf(String::class.java, Int::class.javaObjectType, Long::class.javaObjectType, Boolean::class.javaObjectType)
            .forEach { AccessAttribute.isValueType(it).assert().describedAs(it.name).isTrue() }
        AccessAttribute.isValueType(Level::class.java).assert().isTrue()
        AccessAttribute.single("level", Level::class.java).type.assert().isEqualTo(Level::class.java)
        listOf(Double::class.javaObjectType, java.math.BigDecimal::class.java, List::class.java, Any::class.java)
            .forEach { type ->
                AccessAttribute.isValueType(type).assert().describedAs(type.name).isFalse()
                @Suppress("UNCHECKED_CAST")
                val values = type as Class<Any>
                assertThrownBy<IllegalArgumentException> { AccessAttribute.multiple("values", values) }
                    .hasMessageContaining(type.name)
            }
    }
}

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
package me.ahoo.wow.infra.accessor.field

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * DefaultFieldGetterTest .
 *
 * @author ahoo wang
 */
internal class FieldAccessorTest {
    @Test
    fun instanceFieldValue() {
        val entity = Entity()
        assertThat(INSTANCE_FIELD_ACCESSOR[entity], Matchers.equalTo("instance"))
        assertThat(STATIC_FIELD_ACCESSOR[entity], Matchers.equalTo("static"))
    }

    @Test
    fun setInstanceFieldValue() {
        val entity = Entity()
        INSTANCE_FIELD_ACCESSOR[entity] = "i"
        assertThat(INSTANCE_FIELD_ACCESSOR[entity], Matchers.equalTo("i"))
    }

    @Test
    fun staticFieldValue() {
        assertThat(STATIC_FIELD_ACCESSOR.static, Matchers.equalTo("static"))
    }

    @Test
    fun setStaticFieldValue() {
        STATIC_FIELD_ACCESSOR.setStatic("s")
        assertThat(STATIC_FIELD_ACCESSOR.static, Matchers.equalTo("s"))
    }

    @Test
    fun illegalAccess() {
        val fieldIllegalAccess = FieldIllegalAccess()
        val fieldAccessor = FieldAccessor<FieldIllegalAccess, String>(
            FieldIllegalAccess.FIELD,
        )
        FieldIllegalAccess.FIELD.isAccessible = false
        Assertions.assertThrows(IllegalAccessException::class.java) { fieldAccessor[fieldIllegalAccess] }
        Assertions.assertThrows(IllegalAccessException::class.java) { fieldAccessor[fieldIllegalAccess] = "" }
    }

    class Entity {
        @Suppress("UnusedPrivateMember")
        private val instance = "instance"

        companion object {
            @JvmField
            var STATIC = "static"
        }
    }

    @Suppress("UnusedPrivateProperty")
    class FieldIllegalAccess {
        private val field: String? = null

        companion object {
            val FIELD: Field = FieldIllegalAccess::class.java.getDeclaredField("field")
        }
    }

    companion object {
        val INSTANCE_FIELD: Field = Entity::class.java.getDeclaredField("instance")
        val STATIC_FIELD: Field = Entity::class.java.getDeclaredField("STATIC")
        val INSTANCE_FIELD_ACCESSOR = FieldAccessor<Entity, String>(INSTANCE_FIELD)
        val STATIC_FIELD_ACCESSOR = FieldAccessor<Entity, String>(STATIC_FIELD)
    }
}

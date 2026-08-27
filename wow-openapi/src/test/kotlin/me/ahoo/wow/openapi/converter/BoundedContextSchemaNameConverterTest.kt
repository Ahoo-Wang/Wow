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

package me.ahoo.wow.openapi.converter

import com.fasterxml.jackson.databind.type.TypeFactory
import io.swagger.v3.core.converter.AnnotatedType
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.resolveName
import org.junit.jupiter.api.Test

internal class BoundedContextSchemaNameConverterTest {

    @Test
    fun `should not resolve name for string java type`() {
        val type = TypeFactory.defaultInstance().constructType(String::class.java)
        val annotatedType = AnnotatedType(type)
        annotatedType.resolveName()
        annotatedType.name.assert().isNull()
    }

    @Test
    fun `should not resolve name for string class`() {
        val annotatedType = AnnotatedType(String::class.java)
        annotatedType.resolveName()
        annotatedType.name.assert().isNull()
    }

    @Test
    fun `should keep existing name when not blank`() {
        val annotatedType = AnnotatedType(String::class.java)
        annotatedType.name = "test"
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("test")
    }

    @Test
    fun `should resolve name for list java type`() {
        val type = TypeFactory.defaultInstance().constructCollectionLikeType(List::class.java, String::class.java)
        val annotatedType = AnnotatedType(type)
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("StringList")
    }

    @Test
    fun `should resolve name for non-standard type with bounded context prefix`() {
        val annotatedType = AnnotatedType(BatchResult::class.java)
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("wow.openapi.BatchResult")
    }
}

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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class VariableMetadataTest {

    @Test
    fun `should resolve variable type from field when field path has single element`() {
        val field = java.lang.String::class.java.getDeclaredField("value")
        val metadata = VariableMetadata(field, listOf("value"), "testVar", true)
        metadata.variableType.assert().isEqualTo(field.genericType)
    }

    @Test
    fun `should return null variable type when field is null`() {
        val metadata = VariableMetadata(null, listOf("test"), "testVar", true)
        metadata.variableType.assert().isNull()
    }

    @Test
    fun `should provide field name as last element of field path`() {
        val metadata = VariableMetadata(null, listOf("customer", "id"), "customerId", true)
        metadata.fieldName.assert().isEqualTo("id")
    }
}

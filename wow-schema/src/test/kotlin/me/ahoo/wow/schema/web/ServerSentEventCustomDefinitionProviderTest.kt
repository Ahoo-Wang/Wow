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

package me.ahoo.wow.schema.web

import com.fasterxml.classmate.TypeResolver
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestState
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent

class ServerSentEventCustomDefinitionProviderTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    @Test
    fun `should generate schema for server sent event`() {
        val resolvedType = TypeResolver().resolve(ServerSentEvent::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate schema for parameterized server sent event`() {
        val resolvedType =
            TypeResolver().resolve(ServerSentEvent::class.java, TestState::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }
}

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

package me.ahoo.wow.openapi.context

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.SchemaVersion
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.RequestBodyBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections

internal class OpenAPIComponentContextTest {

    @Test
    fun `should create default context with schema version`() {
        val context = OpenAPIComponentContext.default(schemaVersion = SchemaVersion.DRAFT_2020_12)
        context.assert().isNotNull()
        context.inline.assert().isFalse()
    }

    @Test
    fun `should create default context with inline option`() {
        val context = OpenAPIComponentContext.default(inline = true)
        context.inline.assert().isTrue()
    }

    @Test
    fun `should have correct component reference constants`() {
        OpenAPIComponentContext.COMPONENTS_PREFIX.assert().isEqualTo("#/components/")
        OpenAPIComponentContext.COMPONENTS_HEADERS_REF.assert().isEqualTo("#/components/headers/")
        OpenAPIComponentContext.COMPONENTS_SCHEMAS_REF.assert().isEqualTo("#/components/schemas/")
        OpenAPIComponentContext.COMPONENTS_PARAMETERS_REF.assert().isEqualTo("#/components/parameters/")
        OpenAPIComponentContext.COMPONENTS_REQUEST_BODIES_REF.assert().isEqualTo("#/components/requestBodies/")
        OpenAPIComponentContext.COMPONENTS_RESPONSES_REF.assert().isEqualTo("#/components/responses/")
    }

    @Test
    fun `should register a component schema through the default method for a legacy context`() {
        val context = LegacyOpenAPIComponentContext()
        val schema = StringSchema()._enum(listOf("state.id"))

        val reference = context.componentSchema("example.LegacyFields", schema)

        context.schemas["example.LegacyFields"].assert().isEqualTo(schema)
        reference.`$ref`.assert().isEqualTo("#/components/schemas/example.LegacyFields")
    }

    @Test
    fun `should reject a blank component schema key through the default method`() {
        val error = assertThrows<IllegalArgumentException> {
            LegacyOpenAPIComponentContext().componentSchema(" ", StringSchema())
        }

        error.message.assert().contains("key must not be blank")
    }

    @Test
    fun `should identify the unwritable legacy context when component schema registration fails`() {
        val error = assertThrows<IllegalStateException> {
            LegacyOpenAPIComponentContext(emptyMap()).componentSchema("example.LegacyFields", StringSchema())
        }

        error.message.assert().contains("example.LegacyFields")
        error.message.assert().contains("LegacyOpenAPIComponentContext")
    }

    @Test
    fun `should wrap an unsupported component schema write through the default method`() {
        val schemas = Collections.unmodifiableMap(mutableMapOf<String, Schema<*>>())

        val error = assertThrows<IllegalStateException> {
            LegacyOpenAPIComponentContext(schemas).componentSchema("example.LegacyFields", StringSchema())
        }

        error.message.assert().contains("example.LegacyFields")
        error.message.assert().contains("unwritable schemas map")
        error.cause.assert().isInstanceOf(UnsupportedOperationException::class.java)
    }
}

private class LegacyOpenAPIComponentContext(
    override val schemas: Map<String, Schema<*>> = mutableMapOf()
) : OpenAPIComponentContext {
    private val delegate = OpenAPIComponentContext.default()

    override val inline: Boolean = false
    override val parameters: Map<String, Parameter>
        get() = delegate.parameters
    override val headers: Map<String, Header>
        get() = delegate.headers
    override val requestBodies: Map<String, RequestBody>
        get() = delegate.requestBodies
    override val responses: Map<String, ApiResponse>
        get() = delegate.responses

    override fun resolveType(
        mainTargetType: java.lang.reflect.Type,
        vararg typeParameters: java.lang.reflect.Type
    ): ResolvedType {
        return delegate.resolveType(mainTargetType, *typeParameters)
    }

    override fun schema(
        mainTargetType: java.lang.reflect.Type,
        vararg typeParameters: java.lang.reflect.Type
    ): Schema<*> {
        return delegate.schema(mainTargetType, *typeParameters)
    }

    override fun arraySchema(
        mainTargetType: java.lang.reflect.Type,
        vararg typeParameters: java.lang.reflect.Type
    ): Schema<*> {
        return delegate.arraySchema(mainTargetType, *typeParameters)
    }

    override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter {
        return delegate.parameter(key, builder)
    }

    override fun header(key: String, builder: Header.() -> Unit): Header {
        return delegate.header(key, builder)
    }

    override fun requestBody(key: String, builder: RequestBodyBuilder.() -> Unit): RequestBody {
        return delegate.requestBody(key, builder)
    }

    override fun response(key: String, builder: ApiResponseBuilder.() -> Unit): ApiResponse {
        return delegate.response(key, builder)
    }

    override fun finish() {
        delegate.finish()
    }
}

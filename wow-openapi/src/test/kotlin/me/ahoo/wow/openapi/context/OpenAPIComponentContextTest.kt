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

import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

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
        OpenAPIComponentContext.COMPONENTS_PARAMETERS_REF.assert().isEqualTo("#/components/parameters/")
        OpenAPIComponentContext.COMPONENTS_REQUEST_BODIES_REF.assert().isEqualTo("#/components/requestBodies/")
        OpenAPIComponentContext.COMPONENTS_RESPONSES_REF.assert().isEqualTo("#/components/responses/")
    }
}

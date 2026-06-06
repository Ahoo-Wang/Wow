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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class DefaultOpenAPIComponentContextTest {

    private val context = OpenAPIComponentContext.default()

    @Test
    fun `should return empty schemas before finish`() {
        context.schemas.assert().isEmpty()
    }

    @Test
    fun `should return empty parameters before any registration`() {
        context.parameters.assert().isEmpty()
    }

    @Test
    fun `should register and retrieve parameter`() {
        val parameter = context.parameter("test-id") {
            name = "testParam"
        }
        context.parameters.assert().containsKey("test-id")
        parameter.`$ref`.assert().isEqualTo("#/components/parameters/test-id")
    }

    @Test
    fun `should register and retrieve header`() {
        context.header("X-Test") {
            description = "test"
        }
        context.headers.assert().containsKey("X-Test")
    }

    @Test
    fun `should register and retrieve request body`() {
        context.requestBody("test-body") {
            description("test body")
        }
        context.requestBodies.assert().containsKey("test-body")
    }

    @Test
    fun `should register and retrieve response`() {
        context.response("test-response") {
            description("OK")
        }
        context.responses.assert().containsKey("test-response")
    }
}

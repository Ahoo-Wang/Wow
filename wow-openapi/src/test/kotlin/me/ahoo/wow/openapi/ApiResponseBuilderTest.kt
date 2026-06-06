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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class ApiResponseBuilderTest {

    @Test
    fun `should add header to api response`() {
        val response = ApiResponseBuilder()
            .header("X-Test", Header().description("test-header"))
            .build()
        response.headers.assert().containsKey("X-Test")
        response.headers["X-Test"]!!.description.assert().isEqualTo("test-header")
    }

    @Test
    fun `should add content to api response`() {
        val response = ApiResponseBuilder()
            .content(schema = StringSchema())
            .build()
        response.content.containsKey(Https.MediaType.APPLICATION_JSON).assert().isTrue()
    }

    @Test
    fun `should build response with description and content`() {
        val response = ApiResponseBuilder()
            .description("OK")
            .content(schema = StringSchema())
            .build()
        response.description.assert().isEqualTo("OK")
        response.content.containsKey(Https.MediaType.APPLICATION_JSON).assert().isTrue()
    }
}

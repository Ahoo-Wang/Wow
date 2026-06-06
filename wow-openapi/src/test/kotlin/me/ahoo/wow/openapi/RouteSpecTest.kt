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

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RouteSpecTest {

    private fun routeSpec(
        id: String = "test-id",
        path: String = "/test",
        method: String = Https.Method.GET,
        summary: String = "Test",
        tags: List<Tag> = listOf(Tag().name("test"))
    ): RouteSpec = object : RouteSpec {
        override val id: String = id
        override val path: String = path
        override val method: String = method
        override val summary: String = summary
        override val tags: List<Tag> = tags
        override val parameters: List<Parameter> = emptyList()
        override val responses: ApiResponses = ApiResponses()
    }

    @Test
    fun `should convert route spec to operation with correct fields`() {
        val spec = routeSpec()
        val operation = spec.toOperation()
        operation.operationId.assert().isEqualTo("test-id")
        operation.summary.assert().isEqualTo("Test")
        operation.tags.assert().isEqualTo(listOf("test"))
    }

    @Test
    fun `should convert route spec list to path item with get method`() {
        val pathItem = listOf(routeSpec(method = Https.Method.GET)).toPathItem()
        pathItem.get.assert().isNotNull()
    }

    @Test
    fun `should map post method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.POST)).toPathItem()
        pathItem.post.assert().isNotNull()
    }

    @Test
    fun `should map put method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.PUT)).toPathItem()
        pathItem.put.assert().isNotNull()
    }

    @Test
    fun `should map delete method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.DELETE)).toPathItem()
        pathItem.delete.assert().isNotNull()
    }

    @Test
    fun `should map patch method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.PATCH)).toPathItem()
        pathItem.patch.assert().isNotNull()
    }

    @Test
    fun `should throw when encountering unsupported method`() {
        assertThrows<IllegalArgumentException> {
            listOf(routeSpec(method = "UNSUPPORTED")).toPathItem()
        }
    }

    @Test
    fun `should throw when detecting duplicate routes with same method`() {
        val spec1 = routeSpec(id = "route-1", method = Https.Method.GET)
        val spec2 = routeSpec(id = "route-2", method = Https.Method.GET)
        assertThrows<IllegalArgumentException> {
            listOf(spec1, spec2).toPathItem()
        }
    }
}

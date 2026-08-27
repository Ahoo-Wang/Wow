/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag
import org.junit.jupiter.api.Test

internal class OpenApiRendererTest {

    @Test
    fun `should render route catalog to open api paths`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        routeId = "test.route",
                        method = Https.Method.GET,
                        path = "/test"
                    )
                )
            ),
            openAPI
        )

        openAPI.specVersion.assert().isEqualTo(SpecVersion.V31)
        openAPI.paths["/test"]!!.get.operationId.assert().isEqualTo("test.route")
    }

    @Test
    fun `should render parameters`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        path = "/test/{id}",
                        parameters = listOf(
                            HttpParameter(
                                name = "id",
                                location = HttpParameterLocation.PATH,
                                required = true,
                                schema = HttpSchema.String
                            ),
                            HttpParameter(
                                name = "filter",
                                location = HttpParameterLocation.QUERY,
                                schema = HttpSchema.Boolean
                            ),
                            HttpParameter(
                                name = "X-Test",
                                location = HttpParameterLocation.HEADER,
                                schema = HttpSchema.Long
                            )
                        )
                    )
                )
            ),
            openAPI
        )

        val parameters = openAPI.paths["/test/{id}"]!!.get.parameters.associateBy { it.name }
        parameters["id"]!!.`in`.assert().isEqualTo("path")
        parameters["id"]!!.required.assert().isTrue()
        parameters["id"]!!.schema.type.assert().isEqualTo("string")
        parameters["filter"]!!.`in`.assert().isEqualTo("query")
        parameters["filter"]!!.required.assert().isFalse()
        parameters["filter"]!!.schema.type.assert().isEqualTo("boolean")
        parameters["X-Test"]!!.`in`.assert().isEqualTo("header")
        parameters["X-Test"]!!.schema.type.assert().isEqualTo("integer")
        parameters["X-Test"]!!.schema.format.assert().isEqualTo("int64")
    }

    @Test
    fun `should render request and response content`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        method = Https.Method.POST,
                        requestBody = HttpRequestBody(
                            required = true,
                            content = listOf(
                                HttpContent(
                                    mediaType = Https.MediaType.APPLICATION_JSON,
                                    schema = HttpSchema.ComponentRef("RequestBodySchema")
                                )
                            )
                        ),
                        responses = listOf(
                            HttpResponse(
                                statusCode = Https.Code.OK,
                                content = listOf(
                                    HttpContent(
                                        mediaType = Https.MediaType.TEXT_EVENT_STREAM,
                                        schema = HttpSchema.Array(HttpSchema.ComponentRef("EventSchema"))
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            openAPI
        )

        val operation = openAPI.paths["/test"]!!.post
        operation.requestBody.required.assert().isTrue()
        operation.requestBody.content[Https.MediaType.APPLICATION_JSON]!!
            .schema.`$ref`.assert().isEqualTo("#/components/schemas/RequestBodySchema")
        val arraySchema = operation.responses[Https.Code.OK]!!
            .content[Https.MediaType.TEXT_EVENT_STREAM]!!.schema
        arraySchema.type.assert().isEqualTo("array")
        arraySchema.items.`$ref`.assert().isEqualTo("#/components/schemas/EventSchema")
    }

    @Test
    fun `should render type ref schemas through component context`() {
        val componentContext = OpenAPIComponentContext.default()
        val openAPI = OpenAPI()

        OpenApiRenderer(componentContext).render(
            RouteCatalog(
                listOf(
                    route(
                        method = Https.Method.POST,
                        requestBody = HttpRequestBody(
                            content = listOf(
                                HttpContent(
                                    mediaType = Https.MediaType.APPLICATION_JSON,
                                    schema = HttpSchema.TypeRef(TypeRefFixture::class.java)
                                )
                            )
                        )
                    )
                )
            ),
            openAPI
        )
        componentContext.finish()

        openAPI.paths["/test"]!!.post.requestBody.content[Https.MediaType.APPLICATION_JSON]!!
            .schema.`$ref`.assert().startsWith("#/components/schemas/")
        componentContext.schemas.keys.any { it.contains("TypeRefFixture") }.assert().isTrue()
    }

    @Test
    fun `should render response headers`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        responses = listOf(
                            HttpResponse(
                                statusCode = Https.Code.OK,
                                headers = listOf(
                                    HttpHeader(
                                        name = "X-Result",
                                        schema = HttpSchema.ComponentRef("HeaderSchema")
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            openAPI
        )

        val header = openAPI.paths["/test"]!!.get.responses[Https.Code.OK]!!.headers["X-Result"]!!
        header.schema.`$ref`.assert().isEqualTo("#/components/schemas/HeaderSchema")
    }

    @Test
    fun `should preserve operation component references`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        path = "/test/{id}",
                        parameters = listOf(
                            HttpParameter(
                                name = "id",
                                location = HttpParameterLocation.PATH,
                                required = true,
                                componentRef = "PathParameter"
                            )
                        ),
                        requestBody = HttpRequestBody(componentRef = "TestRequestBody"),
                        responses = listOf(
                            HttpResponse(
                                statusCode = Https.Code.OK,
                                componentRef = "TestResponse"
                            ),
                            HttpResponse(
                                statusCode = Https.Code.BAD_REQUEST,
                                headers = listOf(
                                    HttpHeader(
                                        name = "X-Error",
                                        componentRef = "ErrorHeader"
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            openAPI
        )

        val operation = openAPI.paths["/test/{id}"]!!.get
        operation.parameters.single().`$ref`.assert()
            .isEqualTo("#/components/parameters/PathParameter")
        operation.requestBody.`$ref`.assert()
            .isEqualTo("#/components/requestBodies/TestRequestBody")
        operation.responses[Https.Code.OK]!!.`$ref`.assert()
            .isEqualTo("#/components/responses/TestResponse")
        operation.responses[Https.Code.BAD_REQUEST]!!.headers["X-Error"]!!.`$ref`.assert()
            .isEqualTo("#/components/headers/ErrorHeader")
    }

    @Test
    fun `should render route and path descriptions`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        routeId = "test.post",
                        method = Https.Method.POST,
                        path = "/test",
                        summary = "Post summary",
                        description = "Post description",
                        pathSummary = "Path summary",
                        pathDescription = "Path description"
                    ),
                    route(
                        routeId = "test.get",
                        method = Https.Method.GET,
                        path = "/test",
                        summary = "Get summary",
                        description = "Get description",
                        pathSummary = "Path summary",
                        pathDescription = "Path description"
                    )
                )
            ),
            openAPI
        )

        val pathItem = openAPI.paths["/test"]!!
        pathItem.summary.assert().isEqualTo("Path summary")
        pathItem.description.assert().isEqualTo("Path description")
        pathItem.post.summary.assert().isEqualTo("Post summary")
        pathItem.post.description.assert().isEqualTo("Post description")
        pathItem.get.summary.assert().isEqualTo("Get summary")
        pathItem.get.description.assert().isEqualTo("Get description")
    }

    @Test
    fun `should preserve existing path metadata when route metadata is blank`() {
        val existingPathItem = PathItem()
            .summary("Existing summary")
            .description("Existing description")
        val openAPI = OpenAPI().paths(Paths().addPathItem("/test", existingPathItem))

        OpenApiRenderer().render(
            RouteCatalog(listOf(route())),
            openAPI
        )

        val pathItem = openAPI.paths["/test"]!!
        pathItem.summary.assert().isEqualTo("Existing summary")
        pathItem.description.assert().isEqualTo("Existing description")
    }

    @Test
    fun `should render inline schema shape and declared empty content`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(
                listOf(
                    route(
                        path = "/test/{id}/{customerId}",
                        parameters = listOf(
                            HttpParameter(
                                name = "id",
                                location = HttpParameterLocation.PATH,
                                required = true,
                                schema = HttpSchema.Unspecified
                            ),
                            HttpParameter(
                                name = "customerId",
                                location = HttpParameterLocation.PATH,
                                required = true,
                                schema = HttpSchema.Formatted("int32")
                            )
                        ),
                        requestBody = HttpRequestBody(contentDeclared = true),
                        responses = listOf(HttpResponse(statusCode = Https.Code.OK, contentDeclared = true))
                    )
                )
            ),
            openAPI
        )

        val operation = openAPI.paths["/test/{id}/{customerId}"]!!.get
        val parameters = operation.parameters.associateBy { it.name }
        parameters["id"]!!.schema.type.assert().isNull()
        parameters["customerId"]!!.schema.type.assert().isNull()
        parameters["customerId"]!!.schema.format.assert().isEqualTo("int32")
        operation.requestBody.content.assert().isEmpty()
        operation.responses[Https.Code.OK]!!.content.assert().isEmpty()
    }

    @Test
    fun `should not render empty response headers and content`() {
        val openAPI = OpenAPI()

        OpenApiRenderer().render(
            RouteCatalog(listOf(route())),
            openAPI
        )

        val response = openAPI.paths["/test"]!!.get.responses[Https.Code.OK]!!
        response.headers.assert().isNull()
        response.content.assert().isNull()
    }

    @Test
    fun `should render supported http methods`() {
        val openAPI = OpenAPI()
        val methods = listOf(
            Https.Method.GET,
            Https.Method.POST,
            Https.Method.PUT,
            Https.Method.DELETE,
            Https.Method.PATCH,
            Https.Method.OPTIONS,
            Https.Method.HEAD,
            Https.Method.TRACE
        )

        OpenApiRenderer().render(
            RouteCatalog(
                methods.map { method ->
                    route(
                        routeId = "test.$method",
                        method = method,
                        path = "/test/$method"
                    )
                }
            ),
            openAPI
        )

        openAPI.paths["/test/${Https.Method.GET}"]!!.get.operationId.assert().isEqualTo("test.GET")
        openAPI.paths["/test/${Https.Method.POST}"]!!.post.operationId.assert().isEqualTo("test.POST")
        openAPI.paths["/test/${Https.Method.PUT}"]!!.put.operationId.assert().isEqualTo("test.PUT")
        openAPI.paths["/test/${Https.Method.DELETE}"]!!.delete.operationId.assert().isEqualTo("test.DELETE")
        openAPI.paths["/test/${Https.Method.PATCH}"]!!.patch.operationId.assert().isEqualTo("test.PATCH")
        openAPI.paths["/test/${Https.Method.OPTIONS}"]!!.options.operationId.assert().isEqualTo("test.OPTIONS")
        openAPI.paths["/test/${Https.Method.HEAD}"]!!.head.operationId.assert().isEqualTo("test.HEAD")
        openAPI.paths["/test/${Https.Method.TRACE}"]!!.trace.operationId.assert().isEqualTo("test.TRACE")
    }

    private fun route(
        routeId: String = "test.route",
        method: String = Https.Method.GET,
        path: String = "/test",
        summary: String = "",
        description: String = "",
        pathSummary: String = summary,
        pathDescription: String = description,
        parameters: List<HttpParameter> = emptyList(),
        requestBody: HttpRequestBody? = null,
        responses: List<HttpResponse> = listOf(HttpResponse(statusCode = Https.Code.OK)),
        tags: List<HttpTag> = listOf(HttpTag("test"))
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeId,
            method = method,
            path = path,
            handlerKey = routeId,
            summary = summary,
            description = description,
            pathSummary = pathSummary,
            pathDescription = pathDescription,
            parameters = parameters,
            requestBody = requestBody,
            responses = responses,
            tags = tags
        )
    }

    private data class TypeRefFixture(val value: String = "")
}

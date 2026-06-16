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

package me.ahoo.wow.openapi.migration

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpSchema
import org.junit.jupiter.api.Test

internal class RouteSpecContractAdapterTest {
    private val currentContext = MaterializedNamedBoundedContext("example-service")

    @Test
    fun `should adapt router specs to route catalog`() {
        val routerSpecs = RouterSpecs(currentContext).build()

        val catalog = routerSpecs.toRouteCatalog()

        catalog.routes.assert().hasSize(routerSpecs.count())
        catalog.routes.all { it.handlerKey.isNotBlank() }.assert().isTrue()
    }

    @Test
    fun `should resolve path variable parameters for route catalog validation`() {
        val catalog = RouterSpecs(currentContext).build().toRouteCatalog()

        val routesWithPathVariables = catalog.routes.filter { it.path.contains("{") }

        routesWithPathVariables.assert().isNotEmpty()
        routesWithPathVariables.forEach { route ->
            val templateVariables = Regex("\\{([^}]+)}")
                .findAll(route.path)
                .map { it.groupValues[1] }
                .toSet()
            val pathParameters = route.parameters
                .filter { it.location == HttpParameterLocation.PATH }
                .map { it.name }
                .toSet()
            pathParameters.assert().isEqualTo(templateVariables)
        }
    }

    @Test
    fun `should preserve direct REST contract shape`() {
        val context = OpenAPIComponentContext.default(inline = true)
        val routeSpec = object : RouteSpec {
            override val id: String = "test.route"
            override val path: String = "/test/{id}"
            override val method: String = Https.Method.POST
            override val summary: String = "Test route"
            override val tags: List<Tag> = listOf(Tag().name("test"))
            override val accept: List<String> = listOf(Https.MediaType.APPLICATION_JSON)
            override val parameters: List<Parameter> = listOf(
                Parameter()
                    .name("id")
                    .`in`(ParameterIn.PATH.toString())
                    .schema(StringSchema()),
                Parameter()
                    .name("filter")
                    .`in`(ParameterIn.QUERY.toString())
                    .schema(StringSchema()),
                Parameter()
                    .name("X-Test")
                    .`in`(ParameterIn.HEADER.toString())
                    .schema(StringSchema())
            )
            override val requestBody: RequestBody = RequestBody()
                .content(
                    Content().addMediaType(
                        Https.MediaType.APPLICATION_JSON,
                        MediaType().schema(StringSchema())
                    )
                )
            override val responses: ApiResponses = ApiResponses()
                .addApiResponse(
                    Https.Code.OK,
                    ApiResponse().content(
                        Content().addMediaType(
                            Https.MediaType.TEXT_EVENT_STREAM,
                            MediaType().schema(StringSchema())
                        )
                    )
                )
        }

        val contract = RouteSpecContractAdapter(context).toContract(routeSpec)

        contract.routeId.assert().isEqualTo(routeSpec.id)
        contract.method.assert().isEqualTo(routeSpec.method)
        contract.path.assert().isEqualTo(routeSpec.path)
        contract.accept.assert().isEqualTo(routeSpec.accept)
        contract.parameters.map { it.location to it.name }.assert().isEqualTo(
            listOf(
                HttpParameterLocation.PATH to "id",
                HttpParameterLocation.QUERY to "filter",
                HttpParameterLocation.HEADER to "X-Test"
            )
        )
        contract.requestBody!!.content.map { it.mediaType }
            .assert().isEqualTo(listOf(Https.MediaType.APPLICATION_JSON))
        contract.responses.map { it.statusCode }.assert().isEqualTo(listOf(Https.Code.OK))
        contract.produce.assert().isEqualTo(listOf(Https.MediaType.TEXT_EVENT_STREAM))
    }

    @Test
    fun `should preserve schema component references`() {
        val context = OpenAPIComponentContext.default(inline = true)
        val routeSpec = object : RouteSpec {
            override val id: String = "test.schema_refs"
            override val path: String = "/test/{id}"
            override val method: String = Https.Method.POST
            override val summary: String = "Schema refs"
            override val parameters: List<Parameter> = listOf(
                Parameter()
                    .name("id")
                    .`in`(ParameterIn.PATH.toString())
                    .schema(schemaRef("PathParameterSchema")),
                Parameter()
                    .name("filter")
                    .`in`(ParameterIn.QUERY.toString())
                    .schema(schemaRef("ParameterSchema"))
            )
            override val requestBody: RequestBody = RequestBody()
                .content(
                    Content().addMediaType(
                        Https.MediaType.APPLICATION_JSON,
                        MediaType().schema(schemaRef("RequestBodySchema"))
                    )
                )
            override val responses: ApiResponses = ApiResponses()
                .addApiResponse(
                    Https.Code.OK,
                    ApiResponse()
                        .addHeaderObject("X-Result", Header().schema(schemaRef("ResponseHeaderSchema")))
                        .content(
                            Content()
                                .addMediaType(
                                    Https.MediaType.APPLICATION_JSON,
                                    MediaType().schema(schemaRef("ResponseContentSchema"))
                                )
                                .addMediaType(
                                    Https.MediaType.TEXT_EVENT_STREAM,
                                    MediaType().schema(ArraySchema().items(schemaRef("ArrayItemSchema")))
                                )
                        )
                )
        }

        val contract = RouteSpecContractAdapter(context).toRouteCatalog(listOf(routeSpec)).routes.single()

        contract.parameters.first { it.name == "id" }.schema.assert()
            .isEqualTo(HttpSchema.ComponentRef("PathParameterSchema"))
        contract.parameters.first { it.name == "filter" }.schema.assert()
            .isEqualTo(HttpSchema.ComponentRef("ParameterSchema"))
        contract.requestBody!!.content.single().schema.assert()
            .isEqualTo(HttpSchema.ComponentRef("RequestBodySchema"))
        val response = contract.responses.single()
        response.headers.single().schema.assert()
            .isEqualTo(HttpSchema.ComponentRef("ResponseHeaderSchema"))
        response.content.first { it.mediaType == Https.MediaType.APPLICATION_JSON }.schema.assert()
            .isEqualTo(HttpSchema.ComponentRef("ResponseContentSchema"))
        response.content.first { it.mediaType == Https.MediaType.TEXT_EVENT_STREAM }.schema.assert()
            .isEqualTo(HttpSchema.Array(HttpSchema.ComponentRef("ArrayItemSchema")))
    }

    @Test
    fun `should preserve operation component references`() {
        val context = OpenAPIComponentContext.default(inline = false)
        val routeSpec = object : RouteSpec {
            override val id: String = "test.operation_refs"
            override val path: String = "/test/{id}"
            override val method: String = Https.Method.POST
            override val summary: String = "Operation refs"
            override val parameters: List<Parameter> = listOf(
                context.parameter("PathParameter") {
                    name("id")
                    `in`(ParameterIn.PATH.toString())
                    schema(StringSchema())
                }
            )
            override val requestBody: RequestBody = context.requestBody("TestRequestBody") {
                content(Https.MediaType.APPLICATION_JSON, StringSchema())
            }
            override val responses: ApiResponses = ApiResponses()
                .addApiResponse(
                    Https.Code.OK,
                    context.response("TestResponse") {
                        header(
                            "X-Result",
                            context.header("ResultHeader") {
                                schema(StringSchema())
                            }
                        )
                        content(Https.MediaType.APPLICATION_JSON, StringSchema())
                    }
                )
        }

        val contract = RouteSpecContractAdapter(context).toContract(routeSpec)

        val parameter = contract.parameters.single()
        parameter.componentRef.assert().isEqualTo("PathParameter")
        parameter.name.assert().isEqualTo("id")
        parameter.location.assert().isEqualTo(HttpParameterLocation.PATH)
        contract.requestBody!!.componentRef.assert().isEqualTo("TestRequestBody")
        val response = contract.responses.single()
        response.componentRef.assert().isEqualTo("TestResponse")
        response.headers.single().componentRef.assert().isEqualTo("ResultHeader")
    }

    private fun schemaRef(key: String): Schema<Any> {
        return Schema<Any>().also {
            it.`$ref` = "#/components/schemas/$key"
        }
    }
}

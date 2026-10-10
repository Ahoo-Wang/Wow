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

package me.ahoo.wow.openapi.contract

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contributor.DefaultRouteContributors
import me.ahoo.wow.rest.WowHeaders
import org.junit.jupiter.api.Test

internal class WowComponentsTest {
    private val namedContext = MaterializedNamedBoundedContext("example-service")

    @Test
    fun `should expose the instances the built-in routes use`() {
        WowComponents.errorCodeHeaderComponent.assert().isSameAs(CommonComponents.ERROR_CODE_HEADER)
        WowComponents.errorCodeHeader.assert().isSameAs(CommonComponents.errorCodeHeader)
        WowComponents.badRequestResponse.assert().isSameAs(CommonComponents.badRequestResponse)
        WowComponents.notFoundResponse.assert().isSameAs(CommonComponents.notFoundResponse)
        WowComponents.requestTimeoutResponse.assert().isSameAs(CommonComponents.requestTimeoutResponse)
        WowComponents.tooManyRequestsResponse.assert().isSameAs(CommonComponents.tooManyRequestsResponse)
        WowComponents.unsupportedMediaTypeResponse.assert().isSameAs(CommonComponents.unsupportedMediaTypeResponse)
        WowComponents.spaceIdHeaderParameter.assert().isSameAs(CommonComponents.spaceIdHeaderParameter)
        WowComponents.idPathParameter.assert().isSameAs(CommonComponents.idPathParameter)
        WowComponents.tenantIdPathParameter.assert().isSameAs(CommonComponents.tenantIdPathParameter)
        WowComponents.ownerIdPathParameter.assert().isSameAs(CommonComponents.ownerIdPathParameter)
        WowComponents.versionPathParameter.assert().isSameAs(CommonComponents.versionPathParameter)
    }

    @Test
    fun `should describe the shared components`() {
        WowComponents.errorCodeHeaderComponent.key.assert().isEqualTo("wow.Wow-Error-Code")
        WowComponents.errorCodeHeader.name.assert().isEqualTo(WowHeaders.ERROR_CODE)
        listOf(
            WowComponents.badRequestResponse,
            WowComponents.notFoundResponse,
            WowComponents.requestTimeoutResponse,
            WowComponents.tooManyRequestsResponse,
            WowComponents.unsupportedMediaTypeResponse
        ).map { it.statusCode to it.component?.key }.assert().containsExactly(
            "400" to "wow.BadRequest",
            "404" to "wow.NotFound",
            "408" to "wow.RequestTimeout",
            "429" to "wow.TooManyRequests",
            "415" to "wow.UnsupportedMediaType"
        )
        WowComponents.spaceIdHeaderParameter.location.assert().isEqualTo(HttpParameterLocation.HEADER)
        listOf(
            WowComponents.idPathParameter,
            WowComponents.tenantIdPathParameter,
            WowComponents.ownerIdPathParameter,
            WowComponents.versionPathParameter
        ).forEach {
            it.location.assert().isEqualTo(HttpParameterLocation.PATH)
            it.required.assert().isTrue()
        }
    }

    @Test
    fun `a custom contributor referencing the built-in components should render references to them`() {
        val reportResponse = HttpComponent.response("example.ReportResponse") { context ->
            description("report")
            header(WowHeaders.ERROR_CODE, context.ref(WowComponents.errorCodeHeaderComponent))
        }
        val contributor = reportContributor(
            path = "/report/{id}",
            parameters = listOf(WowComponents.idPathParameter, WowComponents.spaceIdHeaderParameter),
            responses = listOf(
                HttpResponse("200", component = reportResponse),
                WowComponents.badRequestResponse,
                WowComponents.notFoundResponse
            )
        )
        val builtInOnly = OpenAPI()
        RouterSpecs(namedContext).mergeOpenAPI(builtInOnly)
        val openAPI = OpenAPI()
        RouterSpecs(namedContext, routeContributors = DefaultRouteContributors.all() + contributor)
            .mergeOpenAPI(openAPI)

        val operation = openAPI.paths.getValue("/report/{id}").get
        operation.parameters.map { it.`$ref` }.assert().containsExactly(
            "#/components/parameters/wow.id",
            "#/components/parameters/wow.Wow-Space-Id"
        )
        operation.responses.getValue("400").`$ref`.assert().isEqualTo("#/components/responses/wow.BadRequest")
        operation.responses.getValue("404").`$ref`.assert().isEqualTo("#/components/responses/wow.NotFound")
        val components = openAPI.components
        components.responses.getValue("example.ReportResponse").headers.getValue(WowHeaders.ERROR_CODE).`$ref`
            .assert().isEqualTo("#/components/headers/wow.Wow-Error-Code")
        val builtIn = builtInOnly.components
        components.responses.getValue("wow.BadRequest").assert()
            .isEqualTo(builtIn.responses.getValue("wow.BadRequest"))
        components.headers.getValue("wow.Wow-Error-Code").assert()
            .isEqualTo(builtIn.headers.getValue("wow.Wow-Error-Code"))
        components.parameters.getValue("wow.id").assert().isEqualTo(builtIn.parameters.getValue("wow.id"))
    }

    @Test
    fun `a custom contributor alone should register the built-in components it references`() {
        val contributor = reportContributor(path = "/report", responses = listOf(WowComponents.tooManyRequestsResponse))
        val openAPI = OpenAPI()
        RouterSpecs(namedContext, routeContributors = listOf(contributor)).mergeOpenAPI(openAPI)

        val response = openAPI.components.responses.getValue("wow.TooManyRequests")
        response.description.assert().isEqualTo("Too Many Requests")
        response.headers.getValue(WowHeaders.ERROR_CODE).`$ref`.assert()
            .isEqualTo("#/components/headers/wow.Wow-Error-Code")
        openAPI.components.headers.assert().containsKey("wow.Wow-Error-Code")
    }

    @Test
    fun `a custom contributor referencing the built-in components should render when inlining`() {
        // The built-in aggregate routes cannot render with all schemas inlined (recursive query types), so a
        // contributor referencing the internal instance stands in for them.
        val builtIn = reportContributor(path = "/built-in", responses = listOf(CommonComponents.badRequestResponse))
        val contributor = reportContributor(path = "/report", responses = listOf(WowComponents.badRequestResponse))
        val openAPI = OpenAPI()
        RouterSpecs(
            namedContext,
            OpenAPIComponentContext.default(true),
            listOf(builtIn, contributor)
        ).mergeOpenAPI(openAPI)

        listOf("/built-in", "/report").forEach { path ->
            val response = openAPI.paths.getValue(path).get.responses.getValue("400")
            response.`$ref`.assert().isNull()
            response.description.assert().isEqualTo("Bad Request")
            response.headers.assert().containsKey(WowHeaders.ERROR_CODE)
        }
    }

    private fun reportContributor(
        path: String,
        parameters: List<HttpParameter> = emptyList(),
        responses: List<HttpResponse>
    ): RouteContributor = object : RouteContributor {
        override fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> = listOf(
            HttpRouteContract(
                routeId = "example$path.get",
                method = "GET",
                path = path,
                handlerKey = "example.report",
                parameters = parameters,
                responses = responses
            )
        )
    }
}

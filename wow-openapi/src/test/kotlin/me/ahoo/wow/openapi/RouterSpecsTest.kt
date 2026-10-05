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

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs.Companion.DEFAULT_OPENAPI_INFO_TITLE
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.junit.jupiter.api.Test
import java.lang.reflect.Type

internal class RouterSpecsTest {

    private val namedContext = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should build and return non-empty routes`() {
        val routerSpecs = RouterSpecs(namedContext).build()
        routerSpecs.assert().isNotNull()
    }

    @Test
    fun `building the route catalog generates no schema and registers no component`() {
        val componentContext = CountingComponentContext(
            OpenAPIComponentContext.default(false, defaultSchemaNamePrefix = namedContext.getContextAliasPrefix())
        )
        val routerSpecs = RouterSpecs(namedContext, componentContext).build()

        routerSpecs.toRouteCatalog().routes.assert().isNotEmpty()
        componentContext.calls.assert().isZero()
        componentContext.schemas.assert().isEmpty()

        val openAPI = OpenAPI()
        routerSpecs.mergeOpenAPIFromCatalog(openAPI)
        componentContext.calls.assert().isPositive()
        openAPI.components.schemas.assert().isNotEmpty()
        openAPI.paths.keys.assert().containsExactlyInAnyOrderElementsOf(
            routerSpecs.toRouteCatalog().routes.map { it.path }.toSet()
        )
    }

    @Test
    fun `should dispatch the example routes in catalog order`() {
        // Their overlaps are either already ordered (state/tracing before state/{version}) or crossing
        // (snapshot/{afterId}/{limit} and {id}/{version}/compensate), so no example request changes destination.
        val catalog = RouterSpecs(namedContext).build().toRouteCatalog()
        catalog.dispatchRoutes.assert().isEqualTo(catalog.routes)
    }

    @Test
    fun `should merge router specs into open api with context name as title`() {
        val openAPI = OpenAPI()
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)
        openAPI.info?.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should keep existing info when merging`() {
        val info = Info().title("Custom Title")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo("Custom Title")
    }

    @Test
    fun `should replace default info title when merging`() {
        val info = Info().title(DEFAULT_OPENAPI_INFO_TITLE).description("hello")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should keep custom info title when merging`() {
        val info = Info().title(generateGlobalId())
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)
        openAPI.info.assert().isSameAs(info)
    }

    @Test
    fun `should merge into existing open api preserving paths and components`() {
        val info = Info()
        val paths = Paths()
        val components = Components()
        val openAPI = OpenAPI().info(info).paths(paths).components(components)
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.paths.assert().isSameAs(paths)
        openAPI.components.assert().isSameAs(components)
    }

    @Test
    fun `should merge route catalog into open api preserving metadata and components`() {
        val info = Info().title("Custom Title").description("Custom Description")
        val paths = Paths()
        val components = Components()
        val openAPI = OpenAPI().info(info).paths(paths).components(components)

        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(openAPI)

        openAPI.specVersion.assert().isEqualTo(SpecVersion.V31)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo("Custom Title")
        openAPI.info.description.assert().isEqualTo("Custom Description")
        openAPI.paths.assert().isSameAs(paths)
        openAPI.components.assert().isSameAs(components)
        openAPI.paths.assert().isNotEmpty()
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun `catalog merge should expose route summaries and descriptions`() {
        val catalogOpenAPI = OpenAPI()

        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(catalogOpenAPI)

        val (_, pathItem) = catalogOpenAPI.paths.entries.first { (_, pathItem) ->
            pathItem.summary.isNotNullOrBlank() || pathItem.description.isNotNullOrBlank()
        }
        (pathItem.summary.isNotNullOrBlank() || pathItem.description.isNotNullOrBlank()).assert().isTrue()

        val (_, _, operation) = catalogOpenAPI.paths.entries.asSequence()
            .flatMap { (pathName, pathItem) ->
                pathItem.operations().asSequence().map { (method, operation) ->
                    Triple(pathName, method, operation)
                }
            }
            .first { (_, _, operation) ->
                operation.summary.isNotNullOrBlank() || operation.description.isNotNullOrBlank()
            }
        (operation.summary.isNotNullOrBlank() || operation.description.isNotNullOrBlank()).assert().isTrue()
    }

    @Test
    fun `should build catalog from explicit contributors without legacy service loader`() {
        val contributor = object : RouteContributor {
            override val id: String = "test-global"
            override val category: RouteCategory = RouteCategory.GLOBAL
            override val order: Int = 0

            override fun contributeGlobal(
                currentContext: NamedBoundedContext,
                componentContext: OpenAPIComponentContext
            ): List<HttpRouteContract> {
                return listOf(
                    HttpRouteContract(
                        routeId = "test-global",
                        method = Https.Method.GET,
                        path = "/test-global",
                        handlerKey = "test-global"
                    )
                )
            }
        }

        val routerSpecs = RouterSpecs(namedContext, routeContributors = listOf(contributor)).build()
        val catalog = routerSpecs.toRouteCatalog()

        catalog.routes.map { it.routeId }.assert().isEqualTo(listOf("test-global"))
    }

    @Test
    fun `should materialize route catalog once and reuse it`() {
        val contributor = CountingRouteContributor()

        val routerSpecs = RouterSpecs(namedContext, routeContributors = listOf(contributor)).build()
        val firstCatalog = routerSpecs.toRouteCatalog()
        val secondCatalog = routerSpecs.toRouteCatalog()

        firstCatalog.assert().isSameAs(secondCatalog)
        contributor.globalContributions.assert().isEqualTo(1)
    }

    @Test
    fun `catalog merge should finish components after explicit contributors run`() {
        val contributor = object : RouteContributor {
            override val id: String = "component-lifecycle"
            override val category: RouteCategory = RouteCategory.GLOBAL
            override val order: Int = 0

            override fun contributeGlobal(
                currentContext: NamedBoundedContext,
                componentContext: OpenAPIComponentContext
            ): List<HttpRouteContract> {
                componentContext.schema(ContributorLifecycleSchema::class.java)
                return listOf(
                    HttpRouteContract(
                        routeId = "component-lifecycle",
                        method = Https.Method.GET,
                        path = "/component-lifecycle",
                        handlerKey = "component-lifecycle"
                    )
                )
            }
        }
        val openAPI = OpenAPI()

        RouterSpecs(namedContext, routeContributors = listOf(contributor)).build().mergeOpenAPIFromCatalog(openAPI)

        openAPI.components.schemas.assert().isNotEmpty()
    }

    private fun String?.isNotNullOrBlank(): Boolean {
        return isNullOrBlank().not()
    }

    private fun PathItem.operations(): List<Pair<String, Operation>> {
        return listOfNotNull(
            get?.let { Https.Method.GET to it },
            post?.let { Https.Method.POST to it },
            put?.let { Https.Method.PUT to it },
            delete?.let { Https.Method.DELETE to it },
            options?.let { Https.Method.OPTIONS to it },
            head?.let { Https.Method.HEAD to it },
            patch?.let { Https.Method.PATCH to it },
            trace?.let { Https.Method.TRACE to it }
        )
    }

    private data class ContributorLifecycleSchema(val value: String = "")

    private class CountingRouteContributor : RouteContributor {
        var globalContributions: Int = 0
            private set
        override val id: String = "counting-global"
        override val category: RouteCategory = RouteCategory.GLOBAL
        override val order: Int = 0

        override fun contributeGlobal(
            currentContext: NamedBoundedContext,
            componentContext: OpenAPIComponentContext
        ): List<HttpRouteContract> {
            globalContributions++
            return listOf(
                HttpRouteContract(
                    routeId = "counting-global",
                    method = Https.Method.GET,
                    path = "/counting-global",
                    handlerKey = "counting-global"
                )
            )
        }
    }
}

/** Counts every call that generates a schema or registers a component. */
private class CountingComponentContext(private val delegate: OpenAPIComponentContext) :
    OpenAPIComponentContext by delegate {
    var calls = 0

    override fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> =
        delegate.schema(mainTargetType, *typeParameters).also { calls++ }

    override fun arraySchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> =
        delegate.arraySchema(mainTargetType, *typeParameters).also { calls++ }

    override fun componentSchema(key: String, schema: Schema<*>): Schema<*> =
        delegate.componentSchema(key, schema).also { calls++ }

    override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter =
        delegate.parameter(key, builder).also { calls++ }

    override fun header(key: String, builder: Header.() -> Unit): Header =
        delegate.header(key, builder).also { calls++ }

    override fun requestBody(key: String, builder: RequestBodyBuilder.() -> Unit): RequestBody =
        delegate.requestBody(key, builder).also { calls++ }

    override fun response(key: String, builder: ApiResponseBuilder.() -> Unit): ApiResponse =
        delegate.response(key, builder).also { calls++ }
}

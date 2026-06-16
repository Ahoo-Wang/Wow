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
import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs.Companion.DEFAULT_OPENAPI_INFO_TITLE
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.junit.jupiter.api.Test

internal class RouterSpecsTest {

    private val namedContext = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should build and return non-empty routes`() {
        val routerSpecs = RouterSpecs(namedContext).build()
        routerSpecs.assert().isNotNull()
    }

    @Test
    fun `should merge router specs into open api with context name as title`() {
        val openAPI = OpenAPI()
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info?.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should keep existing info when merging`() {
        val info = Info().title("Custom Title")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo("Custom Title")
    }

    @Test
    fun `should replace default info title when merging`() {
        val info = Info().title(DEFAULT_OPENAPI_INFO_TITLE).description("hello")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should keep custom info title when merging`() {
        val info = Info().title(generateGlobalId())
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
    }

    @Test
    fun `should merge into existing open api preserving paths and components`() {
        val info = Info()
        val paths = Paths()
        val components = Components()
        val openAPI = OpenAPI().info(info).paths(paths).components(components)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
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
    fun `catalog merge should preserve route descriptions from legacy merge`() {
        val legacyOpenAPI = OpenAPI()
        val catalogOpenAPI = OpenAPI()

        RouterSpecs(namedContext).build().mergeOpenAPI(legacyOpenAPI)
        RouterSpecs(namedContext).build().mergeOpenAPIFromCatalog(catalogOpenAPI)

        val (path, legacyPathItem) = legacyOpenAPI.paths.entries.first { (_, pathItem) ->
            pathItem.summary.isNotNullOrBlank() || pathItem.description.isNotNullOrBlank()
        }
        val catalogPathItem = catalogOpenAPI.paths[path]!!
        catalogPathItem.summary.assert().isEqualTo(legacyPathItem.summary)
        catalogPathItem.description.assert().isEqualTo(legacyPathItem.description)

        val (operationPath, operationMethod, legacyOperation) = legacyOpenAPI.paths.entries.asSequence()
            .flatMap { (pathName, pathItem) ->
                pathItem.operations().asSequence().map { (method, operation) ->
                    Triple(pathName, method, operation)
                }
            }
            .first { (_, _, operation) ->
                operation.summary.isNotNullOrBlank() || operation.description.isNotNullOrBlank()
            }
        val catalogOperation = catalogOpenAPI.paths[operationPath]!!.operation(operationMethod)
        catalogOperation.summary.assert().isEqualTo(legacyOperation.summary)
        catalogOperation.description.assert().isEqualTo(legacyOperation.description)
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
        routerSpecs.iterator().hasNext().assert().isFalse()
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

    private fun PathItem.operation(method: String): Operation {
        return operations().first { (operationMethod, _) -> operationMethod == method }.second
    }

    private data class ContributorLifecycleSchema(val value: String = "")
}

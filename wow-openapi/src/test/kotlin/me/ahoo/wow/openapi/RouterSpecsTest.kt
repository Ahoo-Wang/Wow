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

import io.swagger.v3.core.util.ObjectMapperFactory
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
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.lang.reflect.Type
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RouterSpecsTest {
    private val mapper = ObjectMapperFactory.createJson31()

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
        routerSpecs.mergeOpenAPI(openAPI)
        componentContext.calls.assert().isPositive()
        openAPI.components.schemas.assert().isNotEmpty()
        openAPI.paths.keys.assert().containsExactlyInAnyOrderElementsOf(
            routerSpecs.toRouteCatalog().routes.map { it.path }.toSet()
        )
    }

    @Test
    fun `build documentation should render once`() {
        val componentContext = CountingComponentContext(OpenAPIComponentContext.default(false))
        val routerSpecs = RouterSpecs(namedContext, componentContext).buildDocumentation()
        val calls = componentContext.calls
        calls.assert().isPositive()

        routerSpecs.buildDocumentation()

        componentContext.calls.assert().isEqualTo(calls)
    }

    @Test
    fun `building the route catalog infers no query fields`() {
        val componentContext = CountingComponentContext(OpenAPIComponentContext.default(false))
        val routerSpecs = RouterSpecs(namedContext, componentContext).build()
        val aggregatedQueryBodies = routerSpecs.toRouteCatalog().routes
            .mapNotNull { it.requestBody?.component }
            .filter { it.key.endsWith(QueryComponent.LIST_QUERY_SUFFIX) && it.key != QueryComponent.LIST_QUERY_KEY }
            .distinct()

        aggregatedQueryBodies.assert().isNotEmpty()
        componentContext.componentSchemaKeys.assert().isEmpty()

        val openAPI = OpenAPI()
        routerSpecs.mergeOpenAPI(openAPI)
        componentContext.componentSchemaKeys.assert().isNotEmpty()
        componentContext.componentSchemaKeys.forEach {
            it.assert().endsWith(QueryComponent.AGGREGATED_FIELDS_SUFFIX)
        }
        openAPI.components.requestBodies.getValue(aggregatedQueryBodies.first().key).extensions.assert()
            .containsKey(QueryComponent.QUERY_FIELDS_EXTENSION)
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

        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)

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

        RouterSpecs(namedContext).build().mergeOpenAPI(catalogOpenAPI)

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

            override fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> {
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
    fun `merge should build the referenced components once and reuse them`() {
        var builds = 0
        val component = HttpComponent.response("test.LifecycleResponse") { context ->
            builds++
            content(schema = context.schema(ContributorLifecycleSchema::class.java))
        }
        val routerSpecs = RouterSpecs(
            namedContext,
            routeContributors = listOf(responseContributor(component, "/component-lifecycle", "/component-lifecycle-2"))
        ).build()
        builds.assert().isZero()

        val openAPI = OpenAPI()
        routerSpecs.mergeOpenAPI(openAPI)

        builds.assert().isEqualTo(1)
        openAPI.components.responses.assert().containsKey("test.LifecycleResponse")
        openAPI.components.schemas.keys.any { it.contains("ContributorLifecycleSchema") }.assert().isTrue()
        val first = openAPI.paths["/component-lifecycle"]!!.get.responses[Https.Code.OK]!!
        val second = openAPI.paths["/component-lifecycle-2"]!!.get.responses[Https.Code.OK]!!
        first.`$ref`.assert().isEqualTo("#/components/responses/test.LifecycleResponse")
        first.assert().isNotSameAs(second)

        routerSpecs.mergeOpenAPI(OpenAPI())
        builds.assert().isEqualTo(1)
    }

    @Test
    fun `merge should reject two different components that share a key`() {
        val first = HttpComponent.response("test.Shared") { description("first") }
        val second = HttpComponent.response("test.Shared") { description("second") }
        val routerSpecs = RouterSpecs(
            namedContext,
            routeContributors = listOf(responseContributor(first, "/first"), responseContributor(second, "/second"))
        )

        assertThrows<IllegalStateException> {
            routerSpecs.mergeOpenAPI(OpenAPI())
        }.message.assert().contains("test.Shared")
    }

    @Test
    fun `merge should accept two equal components that share a key`() {
        val first = HttpComponent.response("test.Shared") { description("same") }
        val second = HttpComponent.response("test.Shared") { description("same") }
        val openAPI = OpenAPI()

        RouterSpecs(
            namedContext,
            routeContributors = listOf(responseContributor(first, "/first"), responseContributor(second, "/second"))
        ).mergeOpenAPI(openAPI)

        openAPI.components.responses["test.Shared"]!!.description.assert().isEqualTo("same")
    }

    @Test
    fun `each merge should produce fresh operations`() {
        val routerSpecs = RouterSpecs(namedContext).buildDocumentation()
        val first = OpenAPI()
        val second = OpenAPI()

        routerSpecs.mergeOpenAPI(first)
        routerSpecs.mergeOpenAPI(second)

        val path = first.paths.keys.first()
        first.paths[path]!!.assert().isNotSameAs(second.paths[path])
        first.paths[path]!!.readOperations().first().assert()
            .isNotSameAs(second.paths[path]!!.readOperations().first())
    }

    @Test
    fun `changing a merged operation should not affect later merges`() {
        val routerSpecs = RouterSpecs(namedContext).buildDocumentation()
        val expected = render(routerSpecs)
        val first = OpenAPI()
        routerSpecs.mergeOpenAPI(first)

        first.paths.values.flatMap { it.readOperations() }.forEach { operation ->
            operation.summary = "changed"
            operation.parameters?.forEach { it.description = "changed" }
            operation.responses?.values?.forEach { it.description = "changed" }
            operation.requestBody?.description = "changed"
            operation.responses?.values?.forEach { response -> response.headers?.values?.forEach { it.description = "changed" } }
        }

        render(routerSpecs).assert().isEqualTo(expected)
    }

    @Test
    fun `merges after build documentation should not block`() {
        val routerSpecs = RouterSpecs(namedContext).buildDocumentation()
        val expected = render(routerSpecs)

        val rendered = Mono.fromCallable { render(routerSpecs) }
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofMinutes(1))

        rendered.assert().isEqualTo(expected)
    }

    @Test
    fun `merges after the first should generate no schema`() {
        val componentContext = CountingComponentContext(OpenAPIComponentContext.default(false))
        val routerSpecs = RouterSpecs(namedContext, componentContext)
        routerSpecs.mergeOpenAPI(OpenAPI())
        val calls = componentContext.calls
        val schemas = componentContext.schemas.size

        repeat(5) { routerSpecs.mergeOpenAPI(OpenAPI()) }

        componentContext.calls.assert().isEqualTo(calls)
        componentContext.schemas.size.assert().isEqualTo(schemas)
    }

    @Test
    fun `concurrent merges should all render the single-thread document`() {
        val expected = render(RouterSpecs(namedContext).build())
        val threads = 4
        val rounds = 5
        val executor = Executors.newFixedThreadPool(threads)
        try {
            repeat(rounds) {
                val routerSpecs = RouterSpecs(namedContext).build()
                val start = CountDownLatch(1)
                val renders = (1..threads).map {
                    executor.submit<String> {
                        start.await()
                        render(routerSpecs)
                    }
                }
                start.countDown()
                renders.forEach { it.get(1, TimeUnit.MINUTES).assert().isEqualTo(expected) }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun responseContributor(component: HttpComponent<ApiResponse>, vararg paths: String): RouteContributor {
        return object : RouteContributor {
            override fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> {
                return paths.map { path ->
                    HttpRouteContract(
                        routeId = path,
                        method = Https.Method.GET,
                        path = path,
                        handlerKey = path,
                        responses = listOf(HttpResponse(Https.Code.OK, component = component))
                    )
                }
            }
        }
    }

    private fun render(routerSpecs: RouterSpecs): String {
        val openAPI = OpenAPI()
        routerSpecs.mergeOpenAPI(openAPI)
        return mapper.writeValueAsString(openAPI)
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

        override fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> {
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

    val componentSchemaKeys = mutableListOf<String>()

    override fun componentSchema(key: String, schema: Schema<*>): Schema<*> =
        delegate.componentSchema(key, schema).also {
            calls++
            componentSchemaKeys.add(key)
        }

    override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter =
        delegate.parameter(key, builder).also { calls++ }

    override fun header(key: String, builder: Header.() -> Unit): Header =
        delegate.header(key, builder).also { calls++ }

    override fun requestBody(key: String, builder: RequestBodyBuilder.() -> Unit): RequestBody =
        delegate.requestBody(key, builder).also { calls++ }

    override fun response(key: String, builder: ApiResponseBuilder.() -> Unit): ApiResponse =
        delegate.response(key, builder).also { calls++ }
}

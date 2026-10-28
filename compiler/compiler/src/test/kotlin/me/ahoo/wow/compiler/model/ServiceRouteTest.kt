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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Decision D359: a `@ServiceRoute` function is listed in its context's fragment, with its parameters and the schema
 * references of its body and its answer, and bound in the module's model provider, which calls it on the instance
 * the host's dependencies hold with each parameter read as the function's type.
 */
@OptIn(ExperimentalCompilerApi::class)
class ServiceRouteTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-server",
    )

    private val routes = SourceFile.kotlin(
        "Routes.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.RouteMethod
        import me.ahoo.wow.api.annotation.RouteQuery
        import me.ahoo.wow.api.annotation.ServiceRoute
        import me.ahoo.wow.api.route.RouteCall

        enum class Period { DAY, WEEK }
        data class Summary(val day: String, val period: Period?, val limit: Int)
        data class Note(val text: String)

        /** The shop's own routes. */
        class ShopRoutes {
            /** The summary of a day. */
            @ServiceRoute(method = RouteMethod.GET, path = "summaries/{day}", errors = ["shop.summary.closed"])
            suspend fun summary(call: RouteCall, day: String, @RouteQuery period: Period?, @RouteQuery limit: Int): Summary =
                Summary(day + "@" + call.tenantId, period, limit)

            @ServiceRoute(method = RouteMethod.POST, path = "notes", name = "addNote")
            suspend fun add(note: Note) = Unit
        }
        """.trimIndent(),
    )

    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        package sample

        import kotlin.reflect.KClass
        import kotlin.reflect.KType
        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.aggregate.Scope
        import me.ahoo.wow.api.caller.Caller
        import me.ahoo.wow.api.client.CommandClient
        import me.ahoo.wow.api.route.RouteCall
        import me.ahoo.wow.kernel.Dependencies
        import me.ahoo.wow.kernel.route.RouteRequest

        class Request(private val values: Map<String, String>) : RouteRequest {
            @Suppress("UNCHECKED_CAST")
            private fun <T : Any> read(value: String, type: KClass<T>): T = when (type) {
                Int::class -> value.toInt() as T
                Period::class -> Period.valueOf(value) as T
                else -> value as T
            }
            override fun <T : Any> path(name: String, type: KClass<T>): T = read(values.getValue(name), type)
            override fun <T : Any> query(name: String, type: KClass<T>): T? = values[name]?.let { read(it, type) }
            @Suppress("UNCHECKED_CAST")
            override fun <T> body(type: KType): T = Note(values.getValue("body")) as T
        }

        object Call : RouteCall {
            @OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)
            override val caller: Caller = Caller(tenantId = "t1")
            override val tenantId: String = "t1"
            override val scope: Scope = Scope.NONE
            override val requestId: String = "r1"
            override val commands: CommandClient get() = error("no commands")
        }

        fun run(): String = runBlocking {
            val handlers = ShopServerModelProvider().routes(Dependencies.of(ShopRoutes()))
            val summary = handlers.single { it.name == "summary" }
            listOf(
                handlers.map { it.context + "." + it.name }.toString(),
                summary.handle(Call, Request(mapOf("day" to "mon", "period" to "WEEK", "limit" to "3"))).toString(),
                summary.handle(Call, Request(mapOf("day" to "mon", "limit" to "3"))).toString(),
                runCatching { summary.handle(Call, Request(mapOf("day" to "mon"))) }.exceptionOrNull()?.message.toString(),
                handlers.single { it.name == "addNote" }.handle(Call, Request(mapOf("body" to "hi"))).toString(),
            ).joinToString("\n")
        }
        """.trimIndent(),
    )

    @Test
    fun `a service route is listed in the fragment and bound in the model provider`() {
        val (compilation, result) = compileModel("generate", options, routes, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null).assert().isEqualTo(
            listOf(
                "[shop.addNote, shop.summary]",
                "Summary(day=mon@t1, period=WEEK, limit=3)",
                "Summary(day=mon@t1, period=null, limit=3)",
                "The query parameter limit is required.",
                "kotlin.Unit",
            ).joinToString("\n"),
        )
        val file = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-server.json" }
        val context = ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java)).contexts.single()
        context.aggregates.assert().isEmpty()
        context.routes.assert().containsExactly(
            ServiceRouteManifest(
                name = "addNote",
                route = RouteManifest("POST", "/shop/notes"),
                request = "#/\$defs/sample.Note",
            ),
            ServiceRouteManifest(
                name = "summary",
                route = RouteManifest("GET", "/shop/summaries/{day}"),
                parameters = listOf(
                    RouteParameterManifest("day", RouteParameterManifest.Location.PATH, mapOf("type" to "string")),
                    RouteParameterManifest(
                        "period",
                        RouteParameterManifest.Location.QUERY,
                        mapOf("type" to "string", "enum" to listOf("DAY", "WEEK")),
                        required = false,
                    ),
                    RouteParameterManifest(
                        "limit",
                        RouteParameterManifest.Location.QUERY,
                        mapOf("type" to "integer", "format" to "int32"),
                        required = true,
                    ),
                ),
                response = "#/\$defs/sample.Summary",
                errors = listOf("shop.summary.closed"),
                description = "The summary of a day.",
            ),
        )
    }

    @Test
    fun `a route that binds wrongly, streams or answers a collection should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import kotlinx.coroutines.flow.Flow
            import me.ahoo.wow.api.annotation.RouteMethod
            import me.ahoo.wow.api.annotation.RouteQuery
            import me.ahoo.wow.api.annotation.ServiceRoute

            data class Item(val id: String)

            class Broken {
                @ServiceRoute(method = RouteMethod.GET, path = "items/{id}")
                suspend fun unbound(other: String): Item = Item(other)

                @ServiceRoute(method = RouteMethod.GET, path = "items")
                suspend fun list(): List<Item> = emptyList()

                @ServiceRoute(method = RouteMethod.GET, path = "stream")
                suspend fun stream(): Flow<Item> = error("no")

                @ServiceRoute(method = RouteMethod.GET, path = "/lead")
                fun blocking(@RouteQuery item: Item): Item = item
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("path variable {id} binds no parameter")
            .contains("parameter other binds nothing, and a GET takes no body")
            .contains("its answer is a kotlin.collections.List<sample.Item>")
            .contains("its answer is a kotlinx.coroutines.flow.Flow<sample.Item>")
            .contains("Broken.blocking is not a suspend function")
            .contains("has path '/lead'")
            .contains("query parameter item is a sample.Item")
    }
}

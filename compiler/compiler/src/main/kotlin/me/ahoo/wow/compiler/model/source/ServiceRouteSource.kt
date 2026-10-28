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

package me.ahoo.wow.compiler.model.source

import me.ahoo.wow.compiler.model.route.RouteArgument
import me.ahoo.wow.compiler.model.route.ServiceRouteBinding

/**
 * Renders a module's `routes`: one `me.ahoo.wow.kernel.route.ServiceRouteHandler` per `@ServiceRoute`
 * function, calling it on the instance of its class the host's `Dependencies` holds, with each parameter read from
 * the request as the function's type, so the edge reflects on nothing.
 */
internal object ServiceRouteSource {
    private const val HANDLER = "me.ahoo.wow.kernel.route.ServiceRouteHandler"
    private const val MALFORMED = "me.ahoo.wow.kernel.route.MalformedRouteRequestException"

    fun render(context: String, routes: List<ServiceRouteBinding>): String = buildString {
        val handlers = routes.map { it.handlerType }.distinct().sorted()
        appendLine("    override fun routes(")
        appendLine("        dependencies: me.ahoo.wow.kernel.Dependencies,")
        appendLine("    ): List<$HANDLER> {")
        handlers.forEachIndexed { index, type ->
            appendLine(
                "        val handler$index = dependencies.get($type::class.java, null, " +
                    "\"the service routes of ${escape(type)}\")",
            )
        }
        appendLine("        return listOf(")
        routes.sortedBy { it.name }.forEach { route ->
            val handler = "handler${handlers.indexOf(route.handlerType)}"
            appendLine("            object : $HANDLER {")
            appendLine("                override val context: String = \"${escape(context)}\"")
            appendLine()
            appendLine("                override val name: String = \"${escape(route.name)}\"")
            appendLine()
            appendLine("                override suspend fun handle(")
            appendLine("                    call: me.ahoo.wow.api.route.RouteCall,")
            appendLine("                    request: me.ahoo.wow.kernel.route.RouteRequest,")
            appendLine("                ): Any? = $handler.${route.function}(")
            route.arguments.forEach { appendLine("                    ${argument(it)},") }
            appendLine("                )")
            appendLine("            },")
        }
        appendLine("        )")
        appendLine("    }")
    }

    private fun argument(argument: RouteArgument): String = when (argument) {
        RouteArgument.Call -> "call"
        is RouteArgument.Path -> "request.path(\"${escape(argument.name)}\", ${argument.type}::class)"
        is RouteArgument.Query -> {
            val read = "request.query(\"${escape(argument.name)}\", ${argument.type}::class)"
            if (argument.required) {
                "$read ?: throw $MALFORMED(\"${escape(argument.name)}\", " +
                    "\"The query parameter ${escape(argument.name)} is required.\")"
            } else {
                read
            }
        }
        is RouteArgument.Body -> "request.body<${argument.type}>(kotlin.reflect.typeOf<${argument.type}>())"
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}

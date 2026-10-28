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

package me.ahoo.wow.compiler.model.route

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.RouteMethod
import me.ahoo.wow.api.annotation.RouteQuery
import me.ahoo.wow.api.annotation.ServiceRoute
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.classOf
import me.ahoo.wow.compiler.model.symbol.render
import me.ahoo.wow.manifest.Manifest

/** A parameter of a service route's function, as its binding passes it. */
internal sealed interface RouteArgument {
    /** The path variable [name], read as [type] (a Kotlin type, `kotlin.Int`). */
    data class Path(val name: String, val type: String) : RouteArgument

    /** The query parameter [name], read as [type]; [required] when the parameter is not nullable. */
    data class Query(val name: String, val type: String, val required: Boolean) : RouteArgument

    /** The request body, of [type] (rendered with its arguments). */
    data class Body(val type: String) : RouteArgument

    /** The call. */
    data object Call : RouteArgument
}

/**
 * A `@ServiceRoute` function: its class ([handler], a binary name for the Manifest and [handlerType] for the code),
 * the route's [name], [method] and [path] (below the context), the function's [arguments] in their order, the schema
 * [request] and [response] (inline schemas: a class's `$ref`, or an array of one; null for none, `Unit` answering 204),
 * and the [errors] it lists.
 */
internal data class ServiceRouteBinding(
    val handler: String,
    val handlerType: String,
    val function: String,
    val name: String,
    val method: RouteMethod,
    val path: String,
    val arguments: List<RouteArgument>,
    val parameterSchemas: Map<String, Map<String, Any?>>,
    val request: Map<String, Any?>?,
    val response: Map<String, Any?>?,
    val errors: List<String>,
    val description: String?,
    val deprecated: Boolean,
    val problems: List<String>,
)

/**
 * Reads `@ServiceRoute` functions: a `suspend` member of a class, whose parameters are path
 * variables (named as in the path), `@RouteQuery` parameters, at most one [RouteCall] and, on a method with a body,
 * one body; scalars only in the path and the query; a body and an answer of a class, or a `List`, a `Set` or a
 * `Collection` of one, or a Jackson tree (`ObjectNode`, `JsonNode`) for one of any shape; never a map, a scalar or a
 * stream (`Flow`, a `Publisher`); `Unit` answers 204.
 */
internal object ServiceRouteResolver {
    val SERVICE_ROUTE: String = ServiceRoute::class.java.name
    private val ROUTE_QUERY: String = RouteQuery::class.java.name
    private val ROUTE_CALL: String = RouteCall::class.java.name
    private val VARIABLE = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
    private val SEGMENT = Regex("[a-z0-9]+(-[a-z0-9]+)*|\\{[A-Za-z][A-Za-z0-9]*}")
    private val NAME = Regex("[a-z][A-Za-z0-9]*")
    private const val SET = "kotlin.collections.Set"
    private val COLLECTIONS = setOf("kotlin.collections.List", SET, "kotlin.collections.Collection")

    /**
     * Jackson's trees, a body or an answer of any shape, such as a relayed command's: a JSON object for an
     * `ObjectNode`, any JSON value for a `JsonNode`.
     */
    private val TREES: Map<String, Map<String, Any?>> = listOf("tools.jackson.databind", "com.fasterxml.jackson.databind")
        .flatMap { listOf("$it.node.ObjectNode" to mapOf("type" to "object"), "$it.JsonNode" to emptyMap()) }
        .toMap()

    /** The JSON Schema of each scalar a path variable or a query parameter may be, by Kotlin type. */
    private val SCALARS: Map<String, Map<String, Any?>> = mapOf(
        "kotlin.String" to mapOf("type" to "string"),
        "kotlin.Int" to mapOf("type" to "integer", "format" to "int32"),
        "kotlin.Long" to mapOf("type" to "integer", "format" to "int64"),
        "kotlin.Double" to mapOf("type" to "number"),
        "kotlin.Boolean" to mapOf("type" to "boolean"),
    )

    /** Types an answer or a body may not be: a collection, a map, a stream or a scalar has no schema of its own. */
    private val NOT_A_SCHEMA = setOf(
        "kotlin.collections.Map",
        "kotlin.Array",
        "kotlinx.coroutines.flow.Flow",
        "org.reactivestreams.Publisher",
        "reactor.core.publisher.Flux",
        "reactor.core.publisher.Mono",
        "kotlin.Any",
    ) + SCALARS.keys

    fun read(function: KSFunctionDeclaration): ServiceRouteBinding {
        val annotation = function.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == SERVICE_ROUTE
        }
        val argument = { name: String -> annotation.arguments.firstOrNull { it.name?.asString() == name }?.value }
        val method = when (val value = argument("method")) {
            is KSType -> RouteMethod.valueOf(value.declaration.simpleName.asString())
            is KSClassDeclaration -> RouteMethod.valueOf(value.simpleName.asString())
            else -> RouteMethod.GET
        }
        val path = argument("path") as? String ?: ""
        val name = (argument("name") as? String)?.ifBlank { null } ?: function.simpleName.asString()
        val owner = function.parentDeclaration as? KSClassDeclaration
        val reading = Reading("${owner?.simpleName?.asString() ?: "?"}.${function.simpleName.asString()}", method)
        reading.checkFunction(function, owner, name)
        reading.checkPath(path)
        val arguments = function.parameters.map(reading::argument)
        reading.checkBound(arguments)
        val returnType = function.returnType?.resolve()
        val response = returnType?.takeUnless { it.declaration.qualifiedName?.asString() == "kotlin.Unit" }
            ?.let { reading.schemaType(it, "answer") }
        return ServiceRouteBinding(
            handler = owner?.binaryName() ?: "",
            handlerType = owner?.qualifiedName?.asString() ?: "",
            function = function.simpleName.asString(),
            name = name,
            method = method,
            path = path,
            arguments = arguments,
            parameterSchemas = reading.schemas,
            request = reading.request,
            response = response,
            errors = (argument("errors") as? List<*>).orEmpty().filterIsInstance<String>(),
            description = Descriptions.of(function),
            deprecated = function.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == "kotlin.Deprecated"
            },
            problems = reading.problems,
        )
    }

    /** The reading of one route function [where], of [method]: the problems found, the schemas and the body read. */
    private class Reading(val where: String, val method: RouteMethod) {
        val problems = mutableListOf<String>()
        val schemas = linkedMapOf<String, Map<String, Any?>>()
        var request: Map<String, Any?>? = null
        private var variables: List<String> = emptyList()

        fun checkFunction(function: KSFunctionDeclaration, owner: KSClassDeclaration?, name: String) {
            if (owner == null || owner.classKind != ClassKind.CLASS) {
                problems += "service route $where is not a member of a class: the host provides its handler as a bean"
            }
            if (Modifier.SUSPEND !in function.modifiers) problems += "service route $where is not a suspend function"
            if (!NAME.matches(name)) problems += "service route $where is named $name: a route's name is lowerCamelCase"
        }

        fun checkPath(path: String) {
            if (path.isEmpty() || path.startsWith('/') || path.split('/').any { !SEGMENT.matches(it) }) {
                problems += "service route $where has path '$path': a path below the context, its segments " +
                    "lower-kebab words or {variables}, with no leading slash"
            }
            variables = VARIABLE.findAll(path).map { it.groupValues[1] }.toList()
            variables.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                problems += "service route $where names the path variable {$it} twice"
            }
        }

        fun argument(parameter: KSValueParameter): RouteArgument {
            val parameterName = parameter.name?.asString() ?: ""
            val type = parameter.type.resolve()
            val query = parameter.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == ROUTE_QUERY
            }
            return when {
                type.declaration.qualifiedName?.asString() == ROUTE_CALL -> RouteArgument.Call
                query != null -> {
                    val name = (query.arguments.firstOrNull()?.value as? String)?.ifBlank { null } ?: parameterName
                    scalar(type, "query parameter $name")?.let { schemas["query:$name"] = it }
                    RouteArgument.Query(name, type.makeNotNullable().render(), required = !type.isMarkedNullable)
                }
                parameterName in variables -> {
                    if (type.isMarkedNullable) problems += "service route $where: path variable {$parameterName} is nullable"
                    scalar(type, "path variable {$parameterName}")?.let { schemas["path:$parameterName"] = it }
                    RouteArgument.Path(parameterName, type.makeNotNullable().render())
                }
                else -> body(parameterName, type)
            }
        }

        private fun body(parameterName: String, type: KSType): RouteArgument {
            if (!method.hasBody) {
                problems += "service route $where: parameter $parameterName binds nothing, and a ${method.name} " +
                    "takes no body"
            } else if (request != null) {
                problems += "service route $where takes two bodies; another parameter is a path variable, a " +
                    "@RouteQuery or the RouteCall"
            }
            request = schemaType(type, "body")
            return RouteArgument.Body(type.render())
        }

        fun checkBound(arguments: List<RouteArgument>) {
            (variables - arguments.filterIsInstance<RouteArgument.Path>().map { it.name }.toSet()).forEach {
                problems += "service route $where: path variable {$it} binds no parameter"
            }
            if (arguments.count { it == RouteArgument.Call } > 1) problems += "service route $where takes two RouteCalls"
        }

        /** The schema of a scalar parameter: a string, a number, a boolean or an enum's entries; null, told, else. */
        fun scalar(type: KSType, what: String): Map<String, Any?>? {
            val declaration = type.declaration.classOf()
            SCALARS[declaration?.qualifiedName?.asString()]?.let { return it }
            if (declaration?.classKind == ClassKind.ENUM_CLASS) {
                val entries = declaration.declarations.filterIsInstance<KSClassDeclaration>()
                    .filter { it.classKind == ClassKind.ENUM_ENTRY }.map { it.simpleName.asString() }.toList()
                return mapOf("type" to "string", "enum" to entries)
            }
            problems += "service route $where: $what is a ${type.render()}; it is a String, an Int, a Long, a " +
                "Double, a Boolean or an enum"
            return null
        }

        /**
         * The inline schema of a body or an answer: a reference to its class's schema, which its binary name keys, or
         * an array of one for a `List`, a `Set` (of distinct items) or a `Collection`; null, told, for anything else.
         */
        fun schemaType(type: KSType, what: String): Map<String, Any?>? {
            val name = type.declaration.qualifiedName?.asString()
            TREES[name]?.let { return it }
            val items = type.arguments.singleOrNull()?.type?.resolve()
            if (name in COLLECTIONS && items != null) {
                return schemaClassOf(items)?.let { Manifest.arraySchema(Manifest.refSchema(it), unique = name == SET) }
                    ?: refuse(type, what)
            }
            if (type.arguments.isNotEmpty()) return refuse(type, what)
            return schemaClassOf(type)?.let(Manifest::refSchema) ?: refuse(type, what)
        }

        /** The binary name of [type]'s class when it has a schema of its own. */
        private fun schemaClassOf(type: KSType): String? {
            val declaration = type.declaration.classOf() ?: return null
            val own = declaration.qualifiedName?.asString() !in NOT_A_SCHEMA && declaration.classKind != ClassKind.INTERFACE
            return declaration.binaryName().takeIf { own && type.arguments.isEmpty() }
        }

        private fun refuse(type: KSType, what: String): Map<String, Any?>? {
            problems += "service route $where: its $what is a ${type.render()}; it is a class of its own, or a List, " +
                "a Set or a Collection of one, not a map, a scalar or a stream"
            return null
        }
    }
}

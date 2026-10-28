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

package me.ahoo.wow.http.route

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.kernel.route.MalformedRouteRequestException
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.query.admission.QueryTargetException
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * The service routes a node serves: the [handlers] the modules' model providers bind, the
 * [authorizers] every call passes, and the [commands] a handler sends as its caller.
 */
@InternalWowApi
class ServiceRoutes(
    val handlers: List<ServiceRouteHandler> = emptyList(),
    val authorizers: List<ServiceRouteAuthorizer> = emptyList(),
    val commands: CommandClient? = null,
) {
    /**
     * The handler of each of [manifest]'s service routes, by context and name; an [IllegalStateException] naming every
     * route without a handler and every handler without a route, so a node whose Manifest and bindings disagree does
     * not start.
     */
    fun bind(manifest: Manifest): Map<Pair<String, String>, ServiceRouteHandler> {
        val byName = handlers.associateBy { it.context to it.name }
        val listed = manifest.contexts.flatMap { context -> context.routes.map { context.name to it.name } }
        val unbound = listed.filter { it !in byName }
        val unlisted = byName.keys.filter { it !in listed.toSet() }
        check(unbound.isEmpty() && unlisted.isEmpty() && byName.size == handlers.size) {
            buildList {
                unbound.forEach { add("service route ${it.first}.${it.second} has no handler") }
                unlisted.forEach { add("the handler of ${it.first}.${it.second} is no route of the Manifest") }
                handlers.groupBy { it.context to it.name }.filterValues { it.size > 1 }.keys
                    .forEach { add("service route ${it.first}.${it.second} has two handlers") }
            }.joinToString("; ", postfix = ".")
        }
        return byName
    }

    @InternalWowApi
    companion object {
        @JvmField
        val NONE: ServiceRoutes = ServiceRoutes()
    }
}

/**
 * Answers a call of a service route: the caller authenticated, refused if it carries `Wow-Call` (the public port's
 * rule) or names another scope it may not act in, then asked of the authorizers ([ServiceRouteAuthorizer.allOf]),
 * then the route's handler called with the parameters read from the request. Its answer is written as JSON, `Unit` as
 * `204 No Content`; a [RouteRefusal] is the problem of its code, `422` unless the context's table says otherwise, and
 * a code the route does not list is logged once; a parameter that does not read is `400 wow.route.malformed`, its
 * violation naming the parameter.
 */
internal class ServiceRouteHandling(
    private val routes: ServiceRoutes,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val targets: QueryTargets,
) {
    private val authorizer = ServiceRouteAuthorizer.allOf(routes.authorizers)
    private val undeclared = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    suspend fun handle(
        request: ServerRequest,
        context: ContextManifest,
        route: ServiceRouteManifest,
        handler: ServiceRouteHandler,
    ): ServerResponse {
        val acting = admit(request, context, route)
        val body = if (route.request != null) request.awaitBodyOrNull<ByteArray>() else null
        val call = Call(
            caller = acting,
            tenantId = acting.tenantId,
            scope = acting.scope,
            requestId = headers.requestId(request) ?: UUID.randomUUID().toString(),
            client = routes.commands,
        )
        val answer = answer(context, route) { handler.handle(call, Parameters(request, body)) }
        if (answer == null || answer == Unit) return ServerResponse.noContent().buildAndAwait()
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(HttpJson.write(answer))
    }

    /**
     * The caller of [request] as it acts on [route]: authenticated, in the scope it may name, allowed by the
     * authorizers; a problem refusing it otherwise.
     */
    private suspend fun admit(request: ServerRequest, context: ContextManifest, route: ServiceRouteManifest): Caller {
        val caller = authenticator.authenticate(request)
        refuseCall(request)
        val acting = try {
            targets.acting(caller, headers.target(request, FrameworkCodes.ROUTE_MALFORMED))
        } catch (refused: QueryTargetException) {
            throw problem(FrameworkCodes.AUTH_FORBIDDEN, refused.errorMsg)
        }
        val action = RouteAction(
            context.name,
            route.name,
            route.route.method,
            request.path(),
            acting.tenantId,
            acting.scope,
        )
        val decision = authorizer.authorize(caller, action)
        if (decision is Authorization.Denied) throw problem(decision.code, decision.message)
        return acting
    }

    /** A service's call (`Wow-Call`) is taken on the internal port alone. */
    private fun refuseCall(request: ServerRequest) {
        if (request.headers().firstHeader(WowHeaders.CALL) != null) {
            throw problem(FrameworkCodes.AUTH_FORBIDDEN, "A service's call (Wow-Call) is not taken on the public port.")
        }
    }

    /** What [handle] answers, its refusal and its malformed parameter turned into their problems. */
    private suspend fun answer(
        context: ContextManifest,
        route: ServiceRouteManifest,
        handle: suspend () -> Any?
    ): Any? =
        try {
            handle()
        } catch (malformed: MalformedRouteRequestException) {
            val detail = malformed.message ?: "The request does not read."
            throw ProblemException(
                statuses.problemOf(FrameworkCodes.ROUTE_MALFORMED, detail).copyWith(
                    violations = listOf(Violation(malformed.parameter, FrameworkCodes.ROUTE_MALFORMED, detail)),
                ),
            )
        } catch (refusal: RouteRefusal) {
            if (refusal.code !in route.errors && undeclared.add(route.name to refusal.code)) {
                logger.warn {
                    "Service route ${context.name}.${route.name} refused with ${refusal.code}, which it does not " +
                        "list in its errors: declare it in @ServiceRoute(errors)."
                }
            }
            throw ProblemException(
                statuses.problemOf(
                    refusal.code,
                    refusal.message,
                    context.name
                ).copyWith(extensions = refusal.extensions),
            )
        }

    private fun problem(code: String, detail: String) = ProblemException(statuses.problemOf(code, detail))

    private class Call(
        override val caller: Caller,
        override val tenantId: String,
        override val scope: Scope,
        override val requestId: String,
        private val client: CommandClient?,
    ) : RouteCall {
        override val commands: CommandClient
            get() = checkNotNull(client) { "This node gives its service routes no command client." }.of(caller)
    }

    /** The request's parameters, read as their route's function asks. */
    private class Parameters(private val request: ServerRequest, private val body: ByteArray?) : RouteRequest {
        override fun <T : Any> path(name: String, type: KClass<T>): T =
            read(name, "path variable", request.pathVariable(name), type)

        override fun <T : Any> query(name: String, type: KClass<T>): T? =
            request.queryParam(name).orElse(null)?.let { read(name, "query parameter", it, type) }

        @OptIn(ExperimentalStdlibApi::class)
        override fun <T> body(type: KType): T {
            val bytes = body?.takeIf { it.isNotEmpty() }
                ?: throw MalformedRouteRequestException(BODY, "The route takes a body, and the request has none.")
            return try {
                HttpJson.mapper.readValue(bytes, HttpJson.mapper.typeFactory.constructType(type.javaType))
            } catch (failure: tools.jackson.core.JacksonException) {
                throw MalformedRouteRequestException(BODY, "The body does not read as the route's request.", failure)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> read(name: String, what: String, value: String, type: KClass<T>): T {
            val read: Any? = when (type) {
                String::class -> value
                Int::class -> value.toIntOrNull()
                Long::class -> value.toLongOrNull()
                Double::class -> value.toDoubleOrNull()
                Boolean::class -> value.toBooleanStrictOrNull()
                else -> type.java.enumConstants?.firstOrNull { (it as Enum<*>).name == value }
            }
            return read as T? ?: throw MalformedRouteRequestException(
                name,
                "The $what $name is '$value', which does not read as a ${type.simpleName}.",
            )
        }
    }

    private companion object {
        const val BODY = "body"
        val logger = KotlinLogging.logger {}
    }
}

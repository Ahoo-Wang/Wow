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

package me.ahoo.wow.api.annotation

/**
 * A service route: an HTTP route of the module's bounded context whose handler is this function, not
 * a command or a query. The model processor lists it in the context's Manifest (`routes`), with its parameters and
 * the schemas of its request and response, and generates its binding; the edge mounts it from the Manifest and admits
 * its caller as it admits a query's, so it is a route of the service's contract like any other.
 *
 * The function is a `suspend` member of a class the host provides (a bean). Its parameters bind by kind:
 * - a parameter named as a variable of [path] (`{id}`) binds that path segment: a `String`, a number or an enum;
 * - a [RouteQuery] parameter binds the query parameter of its name: nullable when it is optional;
 * - a `me.ahoo.wow.api.route.RouteCall` parameter is the call: the admitted caller, its scope and the commands it
 *   may send;
 * - the one other parameter, on a `POST`, `PUT` or `PATCH`, is the request body.
 *
 * The return type is the response, written as JSON; `Unit` answers `204 No Content`. A body and a response are a
 * class of their own, or a `List`, a `Set` or a `Collection` of one; a map, a scalar, a bare `JsonNode` or a stream
 * (a `Flow`, a `Publisher`) fails the compilation. A Jackson `ObjectNode`, a JSON object of any shape, is for a route
 * that relays a body it does not own, such as another service's command: a route of your own takes a class, so its
 * contract is typed. A handler refuses a call by throwing a
 * `me.ahoo.wow.api.route.RouteRefusal` with one of its [errors].
 *
 * @param method the HTTP method.
 * @param path the path below the context, `system-views/{id}`: its variables in braces, no reserved segment.
 * @param name the route's name in the Manifest, its identity for compatibility and its generated client's method;
 *   the function's name when empty.
 * @param errors the codes the route refuses with, each an `@ErrorCode` of the module; a constant code the handler
 *   throws that is not listed fails the compilation.
 */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class ServiceRoute(
    val method: RouteMethod,
    val path: String,
    val name: String = "",
    val errors: Array<String> = [],
)

/** The HTTP method of a [ServiceRoute]. */
enum class RouteMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    ;

    /** Whether a route of this method takes a request body. */
    val hasBody: Boolean
        get() = this == POST || this == PUT || this == PATCH
}

/**
 * A parameter of a [ServiceRoute] bound to the query parameter of its name, or [name]: optional when the parameter is
 * nullable, required otherwise.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@MustBeDocumented
annotation class RouteQuery(val name: String = "")

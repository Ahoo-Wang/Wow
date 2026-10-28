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

package me.ahoo.wow.kernel.route

import me.ahoo.wow.api.route.RouteCall
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The handler of one service route (decision D359): the binding the compiler generates for a `@ServiceRoute`
 * function, named as the Manifest names the route ([context], [name]). The edge mounts it at the route the Manifest
 * lists, admits the call, and asks it to [handle] the request.
 */
interface ServiceRouteHandler {
    /** The bounded context of the route. */
    val context: String

    /** The route's name within [context]. */
    val name: String

    /**
     * Calls the route's function with the parameters [request] reads, as [call]; its answer, `Unit` for none. A
     * parameter that does not read as its type fails with [MalformedRouteRequestException].
     */
    suspend fun handle(call: RouteCall, request: RouteRequest): Any?
}

/** The parameters of a service route's request, read as the types its function takes; the edge implements it. */
interface RouteRequest {
    /** The path variable [name] as [type]: a string, a number, a boolean or an enum. */
    fun <T : Any> path(name: String, type: KClass<T>): T

    /** The query parameter [name] as [type], or null when the request leaves it out. */
    fun <T : Any> query(name: String, type: KClass<T>): T?

    /** The request body as [type], the function's body parameter. */
    fun <T> body(type: KType): T
}

/** A request whose [parameter] (a path variable, a query parameter or `body`) does not read as its function asks. */
class MalformedRouteRequestException(val parameter: String, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

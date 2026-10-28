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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller

/**
 * What a caller asks of a service route (decision D359), as authorization sees it before the request is decoded:
 * the route [name] of [context], its [method] and the [path] it was called at, in [tenantId] and [scope].
 */
class RouteAction @InternalWowApi constructor(
    val context: String,
    val name: String,
    val method: String,
    val path: String,
    val tenantId: String,
    val scope: Scope,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RouteAction && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RouteAction(context=$context, name=$name, method=$method, path=$path, tenantId=$tenantId, scope=$scope)"

    private fun valueFields(): List<Any?> = listOf(context, name, method, path, tenantId, scope)

    companion object {
        /** A RouteAction of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(context: String, name: String, method: String, path: String, tenantId: String, scope: Scope): RouteAction =
            RouteAction(context, name, method, path, tenantId, scope)
    }
}

/**
 * Decides whether a caller may call a service route, after authentication and before its request is decoded. The
 * rule is the [CommandAuthorizer]'s: a node's authorizers are all asked ([allOf]), the first
 * [Authorization.Denied] is the answer, and there is no abstaining. With none registered, every authenticated caller
 * may call the context's routes.
 */
fun interface ServiceRouteAuthorizer {
    suspend fun authorize(caller: Caller, action: RouteAction): Authorization

    companion object {
        /** Allows every call. */
        @JvmField
        val ALLOW_ALL: ServiceRouteAuthorizer = ServiceRouteAuthorizer { _, _ -> Authorization.Allowed }

        /**
         * [authorizers] as one: asked in their order, the first [Authorization.Denied] is the answer and the rest are
         * not asked. [ALLOW_ALL] for none, the authorizer itself for one.
         */
        @JvmStatic
        fun allOf(authorizers: List<ServiceRouteAuthorizer>): ServiceRouteAuthorizer = when (authorizers.size) {
            0 -> ALLOW_ALL
            1 -> authorizers.single()
            else -> AllOf(authorizers)
        }
    }

    private class AllOf(private val authorizers: List<ServiceRouteAuthorizer>) : ServiceRouteAuthorizer {
        override suspend fun authorize(caller: Caller, action: RouteAction): Authorization {
            authorizers.forEach { authorizer ->
                val decision = authorizer.authorize(caller, action)
                if (decision is Authorization.Denied) return decision
            }
            return Authorization.Allowed
        }

        override fun toString(): String = "ServiceRouteAuthorizer.allOf($authorizers)"
    }
}

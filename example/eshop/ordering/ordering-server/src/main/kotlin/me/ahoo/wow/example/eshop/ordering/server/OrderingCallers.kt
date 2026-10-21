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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Who calls the ordering service: the two caller levels of decision D189, read from two request headers. It stands in
 * for CoSec (`wow-cosec`), whose resolver gives the same callers from a principal and its roles
 * (`wow.cosec.tenant-level.roles`); the example keeps to headers so it runs without an identity provider.
 *
 * - `X-User: <id>`: a buyer, an owner-level caller whose owner is the user: it reads and writes only its own
 *   aggregates.
 * - `X-User: <id>` with `X-Role: employee`: an employee, a tenant-level caller without an owner: it reads and writes
 *   every aggregate of the tenant, each action left to action-level authorization.
 * - Neither: the anonymous caller of the default tenant, as without a resolver, for trying the service out.
 *
 * An order belongs to the buyer whose basket checked it out: the buyer reads its own orders, an employee every order.
 */
@Configuration(proxyBeanMethods = false)
class OrderingCallers {
    // region resolver
    @Bean
    fun orderingCallerResolver(): CallerResolver = CallerResolver { request ->
        val headers = request.headers()
        val user = headers.firstHeader(USER)?.trim()?.takeIf { it.isNotEmpty() } ?: return@CallerResolver Caller.ANONYMOUS
        val employee = headers.firstHeader(ROLE)?.trim() == EMPLOYEE
        Caller.of(
            tenantId = Caller.DEFAULT_TENANT,
            scope = if (employee) Scope.NONE else Scope(ownerId = user),
            subject = user,
            attributes = if (employee) mapOf(ROLES to listOf(EMPLOYEE)) else emptyMap(),
        )
    }
    // endregion


    companion object {
        /** The header naming the user. */
        const val USER: String = "X-User"

        /** The header naming the user's role: [EMPLOYEE], or none for a buyer. */
        const val ROLE: String = "X-Role"

        /** The role of an employee, a tenant-level caller. */
        const val EMPLOYEE: String = "employee"

        /** The caller attribute listing its roles, as CoSec's resolver names it. */
        const val ROLES: String = "roles"
    }
}

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

package me.ahoo.wow.example.eshop.basket.server

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Who calls the basket service, in development: the two caller levels of decision D189, read from two request headers,
 * so the service runs without an identity provider on a laptop and in its tests. Behind the CoSec gateway (the `cosec`
 * profile, decisions D295, D296), this resolver steps aside: wow-cosec's resolver gives the same callers from the
 * principal the gateway verified and its roles (`wow.cosec.tenant-level.roles: employee`), and a deployment's request
 * cannot choose its caller. The headers are refused outside development ([DevelopmentMode], review S-9), so a node
 * that would take them from any client does not start.
 *
 * - `X-User: <id>`: a buyer, an owner-level caller whose owner is the user: it reads and writes only its own
 *   aggregates.
 * - `X-User: <id>` with `X-Role: employee`: an employee, a tenant-level caller without an owner: it reads and writes
 *   every aggregate of the tenant, each action left to action-level authorization.
 * - Neither: the anonymous caller of the default tenant, for trying the service out.
 *
 * A basket belongs to its buyer, and so does the order its checkout creates: the checkout saga's command carries the
 * basket's scope (decision D189).
 */
@Configuration(proxyBeanMethods = false)
class BasketCallers {
    @Bean
    @ConditionalOnProperty(COSEC_ENABLED, havingValue = "false", matchIfMissing = true)
    fun basketCallerResolver(development: DevelopmentMode): CallerResolver {
        development.require(COSEC_ENABLED, false, "takes its callers from headers any client sets")
        return CallerResolver { request ->
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
    }

    companion object {
        /** CoSec's switch: off in development, where the headers name the caller; on behind the gateway. */
        const val COSEC_ENABLED: String = "cosec.enabled"

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

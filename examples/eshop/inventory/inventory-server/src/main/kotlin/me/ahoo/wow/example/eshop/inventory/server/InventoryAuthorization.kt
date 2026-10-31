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

package me.ahoo.wow.example.eshop.inventory.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Who changes the stock. A stock belongs to the tenant: every caller reads it, and the [inventoryAuthorizer] keeps
 * the buyers, owner-level callers, from changing it, as the catalog keeps them from changing a product. An employee,
 * a tenant-level caller, receives stock: in development, a `Wow-User` without a `Wow-Owner`; behind the CoSec gateway,
 * a principal of the role `employee`. The order process's reservations reach the stock on the context's command
 * topic, outside admission, and a service's call on the internal port is a service identity's: the authorizer
 * refuses neither.
 */
@Configuration(proxyBeanMethods = false)
class InventoryAuthorization {
    /**
     * Only an employee, a service, or the anonymous caller of a service tried out, changes the stock: a buyer reads
     * it. A scheduled job's commands and the snapshot rebuild, Wow's built-in aggregates' system commands, are an
     * employee's alone.
     */
    @Bean
    fun inventoryAuthorizer(): CommandAuthorizer = CommandAuthorizer { caller, action ->
        builtIn(caller, action) ?: if (caller.service || caller.scope.ownerId == null) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of(
                "Only an employee changes the stock; ${caller.subject} may not ${action.type.name}."
            )
        }
    }

    private companion object {
        val BUILT_IN = listOf("wow.schedule.", "wow.snapshot.")

        /** The answer to a built-in aggregate's system command: an employee's, a tenant-level caller with a subject. */
        fun builtIn(caller: Caller, action: CommandAction): Authorization? {
            if (BUILT_IN.none { action.type.name.startsWith(it) }) return null
            return if (caller.subject != null && caller.scope.ownerId == null) {
                Authorization.Allowed
            } else {
                Authorization.Denied.of("Only an employee sends ${action.type.name}.")
            }
        }
    }
}

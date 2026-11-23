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

import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.commandIs
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Who ships an order. An order is its buyer's: the buyer reads it, and the kernel keeps every other
 * buyer from it. The [orderingAuthorizer] keeps the buyer from shipping it, which only an employee does, a
 * tenant-level caller: in development, a `Wow-User` without a `Wow-Owner`; behind the CoSec gateway, a principal of
 * the role `employee`. The order process's commands reach the order on the context's command topic, outside
 * admission, and a service's call on the internal port is a service identity's: the authorizer refuses neither.
 */
@Configuration(proxyBeanMethods = false)
class OrderingAuthorization {
    /**
     * Wow's operational commands (a scheduled job's, the snapshot rebuild, an operator's action on a parked entry) are
     * an employee's alone, a tenant-level caller with a subject: this authorizer decides them, so the public port and
     * the operator's routes serve them ([CommandAuthorizer.operational]).
     */
    @Bean
    fun orderingOperators(): CommandAuthorizer = CommandAuthorizer.operational { caller, action ->
        if (caller.subject != null && caller.scope.ownerId == null) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of("Only an employee sends ${action.type.name}.")
        }
    }

    /**
     * A buyer may not ship its order. Every other command, a buyer's delete of its own order among them, is the
     * kernel's to keep in the caller's scope; Wow's operational commands are [orderingOperators]'.
     */
    @Bean
    fun orderingAuthorizer(): CommandAuthorizer = CommandAuthorizer { caller, action ->
        when {
            action.commandIs<ShipOrder>() && !caller.service && caller.scope.ownerId != null ->
                Authorization.Denied.of(
                    "Only an employee ships an order; ${caller.subject} may not ship ${action.ref.id}."
                )

            else -> Authorization.Allowed
        }
    }
}

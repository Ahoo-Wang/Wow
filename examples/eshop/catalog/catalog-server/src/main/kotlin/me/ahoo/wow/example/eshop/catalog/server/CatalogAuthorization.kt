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

package me.ahoo.wow.example.eshop.catalog.server

import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Who changes the catalog. Its aggregates belong to the tenant: every caller reads every product, and the
 * [catalogAuthorizer] keeps the buyers, owner-level callers, from changing them. An employee, a tenant-level caller,
 * changes it: in development, a `Wow-User` without a `Wow-Owner` (the host's development callers); behind the CoSec
 * gateway (the `cosec` profile), a principal of the role `wow.cosec.tenant-level.roles: employee`.
 */
@Configuration(proxyBeanMethods = false)
class CatalogAuthorization {
    /**
     * Wow's operational commands (a scheduled job's, the snapshot rebuild, an operator's action on a parked entry) are
     * an employee's alone, a tenant-level caller with a subject: this authorizer decides them, so the public port and
     * the operator's routes serve them ([CommandAuthorizer.operational]).
     */
    @Bean
    fun catalogOperators(): CommandAuthorizer = CommandAuthorizer.operational { caller, action ->
        if (caller.subject != null && caller.scope.ownerId == null) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of("Only an employee sends ${action.type.name}.")
        }
    }

    /**
     * Only an employee, or the anonymous caller of a service tried out, changes the catalog: a buyer reads it. Wow's
     * operational commands (the snapshot rebuild, an operator's action on a parked entry) are [catalogOperators]'.
     */
    @Bean
    fun catalogAuthorizer(): CommandAuthorizer = CommandAuthorizer { caller, action ->
        when {
            caller.scope.ownerId == null -> Authorization.Allowed
            else -> Authorization.Denied.of(
                "Only an employee changes the catalog; ${caller.subject} may not ${action.type.name}."
            )
        }
    }
}

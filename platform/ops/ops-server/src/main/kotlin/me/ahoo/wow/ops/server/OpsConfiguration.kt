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

package me.ahoo.wow.ops.server

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The ops service's beans: who calls it, and what each caller may send.
 *
 * Who calls it: with `wow.platform.trust-identity-headers=true` ([TRUST_IDENTITY_HEADERS]), the callers the request
 * headers name ([OpsCallers]), for local runs, demos and tests only, with a warning at startup: any client names any
 * operator and tenant there. Otherwise CoSec's: the edge's resolver reads the operator from the security context
 * CoSec put on the request, which the `cosec` profile has CoSec's inject mode parse from the token the gateway verified
 * and forwarded (decision D296: only the gateway reaches the service); without CoSec (`cosec.enabled=false`, the
 * default profile) no request has one, and each is answered 401, never served on a client's word. The header switch
 * is refused outside development ([DevelopmentMode], review S-9).
 *
 * What each caller may send is [OpsAuthorizer]'s to say; whether a caller is an operator, CoSec's policies when CoSec
 * authorizes the service's requests itself and `wow.cosec.authorizer.enabled` is on ([CoSecCommandAuthorizer]), else
 * the operator's role among its CoSec roles ([OperatorRole]).
 */
@Configuration(proxyBeanMethods = false)
class OpsConfiguration {
    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "true")
    fun opsCallers(development: DevelopmentMode): CallerResolver {
        development.require(TRUST_IDENTITY_HEADERS, true, "takes the callers from headers any client sets")
        logger.warn {
            "$TRUST_IDENTITY_HEADERS=true: the ops service takes its callers from the X-User, X-Tenant and X-Role " +
                "headers, which any client sets: for local runs, demos and tests only (${development.reason})."
        }
        return OpsCallers()
    }

    @Bean
    fun opsAuthorizer(authorization: ObjectProvider<Authorization>, environment: Environment): CommandAuthorizer {
        val policies = authorization.ifUnique
            ?.takeIf { environment.getProperty(COSEC_AUTHORIZER_ENABLED, Boolean::class.java, false) }
            ?: return OpsAuthorizer()
        val defaultTenant = environment.getProperty(DEFAULT_TENANT).orEmpty().ifBlank { Caller.DEFAULT_TENANT }
        return OpsAuthorizer(CoSecCommandAuthorizer(policies, defaultTenant))
    }

    companion object {
        /** The switch that takes the callers from the request headers ([OpsCallers]): off by default. */
        const val TRUST_IDENTITY_HEADERS: String = "wow.platform.trust-identity-headers"

        /** Whether CoSec's policies decide who is an operator, where CoSec authorizes the requests itself. */
        const val COSEC_AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"

        /** Wow's default tenant, which CoSec's policies see as CoSec's default one. */
        const val DEFAULT_TENANT: String = "wow.tenant.default"

        private val logger = KotlinLogging.logger {}
    }
}

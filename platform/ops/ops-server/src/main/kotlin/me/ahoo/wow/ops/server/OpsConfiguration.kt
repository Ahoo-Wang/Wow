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
import me.ahoo.wow.cosec.CoSecAuthorizers
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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

    /**
     * What each caller may send: CoSec's policies decide who is an operator when CoSec authorizes the requests itself,
     * as the host hands its decision out ([CoSecAuthorizers]); else the operator's role ([OperatorRole]).
     */
    @Bean
    fun opsAuthorizer(cosec: ObjectProvider<CoSecAuthorizers>): CommandAuthorizer =
        cosec.ifAvailable?.let { OpsAuthorizer(it.authorizer()) } ?: OpsAuthorizer()

    companion object {
        /**
         * The switch that takes the callers from the request headers ([OpsCallers]), off by default: a condition only,
         * the host's `wow.platform.trust-identity-headers` holding its metadata and its default.
         */
        const val TRUST_IDENTITY_HEADERS: String = "wow.platform.trust-identity-headers"

        private val logger = KotlinLogging.logger {}
    }
}

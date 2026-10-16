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
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The ops service's beans: who calls it, and what each caller may send.
 *
 * Who calls it: with `wow.platform.trust-identity-headers=true` ([TRUST_IDENTITY_HEADERS]), the callers the request
 * headers name ([OpsCallers]), for local runs, demos and tests only, with a warning at startup: any client names any
 * operator and tenant there. Otherwise CoSec's resolver when `wow-cosec` is on the class path, and without it no
 * caller at all ([REFUSED]): every request is answered 401, never served on a client's word.
 */
@Configuration(proxyBeanMethods = false)
class OpsConfiguration {
    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "true")
    fun opsCallers(): CallerResolver {
        logger.warn {
            "$TRUST_IDENTITY_HEADERS=true: the ops service takes its callers from the X-User, X-Tenant and X-Role " +
                "headers, which any client sets. For local runs, demos and tests only, never a deployment."
        }
        return OpsCallers()
    }

    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingClass(COSEC_CALLER_RESOLVER)
    fun opsRefusedCallers(): CallerResolver {
        logger.warn {
            "The ops service has no identity provider: every request is answered 401. Put wow-cosec on the class " +
                "path, or set $TRUST_IDENTITY_HEADERS=true for a local run."
        }
        return REFUSED
    }

    @Bean
    fun opsAuthorizer(): CommandAuthorizer = OpsAuthorizer()

    companion object {
        /** The switch that takes the callers from the request headers ([OpsCallers]): off by default. */
        const val TRUST_IDENTITY_HEADERS: String = "wow.platform.trust-identity-headers"

        /** CoSec's caller resolver, which serves the service when `wow-cosec` is on the class path. */
        const val COSEC_CALLER_RESOLVER: String = "me.ahoo.wow.cosec.CoSecCallerResolver"

        /** No caller for any request: each is answered 401. */
        @JvmField
        val REFUSED: CallerResolver = CallerResolver { null }

        private val logger = KotlinLogging.logger {}
    }
}

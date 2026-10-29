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

import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.SignedInCallerResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The ops service's beans: who calls it, and what each caller may send.
 *
 * Who calls it: CoSec's callers, the operator the edge's resolver reads from the security context CoSec put on the
 * request, which the `cosec` profile has CoSec's gateway mode read from the identity the CoSec gateway verified and
 * forwarded (`CoSec-Identity`; only the gateway reaches the service). In development without CoSec, the host's, from
 * the `Wow-*` identity headers any client sets (`DevelopmentCallerResolver`), of which a request without a user is no
 * caller here ([requireSignedInCaller]): it is answered 401, as behind the gateway. A node outside development without
 * CoSec does not start.
 *
 * What each caller may send: no caller may forge a record ([OpsAuthorizer]); whether a caller is an operator, CoSec's
 * policies of each action when CoSec decides the requests itself (its `AuthorizationChecker`, which a service behind
 * the gateway has none of) and `wow.cosec.authorizer.enabled` is on ([CoSecCommandAuthorizer]), else the operator's
 * role among its CoSec roles ([OperatorRole]). An operator's
 * action is authorized here, as `ops.parked.Resume` and the others: the owning service runs the ledger command
 * the saga sends it through its command topic, and guards its own operator routes (`/wow/ops/parked`) itself.
 */
@Configuration(proxyBeanMethods = false)
class OpsConfiguration {
    /** The records no caller may forge ([OpsAuthorizer]), refused whoever the operators are. */
    @Bean
    fun opsAuthorizer(): CommandAuthorizer = OpsAuthorizer()

    /**
     * Who the operators are: steps aside when CoSec decides ([CoSecCommandAuthorizer], its policies alone, this one
     * then having no objection); otherwise the operator role decides ([OperatorRole]).
     */
    @Bean
    fun opsOperatorRole(cosec: ObjectProvider<CoSecCommandAuthorizer>): CommandAuthorizer =
        if (cosec.ifAvailable == null) OperatorRole() else CommandAuthorizer.ALLOW_ALL

    companion object {
        /**
         * Requires a signed-in caller: in development, a request without a user is no caller here
         * ([DevelopmentCallerResolver] gives the anonymous one), so the edge answers it 401 on every route, as CoSec's
         * resolver has it answered, and the console asks who calls. The health probes and the console's assets are
         * not the edge's routes, and answer without a caller.
         */
        @Bean
        @JvmStatic
        fun requireSignedInCaller(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DevelopmentCallerResolver) SignedInCallerResolver(bean) else bean
        }
    }
}

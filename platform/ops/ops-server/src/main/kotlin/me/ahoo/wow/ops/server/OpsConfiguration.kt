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

import me.ahoo.wow.cosec.CoSecAuthorizers
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The ops service's beans: who calls it, and what each caller may send.
 *
 * Who calls it: CoSec's callers, the operator the edge's resolver reads from the security context CoSec put on the
 * request, which the `cosec` profile has CoSec's inject mode parse from the token the gateway verified and forwarded
 * (only the gateway reaches the service). In development without CoSec, the host's, from the `Wow-*` identity headers
 * any client sets (`DevelopmentCallerResolver`), of which a request without a user is no caller here
 * ([signedInCallers]): it is answered 401, as behind the gateway. A node outside development without CoSec does not
 * start.
 *
 * What each caller may send is [OpsAuthorizer]'s to say; whether a caller is an operator, CoSec's policies when CoSec
 * authorizes the service's requests itself and `wow.cosec.authorizer.enabled` is on ([CoSecCommandAuthorizer]), else
 * the operator's role among its CoSec roles ([OperatorRole]).
 */
@Configuration(proxyBeanMethods = false)
class OpsConfiguration {
    /**
     * What each caller may send: CoSec's policies decide who is an operator when CoSec authorizes the requests itself,
     * as the host hands its decision out ([CoSecAuthorizers]); else the operator's role ([OperatorRole]).
     */
    @Bean
    fun opsAuthorizer(cosec: ObjectProvider<CoSecAuthorizers>): CommandAuthorizer =
        cosec.ifAvailable?.let { OpsAuthorizer(it.authorizer()) } ?: OpsAuthorizer()

    companion object {
        /**
         * In development, a request without a user is no caller ([DevelopmentCallerResolver] gives the anonymous one):
         * the edge answers it 401, as CoSec's resolver has it answered, and the console asks who calls.
         */
        @Bean
        @JvmStatic
        fun signedInCallers(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DevelopmentCallerResolver) {
                    CallerResolver { request -> bean.resolve(request)?.takeIf { it.subject != null } }
                } else {
                    bean
                }
        }
    }
}

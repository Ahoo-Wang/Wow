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

package me.ahoo.wow.spring.boot.security

import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.security.CoSecAutoConfiguration.Companion.AUTHORIZER_ENABLED
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * `wow.cosec.authorizer.enabled` does not fail silently, as #6849 did: set true where no CoSec
 * authorizer is wired, the node refuses to start and says why. That is where CoSec does not decide the requests in this
 * service and has no `AuthorizationChecker`: behind a CoSec gateway (`wow.cosec.mode=gateway`), with CoSec off, or
 * without wow-cosec and CoSec's WebFlux module on the class path. The switch would otherwise do nothing, and every
 * command that passed its route would be allowed.
 */
internal object CoSecAuthorizerSwitch {
    /** The class of CoSec's command authorizer, by name: wow-cosec is the application's to add. */
    private const val COSEC_COMMAND_AUTHORIZER = "me.ahoo.wow.cosec.CoSecCommandAuthorizer"

    private const val FIX = "Remove $AUTHORIZER_ENABLED where CoSec does not decide the requests in this service: " +
        "behind a CoSec gateway the gateway authorizes them, and a CommandAuthorizer bean of your own decides " +
        "what it does not. For CoSec to authorize each command, run CoSec's pipeline in this service " +
        "($COSEC_PIPELINE_MODE=enforce, its default) with wow-cosec and CoSec's WebFlux module."

    fun check(properties: WowProperties, environment: Environment, beans: ListableBeanFactory) {
        if (!properties.cosec.authorizer.enabled) {
            return
        }
        val wired = beans.getBeanNamesForType(CommandAuthorizer::class.java, true, false)
            .any { beans.getType(it)?.name == COSEC_COMMAND_AUTHORIZER }
        configCheck(wired, AUTHORIZER_ENABLED, true, FIX) {
            "$AUTHORIZER_ENABLED is true, but no CoSec authorizer is wired: ${why(environment)}. The switch would " +
                "do nothing, and every command that passed its route would be allowed."
        }
    }

    /** Why no CoSec authorizer is wired. */
    private fun why(environment: Environment): String {
        val loader = CoSecAuthorizerSwitch::class.java.classLoader
        return when {
            !ClassUtils.isPresent(COSEC_MODULE, loader) || !ClassUtils.isPresent(COSEC_WEBFLUX, loader) ->
                "wow-cosec and CoSec's WebFlux module are not both on the class path"

            environment.getProperty(COSEC_ENABLED).equals("false", ignoreCase = true) ->
                "CoSec is off ($COSEC_ENABLED=false)"

            environment.getProperty(COSEC_PIPELINE_MODE).equals("gateway", ignoreCase = true) ->
                "the service runs behind a CoSec gateway ($COSEC_PIPELINE_MODE=gateway), which decides the " +
                    "requests, and has no AuthorizationChecker of CoSec's"

            else ->
                "the service has no AuthorizationChecker of CoSec's, which its pipeline defines where it decides " +
                    "the requests itself"
        }
    }
}

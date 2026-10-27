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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.spi.config.configCheck
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * CoSec's settings for a service as `wow.cosec.mode` names its mode, as defaults: they come after every other source,
 * so a `cosec.*` key the application sets still wins.
 *
 * - `gateway` ([GATEWAY]): behind a CoSec gateway, in CoSec's inject mode. The gateway verifies each request's token
 *   and authorizes it coarsely; the service reads the principal from the token it forwards and holds no secret, so
 *   CoSec verifies, authorizes, authenticates, audits, locates and limits nothing itself. What a caller may do is
 *   the service's to say.
 * - `off` ([OFF]): CoSec off, for a service run without a gateway.
 */
internal class CoSecModeEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val mode = Binder.get(environment).bind(MODE, String::class.java).orElse(null)?.trim()?.lowercase()
            ?: return
        val settings = MODES[mode]
        configCheck(settings != null, MODE, mode, "Set it to one of ${MODES.keys}, or leave it unset.") {
            "$MODE is $mode, which is no CoSec mode."
        }
        environment.propertySources.addLast(MapPropertySource(SOURCE, settings))
    }

    companion object {
        const val MODE: String = "wow.cosec.mode"
        const val SOURCE: String = "wowCoSecMode"

        /** Behind a CoSec gateway: CoSec on in its inject mode, and nothing else of it on. */
        val GATEWAY: Map<String, Any> = linkedMapOf(
            "cosec.enabled" to "true",
            // The gateway holds CoSec's signing secret; this service neither signs nor verifies a token.
            "cosec.jwt.enabled" to "false",
            // The principal from the token the gateway verified and forwarded.
            "cosec.inject.enabled" to "true",
            // The gateway authorizes the paths, and the service decides what a caller may do.
            "cosec.authorization.enabled" to "false",
            "cosec.authentication.enabled" to "false",
            "cosec.audit.enabled" to "false",
            "cosec.ip2region.enabled" to "false",
            "cosec.limiter.enabled" to "false",
        )

        /** CoSec off. */
        val OFF: Map<String, Any> = mapOf("cosec.enabled" to "false")

        private val MODES = mapOf("gateway" to GATEWAY, "off" to OFF)
    }
}

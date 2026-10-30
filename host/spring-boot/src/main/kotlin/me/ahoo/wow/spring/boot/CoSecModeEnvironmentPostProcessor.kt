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

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * CoSec's settings for a service as `wow.cosec.mode` names its mode, as defaults: they come after every other source,
 * so a `cosec.*` key the application sets still wins.
 *
 * - `gateway` ([GATEWAY]): behind a CoSec gateway (`cosec.pipeline.mode=gateway`). The gateway authenticates and
 *   authorizes each request and forwards the identity it verified in `CoSec-Identity`: the principal, its tenant, and
 *   the app, space and roles of its scope. The service reads it and holds no secret, documents or Redis of CoSec's:
 *   it verifies no token (`cosec.jwt.enabled=false`), and what a caller may do is the service's to say. Only the
 *   gateway may reach it.
 * - `disabled` ([DISABLED]): CoSec off, for a service run without a gateway.
 */
internal class CoSecModeEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        // The key's one reader is its properties bean, bound here before the beans are.
        val settings = when (WowProperties.of(environment).cosec.mode ?: return) {
            WowProperties.CoSec.CoSecMode.GATEWAY -> GATEWAY
            WowProperties.CoSec.CoSecMode.DISABLED -> DISABLED
        }
        environment.propertySources.addLast(MapPropertySource(SOURCE, settings))
    }

    companion object {
        const val MODE: String = "wow.cosec.mode"
        const val SOURCE: String = "wowCoSecMode"

        /** Behind a CoSec gateway: CoSec on, reading the identity the gateway forwards. */
        val GATEWAY: Map<String, Any> = linkedMapOf(
            "cosec.enabled" to "true",
            "cosec.pipeline.mode" to "gateway",
            // The gateway holds CoSec's signing secret; this service neither signs nor verifies a token.
            "cosec.jwt.enabled" to "false",
        )

        /** CoSec off. */
        val DISABLED: Map<String, Any> = mapOf("cosec.enabled" to "false")
    }
}

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

import io.mockk.mockk
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `wow.cosec.authorizer.enabled` true where no CoSec authorizer can be wired refuses the start and says why (decision
 * D485 ⑦): the security switch does not fail silently. Through 10.0.0-beta.21 such a node started, and the switch did
 * nothing.
 */
class CoSecAuthorizerSwitchTest {
    /** wow-cosec and CoSec's WebFlux module are on the tests' class path: the host wires CoSec unless excluded. */
    private val withCoSec = "spring.autoconfigure.exclude" to ""
    private val enabled = "wow.cosec.authorizer.enabled" to true

    /** What CoSec's starter defines where it decides the requests itself. */
    @Configuration(proxyBeanMethods = false)
    class CoSecDecides {
        @Bean
        fun checker(): AuthorizationChecker = mockk()
    }

    private fun refusal(vararg properties: Pair<String, Any>): WowConfigurationException {
        val failure = assertThrows<Exception> { startHost(*properties).close() }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
    }

    @Test
    fun `behind a CoSec gateway the switch refuses the start, naming the gateway`() {
        val refused = refusal(withCoSec, enabled, "wow.cosec.mode" to "gateway")
        refused.key.assert().isEqualTo("wow.cosec.authorizer.enabled")
        refused.message.assert().isEqualTo(
            "wow.cosec.authorizer.enabled is true, but no CoSec authorizer is wired: the service runs behind a " +
                "CoSec gateway (cosec.pipeline.mode=gateway), which decides the requests, and has no " +
                "AuthorizationChecker of CoSec's. The switch would do nothing, and every command that passed its " +
                "route would be allowed. Remove wow.cosec.authorizer.enabled where CoSec does not decide the " +
                "requests in this service: behind a CoSec gateway the gateway authorizes them, and a " +
                "CommandAuthorizer bean of your own decides what it does not. For CoSec to authorize each command, " +
                "run CoSec's pipeline in this service (cosec.pipeline.mode=enforce, its default) with wow-cosec and " +
                "CoSec's WebFlux module.",
        )
    }

    @Test
    fun `with CoSec off, or without its checker, the switch refuses the start too`() {
        refusal(withCoSec, enabled, "wow.cosec.mode" to "disabled").reason.assert()
            .contains("no CoSec authorizer is wired: CoSec is off (cosec.enabled=false).")
        refusal(withCoSec, enabled).reason.assert()
            .contains("no CoSec authorizer is wired: the service has no AuthorizationChecker of CoSec's")
        // The host's CoSec configuration left out, as the fixture's hosts run: no checker either.
        refusal(enabled).reason.assert().contains("no CoSec authorizer is wired")
    }

    @Test
    fun `where CoSec decides the requests, the switch wires CoSec's authorizer and the node starts`() {
        startHost(withCoSec, enabled, sources = listOf(HostTestApp::class.java, CoSecDecides::class.java)).use {
            it.context.getBeansOfType(CommandAuthorizer::class.java).values.single()
                .assert().isInstanceOf(CoSecCommandAuthorizer::class.java)
        }
        // Off, nothing is asked of CoSec, and the node starts without it.
        startHost(withCoSec, "wow.cosec.mode" to "gateway").close()
    }
}

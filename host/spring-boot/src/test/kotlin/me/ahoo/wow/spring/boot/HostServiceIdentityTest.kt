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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.OpsPaths
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A service identity on a host (#6662): an application's resolver that returns one for a public request is refused on
 * the public port and the operator's routes, so no client of them is trusted as a service; the service's own code in
 * the process, which is no request, still sends as the service.
 */
class HostServiceIdentityTest {
    /** An application's resolver that returns a service identity for every request: the case refused. */
    @Configuration(proxyBeanMethods = false)
    class ServiceCallers {
        @Bean
        fun serviceCallers(): CallerResolver = CallerResolver { Caller.ofService("rogue-service") }
    }

    @Test
    fun `a resolver's service identity is refused on the public port, and the process still sends as the service`() {
        startHost(
            "wow.backend" to "memory",
            "wow.http.ops.enabled" to true,
            sources = listOf(HostTestApp::class.java, ServiceCallers::class.java),
        ).use { host ->
            listOf(
                host.post("/bank/account/open-account", """{"owner":"amy"}"""),
                host.post("${OpsPaths.PARKED}/${OpsPaths.LIST}"),
            ).forEach { refused ->
                refused.status.assert().withFailMessage { refused.body.toString() }.isEqualTo(403)
                refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
                refused.body!!["detail"].asString().assert().contains("service:rogue-service")
                    .contains("internal port alone")
            }
            val commands = host.context.getBean(CommandClient::class.java).of(Caller.ofService("host-test"))
            runBlocking { commands.wait(CommandRequest.of(OpenAccount("amy"))) }.status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
    }
}

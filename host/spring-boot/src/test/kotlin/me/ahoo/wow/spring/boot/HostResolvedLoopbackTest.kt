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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.node.NodeIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.InetAddress

/**
 * #6667: a node whose host name resolves to a loopback address (Debian's `/etc/hosts` gives it `127.0.1.1`), with no
 * development profile and no `wow.internal.host`, is a deployment: it binds that address, and is held to a
 * deployment's safeguards, refusing to start without a caller resolver or a durable backend.
 */
class HostResolvedLoopbackTest {
    /** The node's identity as the host resolves it on a Debian host. */
    @Configuration(proxyBeanMethods = false)
    class DebianHost {
        @Bean
        fun debianNodeIdentity(properties: WowProperties, environment: Environment): NodeIdentity =
            NodeIdentity.of(properties, environment, InetAddress.getByAddress("debian", byteArrayOf(127, 0, 1, 1)))
    }

    /** No `wow.internal.host`: the test hosts' loopback one is blanked, which is not set. */
    private val resolved = "wow.internal.host" to ""

    private fun refusal(vararg properties: Pair<String, Any>, sources: List<Class<*>>): WowConfigurationException {
        val failure = assertThrows<Exception> { startHost(resolved, *properties, sources = sources).close() }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
    }

    @Test
    fun `without a caller resolver it refuses to start, as any deployment does`() {
        val cause = refusal(
            "wow.backend" to "memory",
            sources = listOf(HostTestApp::class.java, DebianHost::class.java),
        )
        cause.key.assert().isEqualTo("wow.dev-profiles")
    }

    @Test
    fun `without a durable backend it refuses to start, as any deployment does`() {
        val cause = refusal(
            sources = listOf(HostTestApp::class.java, DebianHost::class.java, AnonymousCallers::class.java),
        )
        cause.key.assert().isEqualTo(BackendSelection.BACKEND)
    }
}

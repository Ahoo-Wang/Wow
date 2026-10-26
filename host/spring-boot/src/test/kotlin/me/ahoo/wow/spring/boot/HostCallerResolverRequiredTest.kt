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
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The public port without a `CallerResolver` bean (decision D301): a node outside development refuses to start, one in
 * development (a profile of `wow.dev-profiles`, or a loopback internal port) serves the anonymous caller, and
 * an intentionally anonymous service says so with a bean returning [CallerResolver.ANONYMOUS] ([AnonymousCallers]).
 */
class HostCallerResolverRequiredTest {
    /** A node bound to every interface, in memory on purpose, so that the backend is not what it refuses. */
    private val deployed = arrayOf(
        "wow.internal.host" to "0.0.0.0",
        "wow.backend" to "memory",
    )

    @Test
    fun `a node outside development without one refuses to start`() {
        val failure = assertThrows<Exception> { startHost(*deployed).close() }
        val cause = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>()
            .first()
        cause.key.assert().isEqualTo("wow.dev-profiles")
        cause.message.assert().contains("CallerResolver.ANONYMOUS")
    }

    @Test
    fun `a node in a development profile serves the anonymous caller`() {
        startHost(*deployed, "spring.profiles.active" to "dev").use { host ->
            host.context.getBean(DevelopmentMode::class.java).reason.assert().isEqualTo("profile dev")
            host.context.getBean(CallerResolver::class.java).assert().isSameAs(CallerResolver.ANONYMOUS)
        }
    }

    @Test
    fun `a node on a loopback internal port serves the anonymous caller`() {
        startHost().use { host ->
            host.context.getBean(DevelopmentMode::class.java).active.assert().isTrue()
            host.post("/bank/account/open-account", """{"owner":"gina"}""").status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `an intentionally anonymous service starts outside development`() {
        startHost(*deployed, sources = listOf(HostTestApp::class.java, AnonymousCallers::class.java)).use { host ->
            host.context.getBean(DevelopmentMode::class.java).active.assert().isFalse()
            host.post("/bank/account/open-account", """{"owner":"gina"}""").status.assert().isEqualTo(200)
        }
    }
}

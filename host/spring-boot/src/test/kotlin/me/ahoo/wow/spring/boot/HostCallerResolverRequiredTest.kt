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
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/**
 * The public port without a `CallerResolver` bean (decision D301): a node outside development refuses to start, one in
 * a profile of `wow.dev-profiles` takes its callers from the identity headers ([DevelopmentCallerResolver]), one in
 * development by a loopback internal port alone serves the anonymous caller whatever headers a request sends (the
 * internal port says nothing of who reaches the public one), and an intentionally anonymous service says so with a bean
 * returning
 * [CallerResolver.ANONYMOUS] ([AnonymousCallers]). CoSec's resolver takes the place of the headers only while CoSec
 * is on (`cosec.enabled`).
 */
class HostCallerResolverRequiredTest {
    /** wow-cosec and CoSec's WebFlux module are on the tests' class path: the host wires CoSec unless excluded. */
    private val withCoSec = arrayOf<Pair<String, Any>>("spring.autoconfigure.exclude" to "")

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
    fun `a node in a development profile takes its callers from the identity headers`() {
        startHost(*deployed, "spring.profiles.active" to "dev").use { host ->
            host.context.getBean(DevelopmentMode::class.java).reason.assert().isEqualTo("profile dev")
            host.context.getBean(
                CallerResolver::class.java
            ).assert().isInstanceOf(DevelopmentCallerResolver::class.java)
        }
    }

    @Test
    fun `a node on a loopback internal port alone serves the anonymous caller, whatever headers it sends`() {
        startHost().use { host ->
            host.context.getBean(DevelopmentMode::class.java).active.assert().isTrue()
            val resolver = host.context.getBean(CallerResolver::class.java)
            resolver.assert().isSameAs(CallerResolver.ANONYMOUS)
            val named = MockServerRequest.builder()
                .header(DevelopmentCallerResolver.USER, "mallory")
                .header(DevelopmentCallerResolver.ROLES, "admin")
                .header(DevelopmentCallerResolver.TENANT, "other")
                .build()
            runBlocking { resolver.resolve(named) }.assert().isSameAs(Caller.ANONYMOUS)
            host.post("/bank/account/open-account", """{"owner":"gina"}""").status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a node on a loopback internal port in a development profile serves the caller its headers name`() {
        startHost("spring.profiles.active" to "dev").use { host ->
            host.post(
                "/bank/account/open-account",
                """{"owner":"hank"}""",
                mapOf(DevelopmentCallerResolver.USER to "hank", DevelopmentCallerResolver.OWNER to "hank"),
            ).status.assert().isEqualTo(200)
            val resolver = host.context.getBean(CallerResolver::class.java)
            val named = MockServerRequest.builder()
                .header(DevelopmentCallerResolver.USER, "hank")
                .header(DevelopmentCallerResolver.OWNER, "hank")
                .build()
            runBlocking { resolver.resolve(named) }!!.subject.assert().isEqualTo("hank")
        }
    }

    @Test
    fun `a development profile that is Spring's default profile takes its callers from the identity headers`() {
        startHost(*deployed, "spring.profiles.default" to "dev").use { host ->
            val development = host.context.getBean(DevelopmentMode::class.java)
            development.profiles.assert().containsExactly("dev")
            development.byProfile.assert().isTrue()
            host.context.getBean(
                CallerResolver::class.java
            ).assert().isInstanceOf(DevelopmentCallerResolver::class.java)
        }
    }

    @Test
    fun `a node in development with CoSec off takes its callers from the identity headers`() {
        startHost(*deployed, *withCoSec, "cosec.enabled" to false, "spring.profiles.active" to "dev").use { host ->
            host.context.getBean(
                CallerResolver::class.java
            ).assert().isInstanceOf(DevelopmentCallerResolver::class.java)
        }
    }

    @Test
    fun `a node outside development with CoSec off and no resolver refuses to start`() {
        val failure = assertThrows<Exception> { startHost(*deployed, *withCoSec, "cosec.enabled" to false).close() }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().isEqualTo("wow.dev-profiles")
    }

    @Test
    fun `a node with CoSec on takes CoSec's callers, in development too`() {
        startHost(*deployed, *withCoSec, "spring.profiles.active" to "dev").use { host ->
            host.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
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

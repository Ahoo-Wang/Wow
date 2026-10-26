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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

/** The `wow.*` keys no property names (review A5-9): warned of, and refused under `wow.cosec`. */
class ConfigurationKeysTest {
    private val keys = ConfigurationKeys.of(javaClass.classLoader, listOf("wow.view-store", "wow", "spring.foo"))

    private fun environment(vararg properties: Pair<String, String>) = MockEnvironment().apply {
        properties.forEach { (key, value) -> setProperty(key, value) }
    }

    @Test
    fun `the keys of the metadata, relaxed, mapped, listed or owned by another bean are known`() {
        val environment = environment(
            "wow.executor.max-in-flight" to "8",
            "wow.executor.laneCapacity" to "8",
            "wow.aggregates.[shop.cart].tenant" to "acme",
            "wow.cosec.cross-scope.roles[0]" to "admin",
            "wow.cosec.authorizer.enabled" to "true",
            "wow.cosid.global-generator" to "snowflake",
            "wow.query.memory.enabled" to "true",
            "wow.view-store.system-views[0].id" to "v",
            "spring.application.name" to "shop",
        )
        keys.unknown(environment).assert().isEmpty()
        keys.check(environment)
    }

    @Test
    fun `a mistyped key is warned of, not refused`() {
        val environment = environment("wow.cluster.forward-timout" to "30s", "wow.clustr.router" to "local")
        keys.unknown(environment).assert().containsExactlyInAnyOrder("wow.cluster.forward-timout", "wow.clustr.router")
        keys.check(environment)
    }

    @Test
    fun `a mistyped security key refuses the startup`() {
        val environment = environment("wow.cosec.authorizer.enable" to "true")
        val failure = assertThrows<IllegalStateException> { keys.check(environment) }
        failure.message.assert().contains("wow.cosec.authorizer.enable")
    }

    @Test
    fun `the keys of a calling service and a call target are checked to their properties (R2_5-3)`() {
        val known = environment(
            "wow.http.services.basket.commands[0]" to "shop.order.SubmitOrder",
            "wow.http.services.[ordering.v2].commands" to "Checkout",
            "wow.http.services.ordering.reads" to "shop.order.snapshot",
            "wow.calls.contexts.ordering.url" to "http://ordering:9090",
        )
        keys.unknown(known).assert().isEmpty()
        keys.check(known)
        // The map holds the entry, but its value has no such property: commands stays empty, which opens every
        // domain command to the service (D226), so the typo refuses the startup.
        val mistyped = environment(
            "wow.http.services.basket.command[0]" to "shop.order.SubmitOrder",
            "wow.http.services.basket" to "t",
            "wow.calls.contexts.ordering.uri" to "http://ordering:9090",
            // The tokens D300 removed are unknown keys now: a deployment that still sets one does not start.
            "wow.http.services.ordering.tokens" to "a,b",
            "wow.calls.token" to "t",
            "wow.internal.token" to "t",
            "wow.cosec.service.roles" to "service",
        )
        keys.unknown(mistyped).assert().containsExactlyInAnyOrder(
            "wow.http.services.basket.command[0]",
            "wow.http.services.basket",
            "wow.calls.contexts.ordering.uri",
            "wow.http.services.ordering.tokens",
            "wow.calls.token",
            "wow.internal.token",
            "wow.cosec.service.roles",
        )
        val failure = assertThrows<IllegalStateException> { keys.check(mistyped) }
        failure.message.assert().contains("wow.http.services.basket.command[0]")
    }

    @Test
    fun `a host whose security key is mistyped does not start`() {
        val failure = runCatching { startHost("wow.cosec.authorizer.enable" to true).close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("wow.cosec.authorizer.enable") }
            .assert().isTrue()
    }

    @Test
    fun `a host whose service's commands are mistyped does not start`() {
        val key = "wow.http.services.basket.command[0]"
        val failure = runCatching { startHost(key to "shop.order.SubmitOrder").close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(key) }.assert().isTrue()
    }
}

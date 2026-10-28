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

package me.ahoo.wow.spi.config

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.store.StoreConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProviderOptionTest {
    private val timeout = ProviderOption.duration("append-timeout", "how long an append may take.", 5.seconds)
    private val size = ProviderOption.int("batch.size", "how many records a batch holds.")
    private val enabled = ProviderOption.boolean("enabled", "whether it runs.", true)
    private val client = ProviderOption.string("client", "the client bean.").perStore()

    @Test
    fun `each factory should parse its type and keep its default and scope`() {
        timeout.parse("1m 30s", "k").assert().isEqualTo(90.seconds)
        timeout.parse("PT2S", "k").assert().isEqualTo(2.seconds)
        timeout.parse("500", "k").assert().isEqualTo(500.milliseconds)
        size.parse(" 64 ", "k").assert().isEqualTo(64)
        enabled.parse("FALSE", "k").assert().isFalse()
        timeout.default.assert().isEqualTo(5.seconds)
        size.default.assert().isNull()
        timeout.scope.assert().isEqualTo(ProviderOption.Scope.TYPE)
        client.scope.assert().isEqualTo(ProviderOption.Scope.PER_STORE)
        client.key.assert().isEqualTo("client")
        timeout.toString().assert()
            .isEqualTo("ProviderOption(key=append-timeout, type=Duration, default=5s, scope=TYPE)")
    }

    @Test
    fun `a value that is not one of the option's type should fail naming where it was set`() {
        val failure = assertThrows<WowConfigurationException> { size.parse("many", "wow.stores.a.options.batch.size") }
        failure.key.assert().isEqualTo("wow.stores.a.options.batch.size")
        failure.message.assert().contains("many", "Integer", "how many records a batch holds.")
        assertThrows<WowConfigurationException> { enabled.parse("yes", "k") }
        assertThrows<WowConfigurationException> { timeout.parse("soon", "k") }
    }

    @Test
    fun `a key and a description should be required`() {
        assertThrows<IllegalArgumentException> { ProviderOption.string("Bad_Key", "x") }
        assertThrows<IllegalArgumentException> { ProviderOption.string("good", " ") }
    }

    @Test
    fun `a store should read an option through its declaration, its default when unset`() {
        val config = StoreConfig.of("a", "t", options = mapOf("append-timeout" to "2s", "batch.size" to " "))
        config.option(timeout).assert().isEqualTo(2.seconds)
        config.option(size).assert().isNull()
        config.option(enabled).assert().isTrue()
        config.optionsRead.assert().containsExactlyInAnyOrder("append-timeout", "batch.size", "enabled")
        assertThrows<WowConfigurationException> {
            StoreConfig.of("a", "t", options = mapOf("batch.size" to "x")).option(size)
        }.key.assert().isEqualTo("wow.stores.a.options.batch.size")
    }
}

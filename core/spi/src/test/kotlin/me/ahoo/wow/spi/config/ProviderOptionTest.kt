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
    private val client = ProviderOption.string("client", "the client bean.").perInstance()

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
        client.scope.assert().isEqualTo(ProviderOption.Scope.INSTANCE)
        client.key.assert().isEqualTo("client")
        timeout.toString().assert()
            .isEqualTo("ProviderOption(key=append-timeout, type=Duration, default=5s, scope=TYPE)")
    }

    private enum class Refresh { WAIT_FOR, IMMEDIATE }

    @Test
    fun `an enumeration should take its constants in lower kebab case, list them, and keep them per store`() {
        val refresh = ProviderOption.enumeration(
            "refresh",
            "when writes are searchable.",
            Refresh::class.java,
            Refresh.WAIT_FOR
        )
        refresh.values.assert().containsExactly("wait-for", "immediate")
        refresh.parse(" Wait-For ", "k").assert().isEqualTo(Refresh.WAIT_FOR)
        refresh.default.assert().isEqualTo(Refresh.WAIT_FOR)
        refresh.perInstance().values.assert().containsExactly("wait-for", "immediate")
        val failure = assertThrows<WowConfigurationException> { refresh.parse("wait_for", "wow.stores.a.refresh") }
        failure.key.assert().isEqualTo("wow.stores.a.refresh")
        failure.message.assert().contains("not one of wait-for, immediate")
        val optional = ProviderOption.enumeration("mode", "the mode.", Refresh::class.java)
        optional.default.assert().isNull()
        optional.perInstance().values.assert().containsExactly("wait-for", "immediate")
        ProviderOption.valueOf(Refresh.IMMEDIATE).assert().isEqualTo("immediate")
        size.values.assert().isEmpty()
    }

    @Test
    fun `an option given a default should be read as a value that is always there, per store too`() {
        val region: ProviderOption.Defaulted<String> = ProviderOption.string("region", "the region.", "eu-1")
        val instance: ProviderOption.Defaulted<String> = region.perInstance()
        instance.scope.assert().isEqualTo(ProviderOption.Scope.INSTANCE)
        instance.default.assert().isEqualTo("eu-1")
        val retries: ProviderOption.Defaulted<Int> = ProviderOption.int("retries", "how many retries.", 3)
        val on: ProviderOption.Defaulted<Boolean> = ProviderOption.boolean("on", "whether it is on.", false)
        val set = StoreConfig.of("a", "t", options = mapOf("region" to "us-2", "retries" to " "))
        val region2: String = set.settings.option(region)
        region2.assert().isEqualTo("us-2")
        val defaulted: Int = set.settings.option(retries)
        defaulted.assert().isEqualTo(3)
        set.settings.option(on).assert().isFalse()
        set.settings.option(timeout).assert().isEqualTo(5.seconds)
        // Without a default, the value may be missing.
        set.settings.option(size).assert().isNull()
        size.perInstance().default.assert().isNull()
    }

    @Test
    fun `a value that is not one of the option's type should fail naming where it was set`() {
        val failure = assertThrows<WowConfigurationException> { size.parse("many", "wow.stores.a.batch.size") }
        failure.key.assert().isEqualTo("wow.stores.a.batch.size")
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
    fun `a map option should pass its sub-keys through, and be read by them alone`() {
        val settings = ProviderOption.map("settings", "server settings every query runs under.")
        settings.isMap.assert().isTrue()
        size.isMap.assert().isFalse()
        settings.default.assert().isNull()
        assertThrows<WowConfigurationException> { settings.parse("x", "wow.acme.settings") }
            .message.assert().contains("read by its sub-keys")
        val config = StoreConfig.of(
            "a",
            "t",
            options = mapOf("settings.max_threads" to "2", "settings.mode" to "fast", "settingsx" to "no"),
        )
        config.settings.options(settings).assert().isEqualTo(mapOf("max_threads" to "2", "mode" to "fast"))
        config.settings.read.assert().containsExactly("settings")
    }

    @Test
    fun `a store should read an option through its declaration, its default when unset`() {
        val config = StoreConfig.of("a", "t", options = mapOf("append-timeout" to "2s", "batch.size" to " "))
        config.settings.option(timeout).assert().isEqualTo(2.seconds)
        config.settings.option(size).assert().isNull()
        config.settings.option(enabled).assert().isTrue()
        config.settings.read.assert().containsExactlyInAnyOrder("append-timeout", "batch.size", "enabled")
        assertThrows<WowConfigurationException> {
            StoreConfig.of("a", "t", options = mapOf("batch.size" to "x")).settings.option(size)
        }.key.assert().isEqualTo("wow.stores.a.batch.size")
    }
}

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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import java.util.concurrent.ConcurrentHashMap

/**
 * The settings of one provider's instance, as its configuration holds them under [key]: a store's (`wow.stores.<name>`),
 * a sink's or a transport's (`wow.<name>`), a peer discovery's or a dataset's. [values] are keyed relative to [key]
 * (`endpoint`, say). Every config a provider is opened with holds one, so a provider reads its settings one way
 * whatever it provides, through its [ProviderOption]s, and a failure names the setting by its full key.
 *
 * It remembers which settings were read ([read]), so a provider's test can hold them to the options it declares.
 *
 * Fields may be added: a config builds it, so the constructor is internal to Wow.
 */
@WowSpi
class ProviderSettings @InternalWowApi constructor(
    /** The configuration key the settings are under, for a provider's failures to name. */
    val key: String,
    /** The settings, by their keys relative to [key]. */
    val values: Map<String, String> = emptyMap(),
) {
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The setting [name], or null when it is not set or blank. */
    fun option(name: String): String? {
        asked += name
        return values[name]?.takeIf { it.isNotBlank() }
    }

    /**
     * The value of [option]: its setting, parsed, else its default; fails naming the setting by its full key when the
     * value is not one of the option's type.
     */
    fun <T : Any> option(option: ProviderOption<T>): T? = setting(option) ?: option.default

    /** The value of the [ProviderOption.Defaulted] [option]: its setting, parsed, else its default, so always one. */
    fun <T : Any> option(option: ProviderOption.Defaulted<T>): T = setting(option) ?: option.default

    private fun <T : Any> setting(option: ProviderOption<T>): T? =
        option(option.key)?.let { option.parse(it, "$key.${option.key}") }

    /** The settings under the map [option] ([ProviderOption.map]), their keys relative to it. */
    fun options(option: ProviderOption<Map<String, String>>): Map<String, String> = options(option.key)

    /** The settings under [prefix], their keys relative to it. */
    fun options(prefix: String): Map<String, String> {
        asked += prefix
        return values.filterKeys { it.startsWith("$prefix.") }.mapKeys { it.key.removePrefix("$prefix.") }
    }

    /** The keys of [values] asked for, and the prefixes, so a test can hold them to the options a provider declares. */
    @InternalWowApi
    val read: Set<String> get() = asked.toSet()

    override fun toString(): String = "ProviderSettings(key=$key, values=${values.keys})"
}

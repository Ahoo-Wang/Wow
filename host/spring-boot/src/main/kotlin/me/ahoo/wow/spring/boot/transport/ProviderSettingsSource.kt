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

package me.ahoo.wow.spring.boot.transport

import me.ahoo.wow.spi.config.ProviderOption
import org.springframework.core.env.Environment

/**
 * Where the settings of a provider (a store's, a dataset backend's, a peer discovery's) are read from: those of its
 * [options] under a prefix, each by its key, and the [keys] set there as written, for a refusal of one that no option
 * names. The environment's reads each option through Spring's binding ([PortProviders.bound]), so the read takes
 * every form the configuration keys' check accepts (#6850).
 */
internal interface ProviderSettingsSource {
    /** The settings under [prefix] that [options] of the [scopes] declare, keyed by each option's key relative to it. */
    fun options(
        prefix: String,
        options: List<ProviderOption<*>>,
        scopes: Collection<ProviderOption.Scope> = listOf(ProviderOption.Scope.TYPE),
    ): Map<String, String>

    /** The keys set under [prefix], each relative to it as its source writes it. */
    fun keys(prefix: String): Set<String>

    companion object {
        /** [environment]'s settings. */
        fun of(environment: Environment): ProviderSettingsSource = object : ProviderSettingsSource {
            override fun options(
                prefix: String,
                options: List<ProviderOption<*>>,
                scopes: Collection<ProviderOption.Scope>,
            ): Map<String, String> = PortProviders.bound(environment, prefix, options, scopes)

            override fun keys(prefix: String): Set<String> = PortProviders.settingsOf(environment, prefix).keys
        }

        /** The [settings] of each prefix, keyed as the options' keys are: a test's, or none. */
        fun of(
            settings: (prefix: String) -> Map<String, String>
        ): ProviderSettingsSource = object : ProviderSettingsSource {
            override fun options(
                prefix: String,
                options: List<ProviderOption<*>>,
                scopes: Collection<ProviderOption.Scope>,
            ): Map<String, String> = PortProviders.declared(settings(prefix), options, scopes)

            override fun keys(prefix: String): Set<String> = settings(prefix).keys
        }

        /** No settings. */
        val NONE: ProviderSettingsSource = of { emptyMap() }
    }
}

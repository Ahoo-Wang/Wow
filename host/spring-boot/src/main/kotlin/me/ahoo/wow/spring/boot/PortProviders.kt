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

import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.util.ServiceLoader

/**
 * The topic transports and the Sinks a node can open (finding R5.B-5), found as its store providers are
 * ([StoreProviders]): on the classpath (`ServiceLoader`) and among the application's beans, a bean taking the place of
 * the provider of its type on the classpath. The host knows no transport or Sink of its own: it opens what a provider
 * gives over the ports of `:spi`.
 *
 * Until the configuration surface is final, a provider of type `<type>` is configured by the keys under
 * `wow.<type>` (`wow.kafka.*`, `wow.clickhouse.*`), handed to it as its options, each relative to that prefix.
 */
internal class PortProviders(
    val transports: Map<String, TransportProvider>,
    val sinks: Map<String, SinkProvider>,
) {
    /** The transports [environment] configures, each opened from [resources]; more than one is ambiguous. */
    fun transportConfigs(
        environment: Environment,
        resources: StoreResources
    ): List<Pair<TransportProvider, TransportConfig>> =
        transports.values.map { provider ->
            provider to TransportConfig.of(
                provider.type,
                provider.type,
                optionsOf(environment, provider.type),
                resources,
                environment.activeProfiles.toSet(),
            )
        }.filter { (provider, config) -> configured(config.key) { provider.available(config) } }

    /** The one transport [environment] configures, or null; fails naming both when two are. */
    fun transportConfig(
        environment: Environment,
        resources: StoreResources
    ): Pair<TransportProvider, TransportConfig>? {
        val configured = transportConfigs(environment, resources)
        configCheck(
            configured.size <= 1,
            "wow.<transport>",
            configured.map { it.first.type },
            "Configure one topic transport.",
        ) { "Several topic transports are configured: ${configured.map { "wow.${it.first.type}" }}." }
        return configured.singleOrNull()
    }

    /** The Sinks [environment] configures, each opened from [resources], with [extra] settings of each type. */
    fun sinkConfigs(
        environment: Environment,
        resources: StoreResources,
        extra: (type: String) -> Map<String, String> = { emptyMap() },
    ): List<Pair<SinkProvider, SinkConfig>> = sinks.values.map { provider ->
        provider to SinkConfig.of(
            provider.type,
            provider.type,
            optionsOf(environment, provider.type) + extra(provider.type),
            resources,
        )
    }.filter { (provider, config) -> configured(config.key) { provider.available(config) } }

    companion object {
        /**
         * [block], a provider reading the settings under [key]: a setting it refuses (an [IllegalArgumentException])
         * fails the startup as Wow's configuration does, naming the key ([WowConfigurationException]).
         */
        inline fun <T> configured(key: String, block: () -> T): T = try {
            block()
        } catch (refused: IllegalArgumentException) {
            throw WowConfigurationException(
                key,
                null,
                refused.message ?: "A setting under $key is refused.",
                "Correct the setting the reason names.",
            ).apply { initCause(refused) }
        }

        /** The providers [classLoader] finds, then [transportBeans] and [sinkBeans]. */
        fun load(
            classLoader: ClassLoader?,
            transportBeans: List<TransportProvider> = emptyList(),
            sinkBeans: List<SinkProvider> = emptyList(),
        ): PortProviders {
            val loader = classLoader ?: PortProviders::class.java.classLoader
            return PortProviders(
                byType(ServiceLoader.load(TransportProvider::class.java, loader).toList(), "classpath", "transport") {
                    it.type
                } + byType(transportBeans, "bean", "transport") { it.type },
                byType(ServiceLoader.load(SinkProvider::class.java, loader).toList(), "classpath", "Sink") {
                    it.type
                } + byType(sinkBeans, "bean", "Sink") { it.type },
            )
        }

        /** The settings under `wow.<type>`, flattened to keys relative to it (`topics.ddl`, `properties.linger.ms`). */
        fun optionsOf(environment: Environment, type: String): Map<String, String> =
            Binder.get(environment).bind("wow.$type", Bindable.mapOf(String::class.java, String::class.java))
                .orElse(emptyMap())
                .orEmpty()

        private fun <P : Any> byType(
            providers: List<P>,
            source: String,
            kind: String,
            type: (P) -> String,
        ): Map<String, P> = providers.groupBy(type).mapValues { (name, found) ->
            check(
                found.size == 1
            ) { "The ${source}s $found are all providers of the $kind type $name: keep one of them." }
            found.single()
        }
    }
}

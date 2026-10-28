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

import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.util.ServiceLoader

/**
 * The topic transports and the Sinks a node can open, found as its store providers are
 * ([StoreProviders]): on the classpath (`ServiceLoader`) and among the application's beans, a bean taking the place of
 * the provider of its type on the classpath. The host knows no transport or Sink of its own: it opens what a provider
 * gives over the ports of `:spi`.
 *
 * A provider of type `<type>` is configured by the keys under `wow.<type>` (`wow.kafka.*`, `wow.clickhouse.*`), of which
 * it is handed those it declares ([declared]), each relative to that prefix; the others are another provider's of the
 * type (the ClickHouse Sink's and dataset backend's share `wow.clickhouse`), and a key no provider declares fails the
 * start ([ConfigurationKeys]).
 */
internal class PortProviders(
    val transports: Map<String, TransportProvider>,
    val sinks: Map<String, SinkProvider>,
) {
    /**
     * The transports [environment] configures, each opened from [resources] for a node that runs for [development] or
     * not, of the [type] alone when it names one; more than one is ambiguous.
     */
    fun transportConfigs(
        environment: Environment,
        resources: StoreResources,
        development: Boolean = false,
        type: String? = null,
    ): List<Pair<TransportProvider, TransportConfig>> =
        transports.values.filter { type == null || it.type == type }.map { provider ->
            provider to TransportConfig.of(
                provider.type,
                provider.type,
                declared(optionsOf(environment, provider.type), TopicTransportOptions.of(provider)),
                resources,
                development,
            )
        }.filter { (provider, config) -> configured(config.key) { provider.available(config) } }

    /** The one transport [environment] configures, or null; fails naming both when two are. */
    fun transportConfig(
        environment: Environment,
        resources: StoreResources,
        development: Boolean = false,
        type: String? = null,
    ): Pair<TransportProvider, TransportConfig>? {
        val configured = transportConfigs(environment, resources, development, type)
        configCheck(
            configured.size <= 1,
            "wow.<transport>",
            configured.map { it.first.type },
            "Configure one topic transport.",
        ) { "Several topic transports are configured: ${configured.map { "wow.${it.first.type}" }}." }
        return configured.singleOrNull()
    }

    /**
     * The Sinks [environment] configures, each opened from [resources], with [extra] settings of each type, the host's
     * own: defaults that the type's own settings under `wow.<type>` override, and what it gives the Sink itself (the
     * ClickHouse Sink's per-aggregate history), of which each is given those it declares.
     */
    fun sinkConfigs(
        environment: Environment,
        resources: StoreResources,
        extra: (type: String) -> Map<String, String> = { emptyMap() },
    ): List<Pair<SinkProvider, SinkConfig>> = sinks.values.map { provider ->
        val own = declared(extra(provider.type), provider.options, ProviderOption.Scope.entries)
        provider to SinkConfig.of(
            provider.type,
            provider.type,
            own + declared(optionsOf(environment, provider.type), provider.options),
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

        /**
         * The [settings] that [options] of the [scopes] declare: each an option's key, or under a map option's prefix
         * ([ProviderOption.map]).
         */
        fun declared(
            settings: Map<String, String>,
            options: List<ProviderOption<*>>,
            scopes: Collection<ProviderOption.Scope> = listOf(ProviderOption.Scope.TYPE),
        ): Map<String, String> {
            val ofScopes = options.filter { it.scope in scopes }
            return settings.filterKeys { key ->
                ofScopes.any { option -> key == option.key || option.isMap && key.startsWith("${option.key}.") }
            }
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

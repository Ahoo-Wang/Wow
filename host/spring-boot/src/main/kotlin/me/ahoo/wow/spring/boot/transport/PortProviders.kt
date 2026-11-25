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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.spring.boot.config.ConfigurationKeys
import me.ahoo.wow.spring.boot.store.StoreProviders
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
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
                bound(environment, "wow.${provider.type}", TopicTransportOptions.of(provider)),
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
        val own = declared(extra(provider.type), provider.options)
        provider to SinkConfig.of(
            provider.type,
            provider.type,
            own + bound(environment, "wow.${provider.type}", provider.options),
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
         * The [settings] that [options] declare, of every scope: each an option's key, or under a map option's prefix
         * ([ProviderOption.map]). Only the host's own settings of a Sink are read so; the environment's are [bound].
         */
        private fun declared(settings: Map<String, String>, options: List<ProviderOption<*>>): Map<String, String> =
            settings.filterKeys { key ->
                options.any { option -> key == option.key || option.isMap && key.startsWith("${option.key}.") }
            }

        /**
         * The settings of [environment] under [prefix] that [options] of the [scopes] declare, each read by its full
         * key through Spring's binding (#6850): an option is read in every form Spring binds and the configuration
         * keys' check accepts ([ConfigurationKeys]), `command-intake.enabled`, `commandIntake.enabled` or the
         * variable `WOW_KAFKA_COMMANDINTAKE_ENABLED`, the environment's order deciding between two of them, and keyed
         * by the option's key relative to [prefix]; a map option's entries under its prefix, each as its source
         * writes it (`properties.linger.ms`).
         */
        fun bound(
            environment: Environment,
            prefix: String,
            options: List<ProviderOption<*>>,
            scopes: Collection<ProviderOption.Scope> = listOf(ProviderOption.Scope.TYPE),
        ): Map<String, String> {
            val binder = Binder.get(environment)
            return buildMap {
                options.filter { it.scope in scopes }.forEach { option ->
                    val key = "$prefix.${option.key}"
                    if (option.isMap) {
                        binder.bind(key, Bindable.mapOf(String::class.java, String::class.java)).ifBound { entries ->
                            entries.forEach { (entry, value) -> put("${option.key}.$entry", value) }
                        }
                    } else {
                        binder.bind(key, String::class.java).orElse(null)?.let { put(option.key, it) }
                    }
                }
            }
        }

        /**
         * Whether [key], a setting relative to a provider's prefix as its source writes it, names [option] in a form
         * Spring binds: the same elements but for case and dashes (`commandIntake.enabled`, `commandintake.enabled`),
         * or, as an environment variable may write it, the option's dashes as dots (`command.intake.enabled`); for a
         * map option, a key under its prefix so written.
         */
        fun names(key: String, option: ProviderOption<*>): Boolean {
            val name = ConfigurationPropertyName.adapt(key, '.')
            val elements = (0 until name.numberOfElements).map {
                name.getElement(
                    it,
                    ConfigurationPropertyName.Form.UNIFORM
                )
            }
            val target = ConfigurationPropertyName.adapt(option.key, '.').let { own ->
                (0 until own.numberOfElements).joinToString(
                    ""
                ) { own.getElement(it, ConfigurationPropertyName.Form.UNIFORM) }
            }
            return if (option.isMap) {
                (1 until elements.size).any { elements.take(it).joinToString("") == target }
            } else {
                elements.joinToString("") == target
            }
        }

        /**
         * The settings of [environment] under [prefix], each key relative to it as its source writes it, nested ones
         * dot-separated: what is set there, for a refusal of a key no option names to quote; never what an option reads
         * ([bound]).
         */
        fun settingsOf(environment: Environment, prefix: String): Map<String, String> =
            Binder.get(environment).bind(prefix, Bindable.mapOf(String::class.java, String::class.java))
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

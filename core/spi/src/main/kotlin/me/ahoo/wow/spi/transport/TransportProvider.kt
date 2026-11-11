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

package me.ahoo.wow.spi.transport

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.ProviderSettings
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.store.StoreResources

/**
 * A topic transport: what opens the broker a node's published streams, its commands to
 * other contexts and their receipts go through. Wow's Kafka transport (`kafka`) is a provider as a community one, a
 * Pulsar transport say, is: the host finds them on the classpath (`ServiceLoader`, from the module's
 * `META-INF/services/me.ahoo.wow.spi.transport.TransportProvider`) and among the application's beans, a bean taking the
 * place of a provider of its type on the classpath, as it finds a [me.ahoo.wow.spi.store.StoreProvider].
 *
 * A provider is stateless: [open] gives each configured transport its own [TopicTransport], over the settings
 * [TransportConfig.settings] holds and the application's [TransportConfig.resources].
 */
@WowSpi
interface TransportProvider {
    /** The type a transport's configuration names: lowercase, unique among the providers of a node. */
    val type: String

    /** What a transport of this type needs, in the words of a startup failure that says it is missing. */
    val requirement: String get() = "its settings"

    /**
     * Every setting of its own it reads from [TransportConfig.settings], each read through its option, for the host to
     * bind and check (`wow.<type>.<key>`): each of [ProviderOption.Scope.TYPE], since a transport has no instances
     * with settings of their own ([ProviderOption.Scope.INSTANCE]). The settings every topic transport has
     * ([TopicTransportOptions]) are not listed again: [TopicTransportOptions.of] joins them.
     */
    val options: List<ProviderOption<*>> get() = emptyList()

    /** Whether [config] configures a transport of this type: the settings it cannot do without are set. */
    fun available(config: TransportConfig): Boolean

    /** Opens the transport [config] configures. */
    fun open(config: TransportConfig): TopicTransport
}

/**
 * One open topic transport: the [producer] the node sends through, the [source] its sourced subscriptions read, and the
 * [admin] it describes and creates its topics with. [close] closes them, after the node has stopped.
 */
@WowSpi
interface TopicTransport : AutoCloseable {
    val producer: TopicProducer

    val source: PartitionedSource

    val admin: TopicAdmin

    /**
     * What the transport has to warn about the node's [topics] once the node is ready, such as a command topic that
     * does not survive the loss of a broker; none by default. It never fails: a broker that cannot be
     * asked gives no warning.
     */
    suspend fun warnings(topics: Set<String>): List<String> = emptyList()

    override fun close() = Unit
}

/**
 * What a [TransportProvider] opens a transport from: its [name] and [type], its own settings ([settings], each key
 * relative to the transport's configuration, `bootstrap-servers` say), the application's [resources], and whether the
 * node runs for [development] (a profile of `wow.dev-profiles` is active, or its internal port binds a loopback address
 * alone): a provider may say less then.
 *
 * Fields may be added: the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
class TransportConfig @InternalWowApi constructor(
    val name: String,
    val type: String,
    options: Map<String, String> = emptyMap(),
    val resources: StoreResources = StoreResources.NONE,
    val development: Boolean = false,
    /** The node's observer, which the transport tells what it meets, such as an offset gap. */
    val observer: Observer = Observer.NONE,
) {
    init {
        require(name.isNotBlank()) { "A transport needs a name." }
        require(type.isNotBlank()) { "A transport needs a type." }
    }

    /** The configuration key of the transport's settings, for a provider's failures to name. */
    val key: String get() = "wow.$name"

    /** Its settings, under [key]: what its provider reads its options from ([ProviderSettings]). */
    val settings: ProviderSettings = ProviderSettings(key, options)

    override fun toString(): String = "TransportConfig(name=$name, type=$type, options=${settings.values.keys})"

    companion object {
        /** A configuration of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            type: String,
            options: Map<String, String> = emptyMap(),
            resources: StoreResources = StoreResources.NONE,
            development: Boolean = false,
            observer: Observer = Observer.NONE,
        ): TransportConfig = TransportConfig(name, type, options, resources, development, observer)
    }
}

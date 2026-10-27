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

package me.ahoo.wow.spi.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreResources

/**
 * A Sink of the streams a service publishes: what opens the [Sink]s of one destination,
 * a warehouse's tables say. Wow's ClickHouse Sink (`clickhouse`) is a provider as a community one is: the host finds
 * them on the classpath (`ServiceLoader`, from the module's `META-INF/services/me.ahoo.wow.spi.sink.SinkProvider`) and
 * among the application's beans, a bean taking the place of a provider of its type on the classpath, as it finds a
 * [me.ahoo.wow.spi.store.StoreProvider], and runs one sink subscription per published stream over what it opens.
 *
 * A provider is stateless: [open] gives each configured sink its own [StreamSinks], over the settings
 * [SinkConfig.options] holds and the application's [SinkConfig.resources] (the service's Manifest among them).
 */
@WowSpi
interface SinkProvider {
    /** The type a sink's configuration names: lowercase, unique among the providers of a node. */
    val type: String

    /** What a sink of this type needs, in the words of a startup failure that says it is missing. */
    val requirement: String get() = "its settings"

    /** Whether [config] configures a sink of this type: the settings it cannot do without are set, and not disabled. */
    fun available(config: SinkConfig): Boolean

    /** Opens the sinks [config] configures. */
    fun open(config: SinkConfig): StreamSinks
}

/**
 * The sinks of one destination, one per published stream: the subscription that writes the stream of `aggregate` in
 * `context` is named `<processor>.<aggregate>` and writes through [sinkOf]. [close] closes what they share, after
 * the node has stopped.
 */
@WowSpi
interface StreamSinks : AutoCloseable {
    /** The name the sink subscriptions take, before the aggregate's: `clickhouse-sink` say. */
    val processor: String

    /** The sink of the stream [context] publishes of [aggregate]. */
    fun sinkOf(context: String, aggregate: String): Sink

    override fun close() = Unit
}

/**
 * What a [SinkProvider] opens its sinks from: their [name] and [type], their own settings ([options], each key relative
 * to the sink's configuration, `endpoint` say), and the application's [resources].
 *
 * A Sink that writes into one of the node's stores, as an event search index written into an Elasticsearch store is,
 * has that opened [store], and writes the [streams] the host names, the aggregate types whose events that store is to
 * serve; a Sink of a destination of its own has neither, and writes the streams the Manifest publishes.
 *
 * Fields may be added: the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
class SinkConfig @InternalWowApi constructor(
    val name: String,
    val type: String,
    val options: Map<String, String> = emptyMap(),
    val resources: StoreResources = StoreResources.NONE,
    /** The aggregate types this Sink writes, null for the streams the Manifest publishes. */
    val streams: Set<AggregateType>? = null,
    /** The node's opened store this Sink writes into, null for a destination of its own. */
    val store: Store? = null,
) {
    init {
        require(name.isNotBlank()) { "A sink needs a name." }
        require(type.isNotBlank()) { "A sink needs a type." }
    }

    /** The configuration key of the sink's settings, for a provider's failures to name. */
    val key: String get() = "wow.$name"

    /** The setting [name] of [options], or null when it is not set or blank. */
    fun option(name: String): String? = options[name]?.takeIf { it.isNotBlank() }

    /** The settings under [prefix], their keys relative to it. */
    fun options(prefix: String): Map<String, String> = options.filterKeys { it.startsWith("$prefix.") }
        .mapKeys { it.key.removePrefix("$prefix.") }

    override fun toString(): String = "SinkConfig(name=$name, type=$type, options=${options.keys})"

    companion object {
        /** A configuration of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            type: String,
            options: Map<String, String> = emptyMap(),
            resources: StoreResources = StoreResources.NONE,
            streams: Set<AggregateType>? = null,
            store: Store? = null,
        ): SinkConfig = SinkConfig(name, type, options, resources, streams, store)
    }
}

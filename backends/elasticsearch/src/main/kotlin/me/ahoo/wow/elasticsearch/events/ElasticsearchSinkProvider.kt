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

package me.ahoo.wow.elasticsearch.events

import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider.Companion.TYPE
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks

/**
 * The event search Sink as a [SinkProvider] of type `elasticsearch`: it writes into an
 * Elasticsearch store of the node ([SinkConfig.store]), the one an aggregate type's `events` queries read,
 * the streams of the types the host names ([SinkConfig.streams]), each through the store's client, index
 * prefix and DDL ([ElasticsearchEventSinks]). It is available only for such a store: it has no settings of its own.
 */
internal class ElasticsearchSinkProvider : SinkProvider {
    override val type: String = TYPE

    override val requirement: String = "an Elasticsearch store that an aggregate's query.events.store names"

    override fun available(config: SinkConfig): Boolean = config.store is ElasticsearchStore

    override fun open(config: SinkConfig): StreamSinks {
        val sinks = ElasticsearchEventSinks.of(requireNotNull(config.store) { "${config.key} writes into no store." })
        return object : StreamSinks {
            override val processor: String = ElasticsearchEventSinks.PROCESSOR

            override fun sinkOf(context: String, aggregate: String): Sink = sinks.sinkOf(context, aggregate)

            override fun toString(): String = sinks.toString()
        }
    }
}

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

package me.ahoo.wow.runtime.contract

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.subscribe.PublishedEvent
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.state.StateEventPayload

/**
 * The events of the contexts a service knows, decoded into their api classes: an event of a
 * published stream, `context.aggregate.Event`, is decoded into the class its context's contract names for it
 * (`#/$defs/<binary name>`) when that class is on [classLoader] and the event is of the revision the contract
 * declares; otherwise it is a [PublishedEvent] of its name, revision and JSON text. A matched class that does not
 * decode the payload fails the handling, as a state that does not decode does.
 */
@InternalWowApi
class PeerEvents(
    contexts: List<ContextManifest>,
    private val classLoader: ClassLoader = PeerEvents::class.java.classLoader,
) {
    private class Known(val binary: String, val revision: Int)

    private val known: Map<String, Known> = contexts.flatMap { context ->
        context.aggregates.flatMap { aggregate ->
            aggregate.events.map { event ->
                "${context.name}.${aggregate.name}.${event.name}" to Known(Manifest.schemaKey(event.schema), event.revision)
            }
        }
    }.toMap()

    private val classes = java.util.concurrent.ConcurrentHashMap<String, Class<*>?>()

    /** [events], a published stream's, each decoded into its api class or kept as a [PublishedEvent]. */
    fun decode(events: List<Any>, codec: EventCodec): List<Any> = events.map { event ->
        if (event is StateEventPayload) decode(event, codec) else event
    }

    private fun decode(event: StateEventPayload, codec: EventCodec): Any {
        val known = known[event.name]?.takeIf { it.revision == event.revision }
        val type = known?.let { classOf(it.binary) }
            ?: return PublishedEvent(event.name, event.revision, event.payload.toString())
        return codec.decodeTree(event.payload, type)
    }

    private fun classOf(binary: String): Class<*>? = classes.computeIfAbsent(binary) {
        runCatching { Class.forName(binary, false, classLoader) }.getOrNull()
    }

    @InternalWowApi
    companion object {
        /** Knows no context: every event is a [PublishedEvent]. */
        @JvmField
        val NONE: PeerEvents = PeerEvents(emptyList())

        /** The events of the contexts [contracts] knows: the service's own and its peers'. */
        @JvmStatic
        fun of(
            contracts: ContractDirectory,
            classLoader: ClassLoader = PeerEvents::class.java.classLoader
        ): PeerEvents =
            PeerEvents(contracts.allContexts, classLoader)
    }
}

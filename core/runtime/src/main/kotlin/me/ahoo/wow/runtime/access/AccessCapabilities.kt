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

package me.ahoo.wow.runtime.access

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog

/**
 * Where the `events` model of an aggregate type is served from: what reads its history's access attributes, which
 * only a store that keeps them current may serve (#7157).
 */
@InternalWowApi
sealed interface EventsSource {
    /** The type's own log, in place: it keeps them current when it stamps and walks ([AccessWalks]). */
    @InternalWowApi
    data object Log : EventsSource

    /**
     * Another [store], whose Sink indexes the type's streams: the Sink keeps them current when it stamps
     * ([AccessStamps]), and the type's log when it walks ([AccessWalks]), since the walk pages the log.
     */
    @InternalWowApi
    data class Store(val store: String) : EventsSource
}

/** The [EventsSource] of each aggregate type; null for a type whose `events` model is not served. */
@InternalWowApi
fun interface EventsSources {
    fun of(type: AggregateType): EventsSource?

    @InternalWowApi
    companion object {
        /** No type's `events` model is served: a hand-wired node, the test DSL. */
        @JvmField
        val NONE: EventsSources = EventsSources { null }
    }
}

/**
 * Whether the stores that serve each type's `events` model can keep its history's access attributes current: an
 * events query decides by them, and an attribute a store cannot keep current would authorize by what the aggregate no
 * longer has, and leave a stream appended without attributes outside every access policy. A type whose model is not
 * served is not checked: nothing reads its history's attributes.
 */
@InternalWowApi
object AccessCapabilities {
    /** One type's log, the store it is on, and whether a Sink of its streams stamps them (a walk's target). */
    @InternalWowApi
    class Placed(val type: AggregateType, val store: String, val log: EventLog, val stampingSink: Boolean)

    /** Why each of [placed] may not serve its `events` model as [sources] say, naming the type, the store and the capability. */
    fun refusals(placed: Collection<Placed>, sources: EventsSources): List<String> = placed.mapNotNull { place ->
        val type = place.type
        when (val source = sources.of(type)) {
            null -> null
            EventsSource.Log -> missing(place.log)?.let { capability ->
                "$type serves its events model from its log on the store '${place.store}', which does not keep its " +
                    "history's access attributes current (no $capability)"
            }

            is EventsSource.Store -> when {
                place.log !is AccessWalks ->
                    "$type serves its events model from the store '${source.store}', but its log on the store " +
                        "'${place.store}' does not walk its history's access attributes (no ${AccessWalks::class.simpleName})"

                !place.stampingSink ->
                    "$type serves its events model from the store '${source.store}', whose Sink does not stamp its " +
                        "history's access attributes (no ${AccessStamps::class.simpleName})"

                else -> null
            }
        }
    }

    /** The capability [log] lacks to keep a history's attributes current, the first one; null when it has both. */
    private fun missing(log: EventLog): String? = when {
        log !is AccessStamps -> AccessStamps::class.simpleName
        log !is AccessWalks -> AccessWalks::class.simpleName
        else -> null
    }
}

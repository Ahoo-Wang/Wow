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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.read.StateMasks
import tools.jackson.databind.JsonNode
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The masks of one aggregate [type]'s records, as [masks] give them for a read that does or does not reveal `DISPLAY`
 * values ([revealsDisplay]): its state's, taken once, and each event's, taken on its first record. A record of a type
 * and events that mask nothing costs a lookup, nothing more.
 */
internal class RecordMasks(
    private val masks: StateMasks,
    private val type: AggregateType,
    private val revealsDisplay: Boolean,
) {
    /** The mask of [type]'s states; null when its schema masks nothing. */
    val state: ((JsonNode) -> JsonNode)? = masks.stateMaskOf(type, revealsDisplay)

    private val events = ConcurrentHashMap<String, Optional<(JsonNode) -> JsonNode>>()

    /** The mask of the bodies of the event named [name], at its current name; null when its schema masks nothing. */
    fun event(name: String): ((JsonNode) -> JsonNode)? =
        (events[name] ?: events.computeIfAbsent(name) { Optional.ofNullable(masks.eventMaskOf(type, it, revealsDisplay)) })
            .orElse(null)
}

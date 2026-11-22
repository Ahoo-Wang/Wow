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
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList

/** Masks that mask nothing: the masks of a node whose schemas declare nothing sensitive. */
internal object NoMasks : StateMasks {
    override fun stateMaskOf(type: AggregateType, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? = null

    override fun eventMaskOf(type: AggregateType, event: String, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? =
        null
}

/** The publication masks of a node whose schemas declare nothing sensitive. */
internal val UNMASKED: PublicationMasks = PublicationMasks(NoMasks, displayShared = true)

/**
 * Masks that replace [field] of every state, when [masksStates], and of the bodies of the events [events] names by
 * `***`, as the query side's masks would a sensitive field; each mask asked for is recorded in [asked], with how.
 */
internal class FieldMasks(
    private val field: String,
    private val events: Set<String>,
    private val masksStates: Boolean = true,
) : StateMasks {
    val asked: MutableList<String> = CopyOnWriteArrayList()

    private val mask: (JsonNode) -> JsonNode = { node -> (node as ObjectNode).put(field, "***") }

    override fun stateMaskOf(type: AggregateType, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? {
        asked += "state $type revealsDisplay=$revealsDisplay"
        return mask.takeIf { masksStates }
    }

    override fun eventMaskOf(type: AggregateType, event: String, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? {
        asked += "event $type $event revealsDisplay=$revealsDisplay"
        return mask.takeIf { event in events }
    }
}

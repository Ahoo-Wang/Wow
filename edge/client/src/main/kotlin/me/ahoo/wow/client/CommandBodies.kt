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

package me.ahoo.wow.client

import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.requireUse
import me.ahoo.wow.spi.json.BodyAggregateIds
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The JSON bodies of commands sent to a peer's routes, those of a command whose body names its aggregate
 * (`idProperty`, its class's `@AggregateId`) among them, which go to their route without an id.
 */
internal object CommandBodies {
    /**
     * [body] as JSON, the body of the command [name] to the aggregate [id] (null for none), whose contract reads its
     * aggregate's id from [idProperty] when it names one: the body names [id] too, or [id] is written into a body that
     * names none, so that the route without an id carries it. A body that names another aggregate is the caller's
     * mistake, a [Misuse.Argument].
     */
    fun of(name: String, body: Any, idProperty: String?, id: String?): ByteArray {
        if (idProperty == null || id == null) {
            return ClientJson.write(body)
        }
        val tree = ClientJson.mapper.valueToTree<JsonNode>(body)
        val inBody = try {
            BodyAggregateIds.of(tree, idProperty)
        } catch (invalid: IllegalArgumentException) {
            throw Misuse.Argument("The body of $name names no aggregate: ${invalid.message}")
        }
        requireUse(inBody == null || inBody == id) {
            "The request names the aggregate $id, but the body of $name names $inBody: send it without an id, " +
                "CommandRequest.of(body)."
        }
        if (inBody == null) {
            requireUse(tree is ObjectNode) { "The body of $name is no JSON object." }
            tree.put(idProperty, id)
        }
        return ClientJson.mapper.writeValueAsBytes(tree)
    }
}

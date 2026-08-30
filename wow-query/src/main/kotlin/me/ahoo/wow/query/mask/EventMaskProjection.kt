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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.Projection
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal const val EVENT_BODY_TYPE_PATH = "body.bodyType"
private const val EVENT_BODY_PATH = "body"

internal fun Projection.requiresInternalEventBodyType(): Boolean {
    val bodySelected = include.isEmpty() || include.any { it == EVENT_BODY_PATH || it.startsWith("$EVENT_BODY_PATH.") }
    val bodyExcluded = EVENT_BODY_PATH in exclude
    if (!bodySelected || bodyExcluded) return false

    val bodyTypeSelected = include.isEmpty() || include.any {
        it == EVENT_BODY_TYPE_PATH || EVENT_BODY_TYPE_PATH.startsWith("$it.")
    }
    return !bodyTypeSelected || EVENT_BODY_TYPE_PATH in exclude
}

internal fun Projection.withInternalEventBodyType(): Projection {
    if (!requiresInternalEventBodyType()) return this
    return if (include.isNotEmpty()) {
        copy(include = include + EVENT_BODY_TYPE_PATH, exclude = exclude - EVENT_BODY_TYPE_PATH)
    } else {
        copy(exclude = exclude - EVENT_BODY_TYPE_PATH)
    }
}

internal fun ObjectNode.removeInternalEventBodyType(): ObjectNode = apply {
    get(EVENT_BODY_PATH)?.takeIf(JsonNode::isArray)?.forEach { event ->
        (event as? ObjectNode)?.remove("bodyType")
    }
}

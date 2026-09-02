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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.filter.QueryContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal const val EVENT_BODY_TYPE_PATH = "body.bodyType"
private const val EVENT_BODY_PATH = "body"
private const val EVENT_BODY_PAYLOAD_PATH = "body.body"
private const val INTERNAL_EVENT_BODY_TYPE_PROJECTED = "wow.query.internal-event-body-type-projected"

internal var QueryContext<*, *>.internalEventBodyTypeProjected: Boolean
    get() = getAttribute<Boolean>(INTERNAL_EVENT_BODY_TYPE_PROJECTED) == true
    set(value) {
        setAttribute(INTERNAL_EVENT_BODY_TYPE_PROJECTED, value)
    }

internal fun Projection.requiresInternalEventBodyType(
    bodyTypePath: String = EVENT_BODY_TYPE_PATH,
): Boolean {
    val bodySelected = include.isEmpty() || include.any { EVENT_BODY_PAYLOAD_PATH.intersectsSelection(it.path) }
    val bodyExcluded = exclude.any { EVENT_BODY_PAYLOAD_PATH.isSelectedBy(it.path) }
    if (!bodySelected || bodyExcluded) return false

    val bodyTypeSelected = include.isEmpty() || include.any { bodyTypePath.isSelectedBy(it.path) }
    return !bodyTypeSelected || exclude.any { bodyTypePath.isSelectedBy(it.path) }
}

internal fun Projection.hasUnrestorableInternalEventBodyTypeExclusion(
    bodyTypePath: String = EVENT_BODY_TYPE_PATH,
): Boolean = exclude.any {
    '*' in it.path && bodyTypePath.matchesProjectionPattern(it.path)
}

internal fun Projection.withInternalEventBodyType(
    bodyTypePath: String = EVENT_BODY_TYPE_PATH,
): Projection =
    if (include.isNotEmpty()) {
        copy(include = include + QueryField(bodyTypePath), exclude = exclude - QueryField(bodyTypePath))
    } else {
        copy(exclude = exclude - QueryField(bodyTypePath))
    }

internal fun ObjectNode.removeInternalEventBodyType(): ObjectNode = apply {
    get(EVENT_BODY_PATH)?.takeIf(JsonNode::isArray)?.forEach { event ->
        (event as? ObjectNode)?.remove("bodyType")
    }
}

private fun String.isSelectedBy(pattern: String): Boolean =
    this == pattern || startsWith("$pattern.") || matchesProjectionPattern(pattern)

private fun String.intersectsSelection(pattern: String): Boolean =
    isSelectedBy(pattern) || pattern.startsWith("$this.")

private fun String.matchesProjectionPattern(pattern: String): Boolean {
    if ('*' !in pattern) return false
    return pattern.split('*').joinToString(".*", "^", "$") { Regex.escape(it) }.toRegex().matches(this)
}

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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.Scope
import tools.jackson.databind.JsonNode

/**
 * What admission refuses as [me.ahoo.wow.api.result.FrameworkCodes.COMMAND_MALFORMED] before any store sees it, so that every
 * backend answers alike:
 *
 * - an id longer than [MAX_ID_LENGTH] characters: a request id, an aggregate id, a tenant id or a space id. PostgreSQL's
 *   indexes do not take a key of more than about 2.7 KB, and a refused append must not look like one whose outcome is
 *   unknown, which the caller retries for ever;
 * - a body that writes the character `\u0000` in a string or a key: PostgreSQL's `jsonb` does not take it, MongoDB
 *   does, and a fact one backend keeps and another refuses would make the choice of backend change what commits.
 */
internal object AdmissionLimits {
    /** The longest id admission takes, in characters. */
    const val MAX_ID_LENGTH: Int = 256

    /** Why an id of a request is too long, or null when none is. */
    fun oversized(requestId: String, aggregateId: String, tenantId: String, scope: Scope): String? =
        oversized("request id", requestId) ?: oversized("aggregate id", aggregateId)
            ?: oversized("tenant id", tenantId) ?: oversized("space id", scope.spaceId)

    /** Checked one by one, without a list of pairs per command. */
    private fun oversized(name: String, id: String?): String? = id?.takeIf { it.length > MAX_ID_LENGTH }
        ?.let { "A $name is at most $MAX_ID_LENGTH characters; this one has ${it.length}." }

    /**
     * Whether the JSON text [json] writes `\u0000` in a string or a key. It reads the escapes alone: in JSON a
     * backslash only ever starts one, inside a string, and the character itself may not appear unescaped.
     */
    fun writesNul(json: ByteArray): Boolean {
        var index = 0
        while (index < json.size) {
            if (json[index] == BACKSLASH) {
                if (isNulEscape(json, index)) {
                    return true
                }
                // The escaped character, a backslash included, is not the start of another escape.
                index += 2
            } else {
                index++
            }
        }
        return false
    }

    private fun isNulEscape(json: ByteArray, at: Int): Boolean =
        at + NUL_ESCAPE.length <= json.size && NUL_ESCAPE.indices.all { json[at + it] == NUL_ESCAPE[it].code.toByte() }

    /** Whether [node] holds `\u0000` in a string or a key, at any depth. */
    fun holdsNul(node: JsonNode): Boolean = when {
        node.isString -> node.asString().contains(NUL)
        node.isObject -> node.properties().any { (name, value) -> name.contains(NUL) || holdsNul(value) }
        node.isArray -> node.any { holdsNul(it) }
        else -> false
    }

    private const val NUL = '\u0000'
    private const val NUL_ESCAPE = "\\u0000"
    private const val BACKSLASH = '\\'.code.toByte()
}

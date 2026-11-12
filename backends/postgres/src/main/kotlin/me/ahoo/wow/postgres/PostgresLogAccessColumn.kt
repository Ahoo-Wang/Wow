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

package me.ahoo.wow.postgres

import me.ahoo.wow.spi.access.AccessValues
import tools.jackson.databind.node.ObjectNode

/**
 * A log stream's access attributes as the log's `access` column holds them: the built-in owner and space are left out
 * without a value, as the MongoDB log leaves them out, and put back as null, first, when read, as a stream holds them.
 * The query side reads an absent key as it read a null one: its typed expressions and `isNull` take both as null.
 */
internal object PostgresLogAccessColumn {
    private val BUILT_IN = listOf(AccessValues.OWNER, AccessValues.SPACE)

    /** [access] as the column's text. */
    fun text(access: Map<String, Any?>): String =
        AccessValues.text(access.filterNot { (name, value) -> value == null && name in BUILT_IN })

    /** The attributes the column's [text] holds, the built-in ones put back; null for a stream not stamped. */
    fun of(text: String?): Map<String, Any?>? = AccessValues.parse(text)?.let(::restored)

    /** The attributes the column's [text] holds as a JSON object, the built-in ones put back. */
    fun node(text: String): ObjectNode = AccessValues.json(checkNotNull(of(text)))

    private fun restored(access: Map<String, Any?>): Map<String, Any?> {
        if (BUILT_IN.all { it in access }) return access
        return LinkedHashMap<String, Any?>((access.size + BUILT_IN.size) * 2).apply {
            BUILT_IN.forEach { put(it, access[it]) }
            access.forEach { (name, value) -> if (name !in BUILT_IN) put(name, value) }
        }
    }
}

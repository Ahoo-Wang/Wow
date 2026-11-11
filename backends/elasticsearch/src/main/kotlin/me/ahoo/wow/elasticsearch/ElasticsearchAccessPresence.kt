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

package me.ahoo.wow.elasticsearch

import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * Which access attributes a document holds, apart from their values. Elasticsearch indexes nothing for an empty array
 * or a null, so under `access` a multiple attribute that is present and empty reads as one the document lacks, where
 * a policy tells them apart (an empty set does not contain a value; a missing attribute never grants).
 *
 * [FIELD] holds `true` under each attribute present: a single one with a value, a multiple one, empty or not. The
 * presence of `access.<name>` reads `accessPresent.<name>` ([markerOf]), on a snapshot's document and on a stream's. A
 * document written before an attribute was declared lacks it there too, as the attribute is missing from it, until the
 * next write of its aggregate (a commit, or the access walk's rewrite) records it.
 */
internal object ElasticsearchAccessPresence {
    /** The field of the attributes present. */
    const val FIELD = "accessPresent"

    private const val ACCESS_PREFIX = "access."

    /** The attributes of [access] present, each `true`. */
    fun of(access: Map<String, Any?>): Map<String, Boolean> =
        access.filterValues { it != null }.mapValues { true }

    /** [of] as the JSON object a document holds under [FIELD]. */
    fun json(access: Map<String, Any?>): ObjectNode = JsonNodeFactory.instance.objectNode().also { node ->
        of(access).keys.forEach { name -> node.put(name, true) }
    }

    /** The attribute `access.<name>` whose presence [marker] marks; null for a path that is no marker. */
    fun valuesOf(marker: String): String? {
        val name = marker.removePrefix("$FIELD.")
        return if (name != marker && name.isNotEmpty() && '.' !in name) "$ACCESS_PREFIX$name" else null
    }

    /** The marker of the presence of [source], an attribute `access.<name>`; null for any other path. */
    fun markerOf(source: String): String? {
        val name = source.removePrefix(ACCESS_PREFIX)
        return if (name != source && name.isNotEmpty() && '.' !in name) "$FIELD.$name" else null
    }
}

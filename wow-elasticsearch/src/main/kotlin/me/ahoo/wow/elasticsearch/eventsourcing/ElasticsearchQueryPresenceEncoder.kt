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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.eventsourcing

internal object ElasticsearchQueryPresenceEncoder {
    const val VERSION: Int = 1
    const val NAMESPACE: String = "__wow_query"
    const val PRESENT: String = "present"
    const val NULL: String = "null"

    fun encode(source: Map<String, Any?>): Map<String, Any?> = encodeObject(source)

    fun strip(source: Map<String, Any?>): Map<String, Any?> = stripObject(source)

    private fun encodeObject(source: Map<*, *>): Map<String, Any?> {
        require(!source.containsKey(NAMESPACE)) { "Elasticsearch query presence namespace is reserved." }
        val encoded = LinkedHashMap<String, Any?>(source.size + 1)
        val present = ArrayList<String>(source.size)
        val nulls = ArrayList<String>()
        source.forEach { (rawKey, value) ->
            val key = rawKey as? String
                ?: throw IllegalArgumentException("Elasticsearch document object keys must be strings.")
            present += key
            if (value == null) {
                nulls += key
            }
            encoded[key] = encodeValue(value)
        }
        encoded[NAMESPACE] = linkedMapOf(
            PRESENT to present,
            NULL to nulls,
        )
        return encoded
    }

    private fun encodeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> encodeObject(value)
        is List<*> -> value.map(::encodeValue)
        is ByteArray -> value.copyOf()
        else -> value
    }

    private fun stripObject(source: Map<*, *>): Map<String, Any?> {
        val stripped = LinkedHashMap<String, Any?>(source.size)
        source.forEach { (rawKey, value) ->
            val key = rawKey as? String
                ?: throw IllegalArgumentException("Elasticsearch document object keys must be strings.")
            if (key != NAMESPACE) {
                stripped[key] = stripValue(value)
            }
        }
        return stripped
    }

    private fun stripValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> stripObject(value)
        is List<*> -> value.map(::stripValue)
        is ByteArray -> value.copyOf()
        else -> value
    }
}

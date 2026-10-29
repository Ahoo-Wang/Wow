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

package me.ahoo.wow.cocache

import me.ahoo.cocache.api.Codec
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * A state view [viewType] as its JSON: Wow's JSON, read as a reader of another context's state reads it, so a field
 * or an enumeration constant the owner adds later breaks no reader.
 */
internal class PublishedStateCodec<V : Any>(private val viewType: Class<V>) : Codec<V> {
    override fun encode(value: V): ByteArray = MAPPER.writeValueAsBytes(value)

    override fun decode(bytes: ByteArray): V = MAPPER.readValue(bytes, viewType)

    /** The view of [state], a state's JSON as the owner publishes it. */
    fun viewOf(state: JsonNode): V = MAPPER.treeToValue(state, viewType)

    override fun toString(): String = "PublishedStateCodec(${viewType.name})"

    private companion object {
        val MAPPER: JsonMapper = WowJson.readerBuilder().build()
    }
}

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

package me.ahoo.wow.spi.state

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode

/**
 * The state of an aggregate after its stream [version] as it is published: the [state]
 * as a JSON tree, numbers exact, and the [schemaFingerprint] of the state's schema, the one the
 * Manifest and the aggregate's snapshots carry, so a reader knows which schema the tree follows.
 *
 * The runtime keeps its own form of a view for the state consumers of a context (`runtime.state.SharedStates`): the
 * serialized text a snapshot stores, with the aggregate's scope, deletion and access attributes. A sink maps that form to this one.
 *
 * A sink makes one from that view's text ([ofText]): the tree is then parsed on its first read, once, and a writer of
 * the record writes the text as it is ([stateText]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class PublishedState private constructor(
    val version: Int,
    val schemaFingerprint: String,
    @Volatile private var tree: JsonNode?,
    /** The state as the JSON text it was made from; null when it was made from a tree. */
    @property:InternalWowApi val stateText: String?,
) {
    @InternalWowApi
    constructor(
        version: Int,
        schemaFingerprint: String,
        state: JsonNode,
    ) : this(version, schemaFingerprint, state, null)

    /** The state as a JSON tree, numbers exact: the one it was made from, or its text parsed on first read, once. */
    val state: JsonNode
        get() = tree ?: synchronized(this) { tree ?: WowJson.mapper.readTree(stateText).also { tree = it } }

    init {
        require(version >= 1) { "A committed state has a version from 1." }
        require(schemaFingerprint.isNotBlank()) { "A published state names the fingerprint of its schema." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PublishedState && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "PublishedState(version=$version, schemaFingerprint=$schemaFingerprint, state=$state)"

    private fun valueFields(): List<Any?> = listOf(version, schemaFingerprint, state)

    companion object {
        /** A PublishedState of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(version: Int, schemaFingerprint: String, state: JsonNode): PublishedState =
            PublishedState(version, schemaFingerprint, state)

        /**
         * A PublishedState of the state's JSON [text], the codec's own: a writer of the record writes it as it is, and
         * [state] parses it on its first read.
         */
        @InternalWowApi
        @JvmStatic
        fun ofText(version: Int, schemaFingerprint: String, text: String): PublishedState =
            PublishedState(version, schemaFingerprint, null, text)
    }
}

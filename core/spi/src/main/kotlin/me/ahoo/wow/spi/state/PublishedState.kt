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
import tools.jackson.databind.JsonNode

/**
 * The state of an aggregate after its stream [version] as it is published: the [state]
 * as a JSON tree, numbers exact, and the [schemaFingerprint] of the state's schema, the one the
 * Manifest and the aggregate's snapshots carry, so a reader knows which schema the tree follows.
 *
 * The runtime keeps its own form of a view for the state consumers of a context (`runtime.state.SharedStates`): the
 * serialized text a snapshot stores, with the aggregate's scope, deletion and tags. A sink maps that form to this one.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class PublishedState @InternalWowApi constructor(val version: Int, val schemaFingerprint: String, val state: JsonNode) {
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
    }
}

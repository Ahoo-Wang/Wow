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

import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import tools.jackson.databind.JsonNode

/**
 * The sink that writes one aggregate type's states into its cache: each commit, in version order per aggregate, its
 * state after the commit as the view [viewType], or its tombstone when the commit deleted it ([StateViewWriter]). It
 * runs as a sink subscription of the owner's node, so it checkpoints, retries and parks as every subscription does,
 * and a record written again changes nothing.
 *
 * The cache holds what a read of the owner returns, as a miss loads it: each state is masked by [mask], the node's
 * mask of the type's states ([me.ahoo.wow.spi.read.StateMasks]), on a copy of the record's tree, which other
 * subscriptions of the record read too. A type whose schema masks nothing has none, and its states are written as they
 * are, with no copy.
 */
internal class StateViewSink<V : Any>(
    viewType: Class<V>,
    private val writer: StateViewWriter<V>,
    private val mask: ((JsonNode) -> JsonNode)?,
) : Sink {
    private val codec = StateViewCodec(viewType)

    override suspend fun write(records: List<StateEventRecord>) {
        records.forEach { record ->
            val key = StateKey(record.ref.tenantId, record.ref.id)
            if (record.deleted) {
                writer.delete(key, record.version)
            } else {
                val state = checkNotNull(record.state) { "The state of ${record.id} is left out of its record." }.state
                writer.write(key, codec.viewOf(mask?.invoke(state.deepCopy()) ?: state), record.version)
            }
        }
    }

    override fun toString(): String = "StateViewSink($writer)"
}

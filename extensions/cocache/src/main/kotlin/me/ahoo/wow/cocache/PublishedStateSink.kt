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

/**
 * The sink that writes one aggregate type's states into its cache: each commit, in version order per aggregate, its
 * state after the commit as the view [viewType], or its tombstone when the commit deleted it ([PublishedStateWriter]). It
 * runs as a sink subscription of the owner's node, so it checkpoints, retries and parks as every subscription does,
 * and a record written again changes nothing.
 */
internal class PublishedStateSink<V : Any>(viewType: Class<V>, private val writer: PublishedStateWriter<V>) : Sink {
    private val codec = PublishedStateCodec(viewType)

    override suspend fun write(records: List<StateEventRecord>) {
        records.forEach { record ->
            val key = StateKey(record.ref.tenantId, record.ref.id)
            if (record.deleted) {
                writer.delete(key, record.version)
            } else {
                val state = checkNotNull(record.state) { "The state of ${record.id} is left out of its record." }
                writer.write(key, codec.viewOf(state.state), record.version)
            }
        }
    }

    override fun toString(): String = "PublishedStateSink($writer)"
}

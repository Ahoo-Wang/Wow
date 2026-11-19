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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * A sink's writes as its subscription makes them, for a test of a sink against its store: each lane [write]s the run
 * of one aggregate's records, which goes with the runs waiting with it in one [Sink.write], at most [Sink.maxBatch]
 * records, paced by [Sink.minWriteInterval] ([SinkWrites]).
 */
class SinkBatches(sink: Sink) {
    private val writes = SinkWrites(sink, sink.maxBatch, sink.minWriteInterval)

    /** Writes [run], one aggregate's records in version order, with the runs waiting; throws the failure of its write. */
    suspend fun write(run: List<StateEventRecord>) {
        writes.write(run).failure?.let { throw it }
    }
}

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

package me.ahoo.wow.tck.sink

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import java.util.concurrent.CopyOnWriteArrayList

/** The [SinkSpec] on a reference sink that appends every record it is given, duplicates included. */
class AppendingSinkSpecTest : SinkSpec() {
    private val records = CopyOnWriteArrayList<StateEventRecord>()

    override fun createSink(): Sink = Sink { records.addAll(it) }

    override suspend fun written(ref: AggregateRef): List<Int> = records.filter { it.ref == ref }.map { it.version }
}

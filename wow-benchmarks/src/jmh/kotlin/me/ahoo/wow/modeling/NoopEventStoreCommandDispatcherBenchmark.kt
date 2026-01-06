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

package me.ahoo.wow.modeling

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class NoopEventStoreCommandDispatcherBenchmark : AbstractCommandDispatcherBenchmark() {

    override fun createEventStore(): EventStore {
        return NoopEventStore
    }

    @Setup
    override fun setup() {
        super.setup()
    }

    @TearDown
    override fun destroy() {
        super.destroy()
    }

    @Benchmark
    override fun send(blackHole: Blackhole) {
        super.send(blackHole)
    }

    @Benchmark
    override fun sendAndWaitForSent(blackHole: Blackhole) {
        super.sendAndWaitForSent(blackHole)

    }

    @Benchmark
    override fun sendAndWaitForProcessed(blackHole: Blackhole) {
        super.sendAndWaitForProcessed(blackHole)

    }
}
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

package me.ahoo.wow.benchmark.infrastructure.mongo

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.MongoEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class MongoEventStoreLoadBenchmark {
    @Param(
        "noop-load-empty",
        "in-memory-load-empty",
        "mongo-load-empty",
    )
    lateinit var scenario: String

    private lateinit var aggregateId: AggregateId
    private lateinit var eventStore: EventStore
    private var fixture: MongoBenchmarkFixture? = null

    @Setup(Level.Iteration)
    fun setup() {
        fixture = null
        aggregateId = BenchmarkAggregates.cartMetadata.aggregateId("benchmark-missing-aggregate")
        eventStore = when (scenario) {
            "noop-load-empty" -> NoopEventStore
            "in-memory-load-empty" -> InMemoryEventStore()
            "mongo-load-empty" -> createMongoEventStore()
            else -> error("Unsupported Mongo event store load scenario: $scenario")
        }
    }

    private fun createMongoEventStore(): EventStore {
        val mongoFixture = MongoBenchmarkFixture()
        fixture = mongoFixture
        return MongoEventStore(mongoFixture.database)
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        fixture?.close()
        fixture = null
    }

    @Benchmark
    fun loadEmpty(blackhole: Blackhole) {
        val result = eventStore
            .load(aggregateId, headVersion = 1, tailVersion = Int.MAX_VALUE)
            .then()
            .block()
        blackhole.consume(result)
    }
}

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

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.toDocument
import org.bson.Document
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.kotlin.core.publisher.toMono

@State(Scope.Benchmark)
open class MongoAppendInsertBreakdownBenchmark {
    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var documentCollection: MongoCollection<Document>

    @Setup(Level.Iteration)
    fun setup() {
        fixture = MongoBenchmarkFixture()
        val collectionName = BenchmarkAggregates.namedAggregate.toEventStreamCollectionName()
        documentCollection = fixture.database.getCollection(collectionName)
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        fixture.close()
    }

    @Benchmark
    fun eventStreamToDocumentInsert(blackhole: Blackhole) {
        val eventStream = BenchmarkEvents.singleEventStream()
        val result = documentCollection
            .insertOne(eventStream.toDocument())
            .toMono()
            .block()
        checkNotNull(result)
        check(result.wasAcknowledged())
        blackhole.consume(result)
    }
}

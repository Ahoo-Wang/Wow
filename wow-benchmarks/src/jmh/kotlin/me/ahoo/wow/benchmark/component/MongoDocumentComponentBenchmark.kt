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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infrastructure.mongo.RawBsonEventStreamRecords
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.toDocument
import me.ahoo.wow.mongo.toDomainEventStream
import me.ahoo.wow.serialization.toJsonString
import org.bson.Document
import org.bson.codecs.DocumentCodec
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class MongoDocumentComponentBenchmark {
    private lateinit var eventStream: DomainEventStream
    private lateinit var eventDocument: Document
    private val documentCodec = DocumentCodec()

    @Setup
    fun setup() {
        eventStream = BenchmarkEvents.singleEventStream()
        eventDocument = eventStream.toDocument()
        verifyRawBsonCompatibility()
    }

    private fun verifyRawBsonCompatibility() {
        val rawDocument = RawBsonEventStreamRecords
            .toRawBsonDocument(eventStream)
            .decode(documentCodec)
        check(rawDocument[Documents.ID_FIELD] == eventDocument[Documents.ID_FIELD]) {
            "Raw BSON _id must match Document _id."
        }
        check(rawDocument[Documents.SIZE_FIELD] == eventDocument[Documents.SIZE_FIELD]) {
            "Raw BSON size must match Document size."
        }
        val documentStreamJson = Document(eventDocument).toDomainEventStream().toJsonString()
        val rawStreamJson = rawDocument.toDomainEventStream().toJsonString()
        check(rawStreamJson == documentStreamJson) {
            "Raw BSON decoded stream must match Document decoded stream."
        }
    }

    @Benchmark
    fun eventStreamToDocument(blackhole: Blackhole) {
        blackhole.consume(eventStream.toDocument())
    }

    @Benchmark
    fun eventStreamToRawBsonDocument(blackhole: Blackhole) {
        blackhole.consume(RawBsonEventStreamRecords.toRawBsonDocument(eventStream))
    }
}

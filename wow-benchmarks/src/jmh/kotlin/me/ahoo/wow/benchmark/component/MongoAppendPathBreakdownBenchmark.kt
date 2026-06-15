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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infrastructure.mongo.RawBsonEventStreamRecords
import me.ahoo.wow.mongo.toDocument
import me.ahoo.wow.mongo.toDomainEventStream
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toLinkedHashMap
import org.bson.BsonBinaryWriter
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.codecs.EncoderContext
import org.bson.io.BasicOutputBuffer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import tools.jackson.databind.JsonNode

@State(Scope.Thread)
open class MongoAppendPathBreakdownBenchmark {
    private lateinit var eventStream: DomainEventStream
    private lateinit var domainEvent: DomainEvent<*>
    private lateinit var eventDocument: Document
    private val documentCodec = DocumentCodec()
    private val encoderContext = EncoderContext.builder().build()

    @Setup
    fun setup() {
        eventStream = BenchmarkEvents.singleEventStream()
        domainEvent = eventStream.body.single()
        eventDocument = eventStream.toDocument()
        verifyEncodedCompatibility()
    }

    private fun verifyEncodedCompatibility() {
        val documentEncoded = RawBsonDocument(encodeDocument(eventDocument))
            .decode(documentCodec)
            .toDomainEventStream()
            .toJsonString()
        val rawEncoded = RawBsonEventStreamRecords.toRawBsonDocument(eventStream)
            .decode(documentCodec)
            .toDomainEventStream()
            .toJsonString()
        check(documentEncoded == rawEncoded) {
            "Document codec bytes and Raw BSON bytes must decode to the same DomainEventStream."
        }
    }

    @Benchmark
    fun domainEventBodyToJsonNode(blackhole: Blackhole) {
        blackhole.consume(domainEvent.body.toJsonNode<JsonNode>())
    }

    @Benchmark
    fun eventStreamToLinkedHashMap(blackhole: Blackhole) {
        blackhole.consume(eventStream.toLinkedHashMap())
    }

    @Benchmark
    fun eventStreamToDocument(blackhole: Blackhole) {
        blackhole.consume(eventStream.toDocument())
    }

    @Benchmark
    fun documentToBsonBytes(blackhole: Blackhole) {
        blackhole.consume(encodeDocument(eventDocument))
    }

    @Benchmark
    fun eventStreamToDocumentToBsonBytes(blackhole: Blackhole) {
        blackhole.consume(encodeDocument(eventStream.toDocument()))
    }

    @Benchmark
    fun eventStreamToRawBsonDocument(blackhole: Blackhole) {
        blackhole.consume(RawBsonEventStreamRecords.toRawBsonDocument(eventStream))
    }

    private fun encodeDocument(document: Document): ByteArray {
        val output = BasicOutputBuffer()
        val writer = BsonBinaryWriter(output)
        try {
            documentCodec.encode(writer, document, encoderContext)
        } finally {
            writer.close()
        }
        return output.toByteArray()
    }
}

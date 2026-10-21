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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Roadmap 8c.1's rollback after V10 has written (decision D32): the 9.x example imported into a V10 log on MongoDB,
 * V10 then writes, and `export` writes those streams back to the 9.x MongoDB store as 9.x writes them, which the
 * import reads back as the same V10 streams; a run again writes nothing, and a V10-only type refuses it.
 */
class NineXExportTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example().also { database ->
        // 9.x's schema initializer creates it when 9.x starts.
        listOf("order", "cart").forEach {
            database.getCollection("${it}_event_stream")
                .createIndex(Indexes.ascending("aggregateId", "version"), IndexOptions().unique(true))
        }
    }
    private val database = "wow_v10_export_${nineX.name}"
    private val log = MongoEventLog(NineXMongoStore.client, database, NineXExample.CONTEXT, PARTITIONS)
    private val converter = StreamConverter(NineXExample.plan)

    private fun config(): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
                  "target": { "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$database" },
                  "partitions": { "${NineXExample.CONTEXT}": $PARTITIONS },
                  "pageSize": 2
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    private val printed: String get() = output.toString()

    @Test
    fun `what V10 wrote after the cut-over goes back to the 9x store, read back as the same streams`(): Unit = runBlocking {
        val config = config().toString()
        run("import", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        val last = converter.convert(NineXExample.stream("order-stream-v4.json"))
        val paid = last.copyWith(
            version = 5,
            commandId = "v10-pay",
            requestId = "v10-pay",
            commandType = "example.order.PayOrder",
            commitTime = last.commitTime + 1_000,
            events = listOf(EventRecord("example.order.OrderPaid", 1, """{"amount":12.50,"paid":true}""")),
        )
        val received = paid.copyWith(
            version = 6,
            commandId = "v10-receive",
            requestId = "v10-receive",
            commandType = "example.order.ReceiptOrder",
            events = listOf(EventRecord("example.order.OrderReceived", 1, "{}")),
        )
        listOf(paid, received).forEach { log.append(it) }

        run("export", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("example done: 2 streams exported to 9.x, 8 already there")
            .contains("EQUAL example-service.order <- example.order: V10 6 streams, 9.x 6 streams")

        // Read back by the import's own reader, the streams convert to what V10 wrote.
        val order = NineXExample.stream("order-stream-v1.json")
        val back = MongoNineXEventSource(nineX).streams(order.aggregate, order.aggregateId, 5, 10)
        back.map { it.version }.assert().containsExactly(5, 6)
        back.map(converter::convert).zip(listOf(paid, received)).forEach { (read, written) -> read.same(written) }
        // As 9.x writes them: the size, the bodyType 9.x recorded for the event, the decimal exact.
        val document = nineX.getCollection("order_event_stream").find(Document("version", 5)).first()!!
        document.getInteger("size").assert().isEqualTo(1)
        val event = document.getList("body", Document::class.java).single()
        event.getString("bodyType").assert().isEqualTo("me.ahoo.wow.example.api.order.OrderPaid")
        (event.get("body", Document::class.java)["amount"] as Decimal128).bigDecimalValue()
            .assert().isEqualTo(BigDecimal("12.50"))

        // Idempotent: from the first entry again, every stream is already there.
        run("export", "--config", config, "--restart").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("example done: 0 streams exported to 9.x, 10 already there")

        // A V10-only type refuses the export, and nothing is written.
        log.append(
            received.copyWith(
                version = 7,
                commandId = "v10-v2",
                requestId = "v10-v2",
                events = listOf(EventRecord("example.order.OrderCreated", 2, "{}")),
            ),
        )
        run("export", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("REFUSED the V10 event example.order.OrderCreated@2 of example.order")
        MongoNineXEventTarget(nineX).count(order.aggregate).assert().isEqualTo(6)
    }

    @Test
    fun `the 9x target reports the streams it already holds and leaves them`() {
        val bare = NineXMongoStore.example()
        val stream = NineXExample.stream("order-stream-v2.json")
        assertThrows<MigrationException> { MongoNineXEventTarget(bare).insert(stream.aggregate, listOf(stream)) }
            .message.assert().contains("createIndex({ aggregateId: 1, version: 1 }, { unique: true })")
        val target = MongoNineXEventTarget(nineX)
        val order = NineXExample.stream("order-stream-v2.json")
        val key = NineXStreamKey(order.aggregateId.id, order.version)
        target.insert(order.aggregate, listOf(order.copy(id = "another"))).assert().containsExactly(key)
        target.stored(order.aggregate, listOf(key, NineXStreamKey("none", 1)))
            .assert().isEqualTo(mapOf(key to NineXStoredStream(order.aggregateId.tenantId, order.commandId)))
        target.stored(order.aggregate, emptyList()).assert().isEmpty()
        target.insert(order.aggregate, emptyList()).assert().isEmpty()
        target.revisionsOf(order.aggregate, "order_created").assert().containsExactly(NineXStream.DEFAULT_REVISION)
        target.count(order.aggregate).assert().isEqualTo(4)
        MongoNineXEventTarget.bsonOf(
            WowJson.mapper.readTree("""{"a":[1,2.5,null,true,"x",12345678901,123456789012345678901234]}""")
        )
            .assert().isEqualTo(
                Document(
                    "a",
                    listOf(
                        1,
                        Decimal128(BigDecimal("2.5")),
                        null,
                        true,
                        "x",
                        12345678901L,
                        Decimal128(BigDecimal("123456789012345678901234"))
                    ),
                ),
            )
    }

    private fun EventStreamRecord.same(other: EventStreamRecord) {
        copyWith(events = other.events).assert().isEqualTo(other)
        events.zip(other.events).forEach { (read, written) ->
            read.name.assert().isEqualTo(written.name)
            NineXExample.sameJson(
                WowJson.mapper.readTree(read.payload),
                WowJson.mapper.readTree(written.payload)
            ).assert().isTrue()
        }
    }

    private companion object {
        const val PARTITIONS = 4
    }
}

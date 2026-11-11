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

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * The full import's walk of a 9.x MongoDB store ([MongoNineXEventSource.instances], [MongoNineXEventSource.aggregateIds]):
 * one scan per page in 9.x's `{aggregateId: 1, version: 1}` index, no sort in the store, in the order the walk always
 * had, so the checkpoints an earlier version wrote resume where they stopped.
 */
class NineXWalkTest {
    @TempDir
    lateinit var directory: Path

    private val order = NineXAggregate("example-service", "order")
    private val database = NineXMongoStore.example()
    private val source = MongoNineXEventSource(database)
    private val example = NineXExample.stream("order-stream-v1.json").aggregateId
    private val copies = AtomicInteger()

    /** Inserts the 9.x example's order as the instance [id] of [tenant], its first [versions] streams. */
    private fun insertOrder(id: String, tenant: String, versions: Int = 4, change: (String) -> String = { it }) {
        val token = "c${copies.incrementAndGet()}-"
        NineXExample.ORDER_STREAMS.take(versions).forEach { name ->
            val text = NineXExample.resource(name)
                .replace(example.id, "\u0000")
                .replace("\"0VY3G", "\"${token}0VY3G")
                .replace("\"\u0000\"", WowJson.mapper.writeValueAsString(id))
                .replace("\"tenantId\":\"(0)\"", "\"tenantId\":${WowJson.mapper.writeValueAsString(tenant)}")
            NineXMongoStore.insert(database, change(text))
        }
    }

    /** Every page of [read], each read after the last instance of the one before. */
    private fun <T> pages(read: (NineXAggregateId?) -> List<T>, idOf: (T) -> NineXAggregateId): List<List<T>> {
        val pages = mutableListOf<List<T>>()
        var after: NineXAggregateId? = null
        while (true) {
            val page = read(after).ifEmpty { return pages }
            pages += page
            after = idOf(page.last())
        }
    }

    @Test
    fun `a page reads 9x's index with no sort in the store, and costs its own instances, however many follow`() {
        for (n in 1..300) {
            insertOrder("o-%03d".format(n), "(0)")
        }
        database.runCommand(Document("profile", 2))
        val first = try {
            source.instances(order, NineXAggregateId("o-100", "(0)"), 5, 1000).also {
                it.map { instance -> instance.id.id }.assert()
                    .containsExactly("o-101", "o-102", "o-103", "o-104", "o-105")
                it.all { instance -> instance.complete && instance.streams.size == 4 }.assert().isTrue()
                source.aggregateIds(order, NineXAggregateId("o-200", "(0)"), 5).map { id -> id.id }.assert()
                    .containsExactly("o-201", "o-202", "o-203", "o-204", "o-205")
            }
        } finally {
            database.runCommand(Document("profile", 0))
        }
        first.size.assert().isEqualTo(5)
        val scans = database.getCollection("system.profile")
            .find(Filters.and(Filters.eq("ns", "${database.name}.order_event_stream"), Filters.eq("op", "query")))
            .toList()
        scans.size.assert().isEqualTo(2)
        scans.forEach { scan ->
            scan.getString("planSummary").assert().isEqualTo("IXSCAN { aggregateId: 1, version: 1 }")
            scan.getBoolean("hasSortStage", false).assert().isFalse()
            // The 9.x sort the walk replaced examined every remaining stream: 800 after o-100. A scan reads its first
            // batch of 101, at most, and examines as many.
            (scan.get("docsExamined") as Number).toLong().assert().isLessThanOrEqualTo(101)
        }
    }

    @Test
    fun `the walk lists instances in the order of the 9x sort it replaced, page by page, ids colliding across tenants`() {
        // Ids that collide across tenants, which only a store without 9.x's unique index holds, inserted out of order;
        // the last two tenants are ordered one way by their UTF-8 bytes, as MongoDB orders strings, the other way by
        // their UTF-16 units.
        NineXMongoStore.collidable(database, order.name)
        listOf("b" to "t2", "a" to "t1", "b" to "\uE000", "b" to "\uD83D\uDE00", "c" to "t1", "b" to "t1", "a" to "t0")
            .forEach { (id, tenant) -> insertOrder(id, tenant, versions = 2) }
        val sorted = database.getCollection("order_event_stream")
            .find(Filters.and(Filters.eq("contextName", order.context), Filters.eq("version", 1)))
            .sort(Sorts.ascending("aggregateId", "tenantId"))
            .map { NineXAggregateId(it.getString("aggregateId"), it.getString("tenantId")) }
            .toList()
        sorted.size.assert().isEqualTo(8)
        sorted.indexOf(NineXAggregateId("b", "\uE000")).assert()
            .isLessThan(sorted.indexOf(NineXAggregateId("b", "\uD83D\uDE00")))
        for (limit in 1..3) {
            pages({ source.aggregateIds(order, it, limit) }, { it }).flatten().assert().isEqualTo(sorted)
            val instances = pages({ source.instances(order, it, limit, 1000) }, { it.id }).flatten()
            instances.map { it.id }.assert().isEqualTo(sorted)
            instances.filter { it.id != example }.forEach { instance ->
                instance.complete.assert().isTrue()
                instance.streams.map { it.version }.assert().containsExactly(1, 2)
                instance.streams.all { it.aggregateId == instance.id }.assert().isTrue()
            }
        }
    }

    @Test
    fun `the ranges MongoDB's sampled ids split the walk into hold every instance once, in the walk's order`() {
        // Ids whose UTF-8 bytes order them one way, as MongoDB orders strings, and their UTF-16 units the other.
        val ids = (100..159).map { "o-$it" } + listOf("\uE000", "\uD83D\uDE00", "z-1")
        ids.forEach { insertOrder(it, "(0)", versions = 2) }
        val whole = pages({ source.instances(order, it, 7, 1000) }, { it.id }).flatten().map { it.id }
        listOf(1, 2, 4, 8).forEach { parallelism ->
            val boundaries = source.idBoundaries(order, parallelism)
            boundaries.size.assert().isLessThanOrEqualTo(parallelism - 1)
            if (parallelism > 1) boundaries.assert().isNotEmpty()
            val ranges = IdRange.split(boundaries)
            val walked = ranges.flatMap { range ->
                pages({ source.instances(order, range, it, 7, 1000) }, { it.id }).flatten().map { it.id }
            }
            walked.assert().isEqualTo(whole)
        }
        source.idBoundaries(NineXAggregate("example-service", "absent"), 4).assert().isEmpty()
    }

    @Test
    fun `a page ends at the instance that reaches its streams, and one that does not parse is left to the import`() {
        insertOrder("p-1", "(0)")
        insertOrder("p-2", "(0)")
        insertOrder("p-3", "(0)") {
            if ("\"version\":2" in it) it.replace(Regex(""""commandId":"[^"]*","""), "") else it
        }
        source.instances(order, example, 10, 6).also { page ->
            page.map { it.id.id to it.complete }.assert().containsExactly("p-1" to true, "p-2" to false)
            page.last().streams.map { it.version }.assert().containsExactly(1, 2)
        }
        source.instances(order, NineXAggregateId("p-2", "(0)"), 10, 100).single().also { instance ->
            instance.id.id.assert().isEqualTo("p-3")
            instance.complete.assert().isFalse()
            instance.streams.map { it.version }.assert().containsExactly(1)
        }
        assertThrows<MigrationException> { source.streams(order, NineXAggregateId("p-3", "(0)"), 2, 10) }
        source.instances(order, NineXAggregateId("p-3", "(0)"), 10, 100).assert().isEmpty()
    }

    @Test
    fun `the walk refuses a collection without 9x's index, which the check names`() {
        database.getCollection("order_event_stream").dropIndex(Indexes.ascending("aggregateId", "version"))
        val absent = NineXAggregate("example-service", "absent")
        val create = "db.getCollection(\"order_event_stream\").createIndex({ aggregateId: 1, version: 1 })"
        source.missingWalkIndexes(listOf(order, NineXAggregate("example-service", "cart"), absent)).assert()
            .isEqualTo(mapOf("order_event_stream" to create))
        assertThrows<MigrationException> { source.instances(order, null, 10, 100) }.message.assert().isEqualTo(
            "The 9.x collection order_event_stream has no (aggregateId, version) index, which 9.x creates and the " +
                "import walks it in: create it, $create.",
        )
        assertThrows<MigrationException> { source.aggregateIds(order, null, 10) }
        source.instances(absent, null, 10, 100).assert().isEmpty()
        val report = MappingCheck(NineXExample.plan).check(
            source.inventory(),
            walkIndexes = source.missingWalkIndexes(NineXExample.plan.aggregates.keys),
        )
        report.passed.assert().isFalse()
        report.render().assert().contains(
            "the 9.x collection order_event_stream has no (aggregateId, version) index, which 9.x creates and the " +
                "import walks it in: create it on the 9.x side, $create",
        )
    }

    @Test
    fun `an import resumes mid-run from a checkpoint an earlier version wrote, in a file or in the target`() = runBlocking {
        NineXMongoStore.collidable(database, order.name)
        listOf("a" to "t1", "b" to "t1", "b" to "t2", "c" to "t1").forEach { (id, tenant) -> insertOrder(id, tenant) }
        // As the walk before this one wrote it, a page into the ids that collide: b of t1 imported, b of t2 not.
        val written = """
            {"format":1,"aggregates":{"example-service.order":{"startedAt":1,"after":{"id":"b","tenantId":"t1"},"completed":false}}}
        """.trimIndent()
        val file = directory.resolve("checkpoints.json").also { Files.writeString(it, written) }
        val table = MongoCheckpointTable(NineXMongoStore.client, "${database.name}_target").also { it.createSchema() }
        table.write("order-service", 0, WowJson.mapper.readValue(written, ImportCheckpoints::class.java), emptyMap())
        listOf(
            FileCheckpointRepository(file),
            TargetCheckpointRepository(table, "order-service", CheckpointLock.None),
        ).forEach { checkpoints ->
            val log = InMemoryEventLog(4)
            val done = EventStreamImporter(
                source,
                NineXExample.plan,
                { log },
                checkpoints,
                ImportOptions(pageSize = 1, concurrency = 2),
            ).importAll(order)
            done.instances.assert().isEqualTo(2)
            done.imported.assert().isEqualTo(8)
            listOf("a" to "t1", "b" to "t1").forEach { (id, tenant) ->
                log.read(NineXExample.orderModel.type.ref(tenant, id)).toList().assert().isEmpty()
            }
            listOf("b" to "t2", "c" to "t1").forEach { (id, tenant) ->
                log.read(NineXExample.orderModel.type.ref(tenant, id)).toList().map { it.version }.assert()
                    .containsExactly(1, 2, 3, 4)
            }
            checkpoints.load().of(order)!!.also {
                it.completed.assert().isTrue()
                // One range from the earlier version's position, which now holds it.
                it.after.assert().isNull()
                it.ranges!!.single().after.assert().isEqualTo(NineXAggregateId("c", "t1"))
            }
        }
    }
}

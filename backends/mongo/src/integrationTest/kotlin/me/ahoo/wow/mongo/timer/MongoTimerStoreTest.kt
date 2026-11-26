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

package me.ahoo.wow.mongo.timer

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.tck.timer.TimerStoreSpec
import org.bson.Document
import org.junit.jupiter.api.Test

class MongoTimerStoreTest : TimerStoreSpec() {
    /** A context of each test's own: the due read takes every aggregate type of the context. */
    override val context: String = MongoLogFixture.newContext("timer")

    override fun createStore(): TimerStore = store(context)

    @Test
    fun `creating the schema twice is harmless`() {
        store("timer-twice")
        store("timer-twice")
    }

    @Test
    fun `a collection recorded in another format is refused`(): Unit = runBlocking {
        val ref = AggregateType(context, "order").ref("t", "o-1")
        store(context).put(timer(ref))
        MongoLogFixture.bumpFormat(store(context).collectionName("order"))
        assertThrownBy<IllegalStateException> { runBlocking { store(context).find(ref, "k") } }
    }

    @Test
    fun `each type's collection is named after its context and type`() {
        store("Shop.Orders").collectionName("Line.Item").assert().isEqualTo("wow_timer__shop_orders__line_item")
    }

    @Test
    fun `an owner that looks like a field path is stored as it is, and one of another context is refused`(): Unit =
        runBlocking {
            val ref = AggregateType(context, "\$order").ref("\$t.x", "\$id-${System.nanoTime()}")
            val timer = timer(ref)
            store(context).put(timer).assert().isTrue()
            store(context).find(ref, "k")?.owner.assert().isEqualTo(ref)
            val elsewhere = AggregateType("elsewhere", "order").ref("t", "o-1")
            assertThrownBy<IllegalArgumentException> { runBlocking { store(context).put(timer(elsewhere)) } }
        }

    @Test
    fun `a timer outside its owner's partition is refused`() {
        val ref = AggregateType(context, "order").ref("t", "o-1")
        val timer = timer(ref)
        assertThrownBy<IllegalArgumentException> {
            runBlocking { store(context).put(timer.copyWith(partition = (timer.partition + 1) % logPartitions)) }
        }
    }

    @Test
    fun `the due read takes each type's collection over its partial index`(): Unit = runBlocking {
        // Decision D308: no ABAB for the read across types; its plan shows each branch over its collection's due index.
        val types = listOf("order", "invoice", "shipment")
        val store = store(context)
        types.forEach { store.put(timer(AggregateType(context, it).ref("t", "$it-1"))) }
        val explained = store.duePipelines(setOf(0, 1, 2, 3), Long.MAX_VALUE, 100)
            .map { store.registryCollection.aggregate(it).explain() }
        // The explain echoes the command's stages beside their plans: each collection's are taken together.
        val branches = documentsWith(explained, "\$unionWith")
            .groupBy { it.get("\$unionWith", Document::class.java).getString("coll") }
        branches.keys.assert().containsExactlyInAnyOrderElementsOf(types.map(store::collectionName))
        branches.values.forEach { stages ->
            val plans = stages.map { it.toJson() }
            plans.any { "\"indexName\": \"timer_due\"" in it }.assert().isTrue()
            plans.none { "COLLSCAN" in it }.assert().isTrue()
        }
    }

    @Test
    fun `a node that owns every partition of the default 64 reads the due timers of 16 aggregate types`(): Unit =
        runBlocking {
            // 64 partitions x 16 collections: 1,024 unions, past MongoDB's 1,000 stages in one aggregation (#6743).
            val store = store(context, CHUNKED_PARTITIONS)
            val refs = (1..TYPES).map { AggregateType(context, "type$it").ref("t", "a-$it") }
            refs.forEach { store.put(timer(it, CHUNKED_PARTITIONS)).assert().isTrue() }

            val due = store.due((0 until CHUNKED_PARTITIONS).toSet(), Long.MAX_VALUE, 10)

            due.map { it.owner }.assert().containsExactlyInAnyOrderElementsOf(refs)
        }

    @Test
    fun `a node that owns 1024 partitions reads the due timers of one aggregate type`(): Unit = runBlocking {
        val partitions = 1024
        val store = store(context, partitions)
        val ref = AggregateType(context, "order").ref("t", "o-1")
        store.put(timer(ref, partitions)).assert().isTrue()

        val due = store.due((0 until partitions).toSet(), Long.MAX_VALUE, 10)

        due.map { it.owner }.assert().containsExactly(ref)
    }

    @Test
    fun `a due read in several aggregations keeps one aggregation's order and each partition's limit`(): Unit =
        runBlocking {
            val store = store(context, CHUNKED_PARTITIONS)
            val all = (0 until CHUNKED_PARTITIONS).toSet()
            // Partition A (45): types 9, 16 and 2 first, at 10, 20 and 30, the rest at 50 + n. Partition B (5): every
            // timer at 15, so its first three go by id, by code point: b-84, b-85 and b-86 (types 16, 15 and 14).
            for (n in 1..TYPES) {
                val type = AggregateType(context, "type$n")
                val dueAt = when (n) {
                    9 -> 10L
                    16 -> 20L
                    2 -> 30L
                    else -> 50L + n
                }
                store.put(timer(refIn(type, PARTITION_A), CHUNKED_PARTITIONS, dueAt = dueAt, id = "a-$n"))
                store.put(timer(refIn(type, PARTITION_B), CHUNKED_PARTITIONS, dueAt = 15, id = "b-${100 - n}"))
            }
            // The collections in registry order (type1, type10 to type16, type2 to type9) times 64 partitions, 500
            // unions an aggregation: types 1 and 10 to 16 of both partitions in the first, types 2 to 8 in the second,
            // type 9 of partition 45 in the third. Each of the first two reads three of partition 45 that a cut drops.
            store.duePipelines(all, Long.MAX_VALUE, LIMIT).size.assert().isEqualTo(3)

            val due = store.due(all, Long.MAX_VALUE, LIMIT)

            due.map { it.id }.assert().containsExactly("a-9", "b-84", "b-85", "b-86", "a-16", "a-2")
        }

    @Test
    fun `timers whose rule a later version wrote should be skipped by the due read, never starving the readable`(): Unit =
        runBlocking {
            val store = store(context)
            val type = AggregateType(context, "order")
            // More unreadable timers than the page holds, all due before more readable ones than it holds, all of one
            // partition: the read goes on past the unreadable ones, and stops at a page of the readable.
            val partition = Partitioner.partitionOf(type.ref("t", "o-0"), logPartitions)
            val same = generateSequence(0) { it + 1 }.map { type.ref("t", "o-$it") }
                .filter { Partitioner.partitionOf(it, logPartitions) == partition }.take(2 * (PAGE + 1)).toList()
            val ahead = same.take(PAGE + 1)
            val behind = same.drop(PAGE + 1)
            ahead.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = at + 1L)).assert().isTrue() }
            behind.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = 100L + at)).assert().isTrue() }
            val later = Updates.set("rule", Document.parse(LATER_RULE))
            timers(store, "order").updateMany(Filters.lt("due_at", 100L), later).modifiedCount.assert()
                .isEqualTo(ahead.size.toLong())

            val partitions = (0 until logPartitions).toSet()
            store.due(partitions, until = 1_000, perPartition = PAGE).map { it.owner }.assert()
                .containsExactlyElementsOf(behind.take(PAGE))
            MongoTimerStore.unknown.seen.assert().contains("rule.misfire=later")
            // One timer read by its key is refused, naming the value.
            assertThrownBy<IllegalStateException> { runBlocking { store.find(ahead.first(), "k") } }
                .message().contains("later")
        }

    @Test
    fun `a read past skipped timers should stop where the due timers end`(): Unit = runBlocking {
        val store = store(context)
        val type = AggregateType(context, "order")
        // A page filled by unreadable timers, then fewer readable ones behind them than the page holds.
        val partition = Partitioner.partitionOf(type.ref("t", "o-0"), logPartitions)
        val same = generateSequence(0) { it + 1 }.map { type.ref("t", "o-$it") }
            .filter { Partitioner.partitionOf(it, logPartitions) == partition }.take(PAGE + 2).toList()
        same.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = at + 1L)).assert().isTrue() }
        val later = Updates.set("rule", Document.parse(LATER_RULE))
        timers(store, "order").updateMany(Filters.lte("due_at", PAGE + 1L), later).modifiedCount.assert()
            .isEqualTo(PAGE + 1L)

        store.due((0 until logPartitions).toSet(), until = 1_000, perPartition = PAGE).map { it.owner }.assert()
            .containsExactly(same.last())
    }

    /**
     * The read every poll runs: each collection's first due timers of each partition, nothing more. A timer whose rule
     * this version cannot read is the store's to skip; the read on past such timers runs only when one filled a page.
     */
    @Test
    fun `the due read should stay the plain read of each partition's first due timers`(): Unit = runBlocking {
        val store = store(context)
        store.put(timer(AggregateType(context, "order").ref("t", "o-1"))).assert().isTrue()
        val branches = store.duePipelines(setOf(0, 1), Long.MAX_VALUE, PAGE).flatMap { pipeline ->
            documentsWith(pipeline.map { Document.parse(it.toBsonDocument().toJson()) }, "\$unionWith")
        }
        branches.assert().hasSize(2)
        branches.forEach { union ->
            val stages = union.get("\$unionWith", Document::class.java).getList("pipeline", Document::class.java)
            stages.map { it.keys.single() }.assert().containsExactly("\$match", "\$sort", "\$limit", "\$set")
            stages.first().toJson().assert().doesNotContain("rule")
        }
    }

    @Test
    fun `a parked timer whose rule a later version wrote should be skipped by the parked read`(): Unit = runBlocking {
        val store = store(context)
        val type = AggregateType(context, "invoice")
        val target = type.ref("t", "target")
        val readable = type.ref("t", "readable")
        val later = type.ref("t", "later")
        listOf(readable, later).forEach { store.put(timer(it, target = target)).assert().isTrue() }
        val stored = timers(store, "invoice")
        stored.updateMany(Filters.empty(), Updates.set("status", "parked"))
        // Many, not one: a sharded collection takes a single update only by its shard key.
        stored.updateMany(Filters.eq("aggregate.id", later.id), Updates.set("rule", Document.parse(LATER_TYPE)))
            .modifiedCount.assert().isEqualTo(1)

        store.parked(target).map { it.owner }.assert().containsExactly(readable)
        MongoTimerStore.unknown.seen.assert().contains("rule.type=later")
    }

    /** The timer collection of [aggregate] that [store] writes. */
    private fun timers(store: MongoTimerStore, aggregate: String) =
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(store.collectionName(aggregate))

    /** An aggregate of [type] in [partition] of [CHUNKED_PARTITIONS]. */
    private fun refIn(type: AggregateType, partition: Int): AggregateRef = generateSequence(0) { it + 1 }
        .map { type.ref("t", "p$partition-$it") }
        .first { Partitioner.partitionOf(it, CHUNKED_PARTITIONS) == partition }

    /** Every document in [value], however deep, that has [key]. */
    private fun documentsWith(value: Any?, key: String): List<Document> = when (value) {
        is Document -> listOfNotNull(value.takeIf { key in it }) + value.values.flatMap { documentsWith(it, key) }
        is List<*> -> value.flatMap { documentsWith(it, key) }
        else -> emptyList()
    }

    private fun timer(
        ref: AggregateRef,
        partitions: Int = logPartitions,
        dueAt: Long = 5,
        id: String = "$ref#k@1",
        target: AggregateRef = ref,
    ) = Timer(
        id = id,
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, partitions),
        rule = ScheduleRule.At(dueAt),
        dueAt = dueAt,
        target = target,
        commandType = "c.order.Cancel",
        body = "{}",
    )

    private fun store(context: String, partitions: Int = logPartitions) =
        MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, partitions)
            .also { it.createSchema() }

    private companion object {
        /** The default partition count: with [TYPES] collections, more unions than one aggregation holds. */
        const val CHUNKED_PARTITIONS = 64
        const val TYPES = 16
        const val LIMIT = 3
        const val PARTITION_A = 45
        const val PARTITION_B = 5

        /** The due read's page of a partition, which the unreadable timers outnumber. */
        const val PAGE = 2

        /** A cron rule of a later version: a misfire this one does not know. */
        const val LATER_RULE = """{"type":"cron","expression":"0 0 * * * *","timeZone":"UTC","misfire":"later"}"""

        /** A rule of a type a later version adds. */
        const val LATER_TYPE = """{"type":"later","at":5}"""
    }
}

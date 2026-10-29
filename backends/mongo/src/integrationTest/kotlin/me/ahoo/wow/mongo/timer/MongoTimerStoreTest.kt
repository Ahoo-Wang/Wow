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
import me.ahoo.wow.mongo.MongoFormat
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
    fun `a collection of format 4, one per context, is refused`(): Unit = runBlocking {
        val ref = AggregateType(context, "order").ref("t", "o-1")
        store(context).put(timer(ref))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(MongoFormat.COLLECTION)
            .updateOne(Filters.eq("_id", store(context).collectionName("order")), Updates.set("version", 4))
            .matchedCount.assert().isEqualTo(1L)
        assertThrownBy<IllegalStateException> { runBlocking { store(context).find(ref, "k") } }
    }

    @Test
    fun `a timer written before the skipped count reads as having skipped none`(): Unit = runBlocking {
        val ref = AggregateType(context, "order").ref("t", "skipped-${System.nanoTime()}")
        val timer = timer(ref).copyWith(rule = ScheduleRule.Cron("0 0 0 1 * *"), slot = 4, skipped = 3)
        val store = store(context)
        store.put(timer).assert().isTrue()
        store.find(timer.owner, "k")?.skipped.assert().isEqualTo(3L)
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(store.collectionName("order"))
            // By the owner's partition too: a single update of a sharded collection names its shard key.
            .updateOne(
                Filters.and(
                    Filters.eq("partition", Partitioner.partitionOf(ref, logPartitions)),
                    Filters.eq("owner.id", ref.id),
                ),
                Updates.unset("skipped"),
            )
            .matchedCount.assert().isEqualTo(1L)
        store.find(timer.owner, "k")?.skipped.assert().isEqualTo(0L)
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
        val explained = store.registryCollection.aggregate(store.duePipeline(setOf(0, 1, 2, 3), Long.MAX_VALUE, 100))
            .explain()
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

    /** Every document in [value], however deep, that has [key]. */
    private fun documentsWith(value: Any?, key: String): List<Document> = when (value) {
        is Document -> listOfNotNull(value.takeIf { key in it }) + value.values.flatMap { documentsWith(it, key) }
        is List<*> -> value.flatMap { documentsWith(it, key) }
        else -> emptyList()
    }

    private fun timer(ref: AggregateRef) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, logPartitions),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "c.order.Cancel",
        body = "{}",
    )

    private fun store(context: String) =
        MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, logPartitions)
            .also { it.createSchema() }
}

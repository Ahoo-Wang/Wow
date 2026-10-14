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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.TimerStoreSpec
import org.junit.jupiter.api.Test

class MongoTimerStoreTest : TimerStoreSpec() {
    override fun createStore(): TimerStore = store

    @Test
    fun `creating the schema twice is harmless`() {
        store("timer-twice").createSchema()
        store("timer-twice").createSchema()
    }

    @Test
    fun `a collection recorded in another format is refused`() {
        val context = MongoLogFixture.newContext("timer")
        val created = store(context).also { it.createSchema() }
        MongoLogFixture.bumpFormat(created.collectionName)
        assertThrownBy<IllegalStateException> { store(context).createSchema() }
    }

    @Test
    fun `a collection of format 2, without the slot's due time, is refused`() {
        val context = MongoLogFixture.newContext("timer")
        val created = store(context).also { it.createSchema() }
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(MongoFormat.COLLECTION)
            .updateOne(Filters.eq("_id", created.collectionName), Updates.set("version", 2))
            .matchedCount.assert().isEqualTo(1L)
        assertThrownBy<IllegalStateException> { store(context).createSchema() }
    }

    @Test
    fun `a timer written before the skipped count reads as having skipped none`(): Unit = runBlocking {
        val ref = AggregateType("shop", "order").ref("t", "skipped-${System.nanoTime()}")
        val timer = Timer(
            id = "$ref#k@1",
            owner = ref.toString(),
            key = "k",
            version = 1,
            partition = 0,
            rule = ScheduleRule.Cron("0 0 0 1 * *"),
            dueAt = 5,
            target = ref,
            commandType = "shop.order.Invoice",
            body = "{}",
            slot = 4,
            skipped = 3,
        )
        store.put(timer).assert().isTrue()
        store.find(timer.owner, "k")?.skipped.assert().isEqualTo(3L)
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(store.collectionName)
            .updateOne(Filters.eq("owner", timer.owner), Updates.unset("skipped"))
            .matchedCount.assert().isEqualTo(1L)
        store.find(timer.owner, "k")?.skipped.assert().isEqualTo(0L)
    }

    @Test
    fun `the collection is named after its context`() {
        store("Shop.Orders").collectionName.assert().isEqualTo("wow_timer__shop_orders")
    }

    private companion object {
        fun store(context: String) = MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context)

        /** One store for every test: they keep to timers of their own. */
        val store: MongoTimerStore by lazy { store("timer-tck").also { it.createSchema() } }
    }
}

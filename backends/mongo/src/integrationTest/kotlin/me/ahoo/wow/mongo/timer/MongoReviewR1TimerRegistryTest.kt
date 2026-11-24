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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (MongoDB backend), from PostgreSQL's #6751: under `ddl: validate` or `none` the timer store writes no
 * registry record for a type's collection (`MongoTimerStore.ensure`), and the due read reads only the collections the
 * registry lists: it drops one it knows that the registry's count leaves out. A timer collection that exists without
 * its record is then never read for due timers, by the node that wrote them included.
 */
class MongoReviewR1TimerRegistryTest {
    @Test
    fun `the timers of a collection without its registry record are due`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("timer-registry")
        val order = AggregateType(context, "order")
        val first = order.ref("t", "o-1")
        // The schema as `ddl: auto` makes it, then restored without the registry's record of `order`.
        val auto = MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, PARTITIONS)
            .also { it.createSchema() }
        auto.put(timer(first)).assert().isTrue()
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection("wow_timer_aggregate")
            .deleteOne(Filters.eq("_id", auto.collectionName(order.name)))
        val validating =
            MongoTimerStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, PARTITIONS, ddl = Ddl.VALIDATE)
                .also { it.createSchema() }
        val second = order.ref("t", "o-2")
        validating.put(timer(second)).assert().isTrue()

        val due = validating.due((0 until PARTITIONS).toSet(), Long.MAX_VALUE, 10)

        due.map { it.owner }.assert().containsExactlyInAnyOrder(first, second)
    }

    private fun timer(ref: AggregateRef) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, PARTITIONS),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "c.order.Cancel",
        body = "{}",
    )

    private companion object {
        const val PARTITIONS = 4
    }
}

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
package me.ahoo.wow.mongo.ledger

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [MongoFailureLedger.due] reads by the key index whatever the planner would choose, on a collection that still holds
 * the index on the partition and the next attempt an earlier build created (`ledger_due`). At a tenth of the entries
 * due, a fresh plan of a page in the middle of a round reads that index and sorts every due entry of the partitions
 * again, page after page; by the key, a round reads each of the subscription's entries once, and each page at most a
 * page of keys more, where the keyset's branches (one for each column of the key) start their ranges. The profiler, on a
 * database of the test's own, records what each page read.
 */
class MongoFailureLedgerDuePlanTest {
    @Test
    fun `a round reads by the key index, each entry once`() {
        assumeFalse(MongoLogFixture.sharded, "A mongos has no profiler.")
        val database = MongoLogFixture.client.getDatabase(DATABASE)
        try {
            val ledger = MongoFailureLedger(MongoLogFixture.client, DATABASE).also { it.createSchema() }
            val collection = database.getCollection(COLLECTION)
            // The index an earlier build created, which a database keeps.
            collection.createIndex(
                Indexes.ascending("service", "subscription_context", "subscription", "partition", "next_attempt_at"),
                IndexOptions().name("ledger_due"),
            )
            val first = ParkedAggregate(SUBSCRIPTION, TYPE.ref("tenant", "template"), 0, 1, "failed", 1)
            runBlocking { ledger.park(first) }
            val template = checkNotNull(collection.find().first()).apply { remove("_id") }
            collection.deleteMany(Document())
            collection.insertMany(
                (1..PARKED).map { index ->
                    val id = UUID.nameUUIDFromBytes("$index".toByteArray()).toString()
                    Document(template)
                        .append("tenant_id", "tenant-${index % TENANTS}")
                        .append("aggregate_id", id)
                        .append("partition", Math.floorMod(id.hashCode(), PARTITIONS))
                        .append("next_attempt_at", if (index % DUE_EVERY == 0) null else ParkedAggregate.NEVER)
                },
            )
            val middle = collection.find()
                .sort(Document("tenant_id", 1).append("aggregate_id", 1))
                .skip(PARKED / 2)
                .first()!!
                .let { TYPE.ref(it.getString("tenant_id"), it.getString("aggregate_id")) }
            database.runCommand(Document("planCacheClear", COLLECTION))

            val page = profiled(database) { runBlocking { ledger.due(SUBSCRIPTION, PARTS, NOW, middle) } }
            page.single().getString("planSummary").assert().startsWith(KEY_INDEX)

            val round = profiled(database) {
                runBlocking {
                    var after: AggregateRef? = null
                    do {
                        val due = ledger.due(SUBSCRIPTION, PARTS, NOW, after)
                        after = due.lastOrNull()?.ref
                    } while (due.size == FailureLedger.DEFAULT_PAGE)
                }
            }
            round.forEach { it.getString("planSummary").assert().startsWith(KEY_INDEX) }
            val keys = round.sumOf { it.getInteger("keysExamined") }
            keys.assert().isLessThanOrEqualTo(PARKED + round.size * FailureLedger.DEFAULT_PAGE)
        } finally {
            database.runCommand(Document("profile", 0))
            database.drop()
        }
    }

    /** The finds of the ledger's collection that [block] runs, as the profiler records them. */
    private fun profiled(database: MongoDatabase, block: () -> Unit): List<Document> {
        database.runCommand(Document("profile", 0))
        database.getCollection("system.profile").drop()
        database.runCommand(Document("profile", 2))
        block()
        database.runCommand(Document("profile", 0))
        return database.getCollection("system.profile")
            .find(Document("op", "query").append("ns", "$DATABASE.$COLLECTION"))
            .toList()
    }

    private companion object {
        const val COLLECTION = "wow_failure_ledger"
        val DATABASE = "wow_ledger_due_plan_${UUID.randomUUID().toString().take(8)}"
        val SUBSCRIPTION = SubscriptionId("shop", "projector")
        val TYPE = AggregateType("shop", "order")
        const val PARKED = 10_000
        const val TENANTS = 20
        const val PARTITIONS = 64

        /** One entry in ten is due: the share at which a fresh plan of a middle page reads the due index. */
        const val DUE_EVERY = 10
        val PARTS = (0 until PARTITIONS).toList()
        const val NOW = 1_770_000_000_000

        /** How the profiler summarizes a read by the key index. */
        const val KEY_INDEX = "IXSCAN { service: 1, subscription_context: 1, subscription: 1, context: 1, " +
            "aggregate_name: 1, tenant_id: 1, aggregate_id: 1 }"
    }
}

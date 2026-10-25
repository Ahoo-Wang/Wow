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

package me.ahoo.wow.mongo

import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The log and the snapshots on a sharded cluster (decision D277), when `wow.mongo.url` names a mongos
 * (`test/mongo-cluster/cluster.sh test sharded`); skipped otherwise.
 */
class MongoShardingTest {
    private val client = MongoLogFixture.client
    private val config = client.getDatabase("config")

    @BeforeEach
    fun onSharded() {
        assumeTrue(MongoLogFixture.sharded, "Runs on a sharded cluster: -Dwow.mongo.url of a mongos.")
    }

    @Test
    fun `a new log collection is sharded by partition, a chunk each, spread over the shards`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(PARTITIONS, context, aggregates = listOf("order"))
        val namespace = "${MongoLogFixture.DATABASE}.${MongoNames.of("wow_log", context, "order")}"
        keyOf(namespace).assert().isEqualTo(Document("partition", 1))
        val chunks = chunksOf(namespace)
        chunks.size.assert().isEqualTo(PARTITIONS)
        chunks.groupingBy { it.getString("shard") }.eachCount().values.assert().containsOnly(PARTITIONS / 2)

        // The log works across them: an append in each partition, each read back by its aggregate and its tail.
        val type = AggregateType(context, "order")
        val ids = (1..PARTITIONS * 4).map { "o-$it" }
            .distinctBy { Partitioner.partitionOf(type.ref("tenant", it), PARTITIONS) }
        ids.size.assert().isEqualTo(PARTITIONS)
        ids.forEach { id ->
            log.append(stream(type, id)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.read(type.ref("tenant", id)).toList().single().ref.id.assert().isEqualTo(id)
            log.tail(Partitioner.partitionOf(type.ref("tenant", id), PARTITIONS), null).first().stream.ref.id
                .assert().isEqualTo(id)
        }
    }

    @Test
    fun `a read by aggregate and a tail target one shard`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(PARTITIONS, context, aggregates = listOf("order"))
        val log = client.getDatabase(MongoLogFixture.DATABASE).getCollection(MongoNames.of("wow_log", context, "order"))
        val byAggregate = Document("partition", 3).append("aggregate_id", "a").append("tenant_id", "t")
            .append("version", Document("\$gte", 1))
        val tail = Filters.and(
            Filters.eq("partition", 3),
            Filters.gt("_id", MongoEventLog.positionId(3, 0)),
            Filters.lt("_id", MongoEventLog.positionId(4, 0)),
        )
        listOf(byAggregate, tail).forEach { filter ->
            shardsOf(log.find(filter).explain()).assert().hasSize(1)
        }
    }

    @Test
    fun `a new snapshot collection is sharded by tenant and the aggregate id's hash`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = MongoSnapshotStore(client, MongoLogFixture.DATABASE, context).also { it.createCollection("order") }
        val namespace = "${MongoLogFixture.DATABASE}.${MongoSnapshotStore.collectionName(context, "order")}"
        keyOf(namespace).assert().isEqualTo(Document("tenant_id", 1).append("aggregate_id", "hashed"))
        val ref = AggregateType(context, "order").ref("tenant", "a")
        store.save(snapshot(ref, 1)).assert().isTrue()
        store.save(snapshot(ref, 1)).assert().isFalse()
        store.load(ref)?.version.assert().isEqualTo(1)
    }

    @Test
    fun `with sharding off a store leaves its collections unsharded`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoEventLog(client, MongoLogFixture.DATABASE, context, PARTITIONS, sharding = MongoSharding.OFF)
            .also { it.createSchema() }.createCollection("order")
        MongoSnapshotStore(client, MongoLogFixture.DATABASE, context, sharding = MongoSharding.OFF)
            .createCollection("order")
        keyOf("${MongoLogFixture.DATABASE}.${MongoNames.of("wow_log", context, "order")}").assert().isNull()
        keyOf("${MongoLogFixture.DATABASE}.${MongoSnapshotStore.collectionName(context, "order")}").assert().isNull()
    }

    private fun keyOf(namespace: String): Document? =
        config.getCollection("collections").find(Filters.eq("_id", namespace)).first()?.get("key", Document::class.java)

    private fun chunksOf(namespace: String): List<Document> {
        val uuid = config.getCollection("collections").find(Filters.eq("_id", namespace)).first()!!["uuid"]
        return config.getCollection("chunks").find(Filters.eq("uuid", uuid)).toList()
    }

    /** The shards a sharded `find`'s plan reads. */
    private fun shardsOf(explain: Document): List<String> =
        explain.get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java)
            .getList("shards", Document::class.java).map { it.getString("shardName") }

    private fun stream(type: AggregateType, id: String) = EventStreamRecord(
        ref = type.ref("tenant", id),
        version = 1,
        commandId = "command-$id",
        requestId = "request-$id",
        commandType = "${type.context}.order.Create",
        commitTime = 1L,
        events = listOf(EventRecord("${type.context}.order.Created", 1, "{}")),
        scope = Scope.NONE,
    )

    private fun snapshot(ref: AggregateRef, version: Int) = SnapshotRecord(
        ref = ref,
        version = version,
        schemaFingerprint = "fingerprint",
        state = "{}",
        firstEventTime = 1L,
        lastEventTime = 1L,
    )

    private companion object {
        const val PARTITIONS = 8
    }
}

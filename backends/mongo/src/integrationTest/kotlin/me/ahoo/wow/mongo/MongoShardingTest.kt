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
import com.mongodb.ConnectionString
import com.mongodb.client.MongoClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The log, the snapshots, the reservations and the timers on a sharded cluster (decisions D277, D307, D308), when
 * `wow.mongo.url` names a mongos (`test/mongo-cluster/cluster.sh test sharded`); skipped otherwise.
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
    fun `a type's timer collection is sharded by partition, its reads by owner on one shard, its due read on all`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val store = MongoTimerStore(client, MongoLogFixture.DATABASE, context, PARTITIONS).also { it.createSchema() }
            val type = AggregateType(context, "order")
            // An owner in each partition, so the timers are on both shards.
            val owners = (1..PARTITIONS * 8).map { type.ref("tenant", "o-$it") }
                .distinctBy { Partitioner.partitionOf(it, PARTITIONS) }
            owners.size.assert().isEqualTo(PARTITIONS)
            owners.forEach { store.put(timer(it)).assert().isTrue() }
            val namespace = "${MongoLogFixture.DATABASE}.${store.collectionName("order")}"
            keyOf(namespace).assert().isEqualTo(Document("partition", 1))
            chunksOf(namespace).groupingBy { it.getString("shard") }.eachCount().values.assert()
                .containsOnly(PARTITIONS / 2)
            // Decision D308: the due read of every partition takes the timers of both shards, in one aggregation.
            store.due((0 until PARTITIONS).toSet(), Long.MAX_VALUE, 100).map { it.owner }.assert()
                .containsExactlyInAnyOrderElementsOf(owners)
            owners.forEach { store.find(it, "k")?.owner.assert().isEqualTo(it) }
            // A read by owner names the owner's partition: it targets one shard.
            val timers = client.getDatabase(MongoLogFixture.DATABASE).getCollection(store.collectionName("order"))
            val owner = owners.first()
            val byOwner = Filters.and(
                Filters.eq("partition", Partitioner.partitionOf(owner, PARTITIONS)),
                Filters.eq("owner.tenant_id", owner.tenantId),
                Filters.eq("owner.id", owner.id),
                Filters.eq("key", "k"),
            )
            shardsOf(timers.find(byOwner).explain()).assert().hasSize(1)
        }

    @Test
    fun `a new reservation collection is sharded by tenant and the key's hash, a key read on one shard`(): Unit =
        runBlocking {
            val type = AggregateType(MongoLogFixture.newContext(), "user")
            val store = MongoReservationStore(client, MongoLogFixture.DATABASE)
            val scope = ReservationScope.of(type, "tenant", "name")
            store.reserve(scope, "ahoo", type.ref("tenant", "u-1"), "h", TTL).assert().isNotNull()
            val name = MongoNames.of("wow_reservation", type.context, type.name)
            keyOf("${MongoLogFixture.DATABASE}.$name").assert()
                .isEqualTo(Document("tenant_id", 1).append("key", "hashed"))
            val collection = client.getDatabase(MongoLogFixture.DATABASE).getCollection(name)
            val byKey = Filters.and(
                Filters.eq("_id", Document("t", "tenant").append("n", "name").append("k", "ahoo")),
                Filters.eq("tenant_id", "tenant"),
                Filters.eq("key", "ahoo"),
            )
            shardsOf(collection.find(byKey).explain()).assert().hasSize(1)
        }

    @Test
    fun `concurrent reserves of the same keys through two routers leave each key one owner`(): Unit = runBlocking {
        val hosts = ConnectionString(WowContainers.mongoUrl).hosts
        assumeTrue(hosts.size > 1, "Needs two mongos routers in wow.mongo.url.")
        val routers = hosts.take(2).map { MongoClients.create("mongodb://$it/") }
        try {
            val type = AggregateType(MongoLogFixture.newContext(), "user")
            val stores = routers.map { MongoReservationStore(it, MongoLogFixture.DATABASE) }
            val scope = ReservationScope.of(type, "tenant", "name")
            val keys = (1..KEYS).map { "key-$it" }
            val won = keys.flatMap { key ->
                (1..OWNERS).map { owner ->
                    async(Dispatchers.IO) {
                        val ref = type.ref("tenant", "u-$owner")
                        val held = stores[owner % stores.size].reserve(scope, key, ref, "h-$owner", TTL)
                        if (held?.owner == ref) key to ref else null
                    }
                }
            }.awaitAll().filterNotNull()
            won.groupBy({ it.first }, { it.second }).forEach { (key, owners) ->
                owners.assert().hasSize(1)
                stores.forEach { it.find(scope, key)?.owner.assert().isEqualTo(owners.single()) }
            }
            won.map { it.first }.toSet().assert().isEqualTo(keys.toSet())
        } finally {
            routers.forEach { it.close() }
        }
    }

    @Test
    fun `with sharding off a store leaves its collections unsharded`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoEventLog(client, MongoLogFixture.DATABASE, context, PARTITIONS, sharding = MongoSharding.NONE)
            .also { it.createSchema() }.createCollection("order")
        MongoSnapshotStore(client, MongoLogFixture.DATABASE, context, sharding = MongoSharding.NONE)
            .createCollection("order")
        keyOf("${MongoLogFixture.DATABASE}.${MongoNames.of("wow_log", context, "order")}").assert().isNull()
        keyOf("${MongoLogFixture.DATABASE}.${MongoSnapshotStore.collectionName(context, "order")}").assert().isNull()
        val timers = MongoTimerStore(client, MongoLogFixture.DATABASE, context, PARTITIONS, sharding = MongoSharding.NONE)
        timers.put(timer(AggregateType(context, "order").ref("tenant", "o-1"))).assert().isTrue()
        keyOf("${MongoLogFixture.DATABASE}.${timers.collectionName("order")}").assert().isNull()
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

    private fun timer(owner: AggregateRef) = Timer(
        id = "$owner#k@1",
        owner = owner,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(owner, PARTITIONS),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = owner,
        commandType = "${owner.type.context}.order.Cancel",
        body = "{}",
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
        const val KEYS = 32
        const val OWNERS = 4
        val TTL = 1.minutes
    }
}

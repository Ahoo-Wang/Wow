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

package me.ahoo.wow.spring.boot

import com.mongodb.client.MongoClient
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import javax.sql.DataSource

/**
 * The exit of roadmap 6.0g (decision D177): one context whose vaults' log and snapshots are on PostgreSQL and whose
 * ledgers' are on MongoDB, served by two hosts. Each store holds its own aggregates' log, partition sequences,
 * snapshots, timers and checkpoints; the owner of a partition runs both groups' subscriptions and timers in it, and a
 * host that stops hands both groups over to the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MixedStoresTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val mongo = WowContainers.newMongo().also { it.start() }
    private val hosts = listOf("node-a", "node-b").map { node ->
        startHost(
            "wow.node.id" to node,
            "wow.log.partitions" to PARTITIONS,
            "wow.stores.pg.type" to "postgres",
            "wow.stores.mongo.type" to "mongo",
            "wow.stores.mongo.database" to DATABASE,
            "wow.log.store" to "pg",
            "wow.aggregates.[treasury.ledger].log.store" to "mongo",
            "wow.aggregates.[treasury.ledger].snapshot.stores" to "mongo",
            "wow.timers.poll-interval" to "100ms",
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "spring.datasource.hikari.maximum-pool-size" to 8,
            "test.mongo.url" to mongo.replicaSetUrl,
            sources = listOf(TreasuryApp::class.java, MongoClientConfiguration::class.java),
        )
    }
    private val first = hosts[0]
    private val second = hosts[1]

    @AfterAll
    fun close() {
        hosts.forEach { it.close() }
        postgres.stop()
        mongo.stop()
    }

    private fun Host.state(type: String, id: String) = get("/treasury/$type/$id/state")

    private fun Host.sealed(type: String, id: String): Boolean =
        state(type, id).takeIf { it.status == 200 }?.body?.get("state")?.get("sealed")?.asBoolean() == true

    private fun pgCount(sql: String): Long = first.context.getBean(DataSource::class.java).connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun mongoCount(collection: String, filter: org.bson.conversions.Bson = Filters.empty()): Long =
        first.context.getBean(MongoClient::class.java).getDatabase(DATABASE).getCollection(collection)
            .countDocuments(filter)

    @Test
    @Order(1)
    fun `each aggregate's log, snapshots, timers and checkpoints are on its store`() {
        first.node.context(TREASURY).logGroups.map { it.store }.assert().containsExactly("pg", "mongo")
        first.node.options.appendQueue.enabled.assert().isTrue()
        first.post("/treasury/vault/v-1/open-vault", """{"sealAfterMillis":300}""").status.assert().isEqualTo(200)
        first.post("/treasury/ledger/l-1/open-ledger", """{"closeAfterMillis":300}""").status.assert().isEqualTo(200)
        eventually { first.sealed("vault", "v-1") && first.sealed("ledger", "l-1") }

        pgCount("SELECT count(*) FROM wow_log__treasury__vault").assert().isEqualTo(2)
        pgCount("SELECT count(*) FROM pg_tables WHERE tablename = 'wow_log__treasury__ledger'").assert().isZero()
        mongoCount("wow_log__treasury__ledger").assert().isEqualTo(2)
        mongoCount("wow_log__treasury__vault").assert().isZero()
        pgCount("SELECT count(*) FROM wow_snapshot__treasury__vault").assert().isEqualTo(1)
        mongoCount("wow_snapshot__treasury__ledger").assert().isEqualTo(1)
        pgCount("SELECT count(*) FROM wow_timer__treasury WHERE status = 'FIRED'").assert().isEqualTo(1)
        mongoCount("wow_timer__treasury", Filters.eq("status", "FIRED")).assert().isEqualTo(1)
        // Each group's partition sequence starts on its own: the first entry of each log is at 1.
        val ledgerPartition = Partitioner.partitionOf(LEDGER.ref(Caller.DEFAULT_TENANT, "l-1"), PARTITIONS)
        mongoCount("wow_log__treasury__ledger", Filters.and(Filters.eq("partition", ledgerPartition), Filters.eq("sequence", 1L)))
            .assert().isEqualTo(1)
        eventually { hosts.flatMap { it.ledgerbook() }.containsAll(listOf("vault:v-1@2", "ledger:l-1@2")) }
        eventually {
            pgCount(
                "SELECT count(*) FROM wow_checkpoint WHERE subscription_group = 'bank-service/treasury' " +
                    "AND subscription = '$LEDGERBOOK' AND position > 0",
            ) > 0 &&
                mongoCount(
                    "wow_checkpoint",
                    Filters.and(
                        Filters.eq("subscription_group", "bank-service/treasury#mongo"),
                        Filters.eq("subscription", LEDGERBOOK),
                        Filters.gt("position", 0L),
                    ),
                ) > 0
        }
    }

    @Test
    @Order(2)
    fun `the snapshots of each type are queried on its store`() {
        first.post("/treasury/vault/v-q/open-vault", """{"sealAfterMillis":600000}""").status.assert().isEqualTo(200)
        first.post("/treasury/ledger/l-q/open-ledger", """{"closeAfterMillis":600000}""").status.assert()
            .isEqualTo(200)
        listOf("vault" to "v-q", "ledger" to "l-q").forEach { (type, id) ->
            val listed = first.post(
                "/treasury/$type/snapshot/query",
                """{"kind":"list","filter":{"op":"ID","value":"$id"}}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["aggregateId"].asString() }.assert().containsExactly(id)
        }
    }

    @Test
    @Order(3)
    fun `a host that stops hands both groups' partitions, subscriptions and timers over`() {
        val vault = idOwnedBySecond(VAULT, "v-h")
        val ledger = idOwnedBySecond(LEDGER, "l-h")
        first.post("/treasury/vault/$vault/open-vault", """{"sealAfterMillis":3000}""").status.assert()
            .isEqualTo(200)
        first.post("/treasury/ledger/$ledger/open-ledger", """{"closeAfterMillis":3000}""").status.assert()
            .isEqualTo(200)
        first.sealed("vault", vault).assert().isFalse()
        // The node leaves and hands its partitions off; its host keeps the JVM's shared Reactor Netty resources up.
        runBlocking { second.node.stop() }
        eventually(OWNERSHIP_TIMEOUT) { first.node.context(TREASURY).ownership.owned.size == PARTITIONS }
        eventually(OWNERSHIP_TIMEOUT) { first.sealed("vault", vault) && first.sealed("ledger", ledger) }
        eventually { first.ledgerbook().containsAll(listOf("vault:$vault@2", "ledger:$ledger@2")) }
        first.node.context(TREASURY).logGroups.forEach { group ->
            group.subscriptions.partitions.assert().hasSize(PARTITIONS)
            group.timers!!.runner.partitions.assert().hasSize(PARTITIONS)
        }
    }

    private fun Host.ledgerbook(): List<String> = context.getBean(Ledgerbook::class.java).entries

    /** An id of [type] from [prefix] whose partition the second host owns and the first forwards to it. */
    private fun idOwnedBySecond(type: AggregateType, prefix: String): String {
        var found: String? = null
        eventually(OWNERSHIP_TIMEOUT) {
            val owned = second.node.context(TREASURY).ownership.owned
            val routes = first.node.context(TREASURY).ownership
            found = (0 until CANDIDATES).map { "$prefix-$it" }.firstOrNull { candidate ->
                val partition = Partitioner.partitionOf(type.ref(Caller.DEFAULT_TENANT, candidate), PARTITIONS)
                partition in owned && routes.forwardTarget(partition)?.node == "node-b"
            }
            found != null && first.node.context(TREASURY).ownership.owned.isNotEmpty()
        }
        return found!!
    }

    private fun eventually(timeout: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "The condition did not hold within $timeout ms." }
            Thread.sleep(POLL)
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val DATABASE = "mixed"
        const val CANDIDATES = 200
        const val TIMEOUT = 20_000L
        const val OWNERSHIP_TIMEOUT = 30_000L
        const val POLL = 100L
        val VAULT = AggregateType(TREASURY, "vault")
        val LEDGER = AggregateType(TREASURY, "ledger")
    }
}

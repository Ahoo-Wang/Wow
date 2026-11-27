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

import com.mongodb.MongoException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoEventLogOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger

/**
 * A MongoDB log store needs on its registry `wow_log_aggregate` only what Operate › MongoDB says (#7230): `find`,
 * and `insert` to write a record that is missing. The partition count `createSchema` records under every `ddl` and
 * the record of each collection are read first and written only when missing, as the timer store's records are
 * (#7127): MongoDB checks an account's privilege before the key, so an insert or an upsert of a document already there
 * by an account that may only read the registry is refused as `Unauthorized` (13), not answered as a duplicate. A
 * record the store must write and may not is refused, naming the document an operator inserts instead.
 *
 * A standalone `mongod` with authorization on, as `MongoTimerRegistryGrantTest` runs; each test has a database and an
 * account of its own. A root `ddl: auto` log makes the context's `order` collection, records it and appends to it; the
 * account may read the database, write every collection that log made but the registry, and take its test's actions
 * on the registry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoLogRegistryGrantTest {
    private val mongo = GenericContainer(DockerImageName.parse(WowContainers.MONGO_IMAGE))
        .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT)
        .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
        .withExposedPorts(PORT)
        .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2))
        .also { it.start() }
    private val address = "${mongo.host}:${mongo.getMappedPort(PORT)}"
    private val admin = MongoClients.create("mongodb://$ROOT:$PASSWORD@$address/?authSource=admin")
    private val databases = AtomicInteger()

    @AfterAll
    fun close() {
        admin.close()
        mongo.stop()
    }

    @Test
    fun `an account granted insert, update and find on the registry runs the log under validate`(): Unit =
        withAccount(listOf("insert", "update", "find")) { _, account ->
            account.append(ORDER, "o-2").assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    @Test
    fun `an account granted insert and find on the registry runs the log, and writes a record that is missing, under validate`(): Unit =
        withAccount(listOf("insert", "find")) { database, account ->
            account.append(ORDER, "o-2").assert().isInstanceOf(AppendOutcome.Committed::class.java)
            // A restore that lost the record of order's collection: the next use records it again.
            registry(database).deleteOne(Filters.eq("_id", collectionOf(ORDER)))
            account.append(ORDER, "o-3").assert().isInstanceOf(AppendOutcome.Committed::class.java)
            registry(database).find(Filters.eq("_id", collectionOf(ORDER))).first()!!.getString("aggregate")
                .assert().isEqualTo(ORDER)
        }

    @Test
    fun `an account that may only read the registry runs a log whose count and collections are all recorded`(): Unit =
        withAccount(listOf("find")) { _, account ->
            account.append(ORDER, "o-2").assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    @Test
    fun `an account that may update but not insert into the registry runs a log whose collections are all recorded`(): Unit =
        withAccount(listOf("find", "update")) { _, account ->
            account.append(ORDER, "o-2").assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    @Test
    fun `a missing record the account may not insert is refused by name, and the record inserted as it says serves the account`(): Unit =
        withAccount(listOf("find")) { database, account ->
            // A restore that lost the record of order's collection.
            registry(database).deleteOne(Filters.eq("_id", collectionOf(ORDER)))
            assertThrownBy<IllegalStateException> { account.append(ORDER, "o-2") }
                .hasMessageContaining("The registry wow_log_aggregate has no record of the log collection")
                .hasMessageContaining("Let the store's account insert into wow_log_aggregate, or insert")
                .hasMessageContaining(
                    "{_id: \"${collectionOf(ORDER)}\", context: \"$CONTEXT\", aggregate: \"$ORDER\"} into it."
                )

            registry(database).insertOne(
                Document("_id", collectionOf(ORDER)).append("context", CONTEXT).append("aggregate", ORDER),
            )
            account.append(ORDER, "o-3").assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    @Test
    fun `a missing partition count the account may not insert is refused by name, and the count inserted as it says starts the log`(): Unit =
        withAccount(listOf("find")) { database, account ->
            val marker = "${MongoNames.of("wow_log", CONTEXT, "partition")}#partitions"
            registry(database).deleteOne(Filters.eq("_id", marker))
            assertThrownBy<IllegalStateException> { account.append(ORDER, "o-2") }
                .hasMessageContaining("The registry wow_log_aggregate has no record of the partition count")
                .hasMessageContaining("or insert {_id: \"$marker\", partitions: $PARTITIONS} into it.")

            registry(database).insertOne(Document("_id", marker).append("partitions", PARTITIONS))
            account.append(ORDER, "o-3").assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    /** An account of a database of its own, taking [actions] on the registry: [block] gets the database's name. */
    private fun withAccount(actions: List<String>, block: (database: String, account: Account) -> Unit) {
        val name = "wow_log_grant_${databases.incrementAndGet()}"
        runBlocking { log(admin, name, Ddl.AUTO).append(stream(ORDER, "o-1")) }
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val database = admin.getDatabase(name)
        val written = database.listCollectionNames().toList().filter { it != MongoEventLog.REGISTRY }
        val privileges = written.map { collection ->
            privilege(name, collection, listOf("find", "insert", "update", "remove"))
        } + privilege(name, MongoEventLog.REGISTRY, actions)
        database.runCommand(
            Document("createRole", WRITER).append("privileges", privileges).append("roles", emptyList<Document>()),
        )
        database.runCommand(
            Document("createUser", ACCOUNT).append("pwd", PASSWORD).append(
                "roles",
                listOf(Document("role", "read").append("db", name), Document("role", WRITER).append("db", name)),
            ),
        )
        MongoClients.create("mongodb://$ACCOUNT:$PASSWORD@$address/?authSource=$name").use { client ->
            if ("insert" !in actions) {
                // The control: the account may not insert into the registry.
                assertThrows<MongoException> {
                    client.getDatabase(name).getCollection(MongoEventLog.REGISTRY).insertOne(Document("_id", "probe"))
                }.code.assert().isEqualTo(UNAUTHORIZED)
            }
            block(name, Account(client, name))
        }
    }

    /** The service's account: each append opens the log again under `ddl: validate`, as a node starting does. */
    private inner class Account(private val client: MongoClient, private val database: String) {
        fun append(aggregate: String, id: String): AppendOutcome =
            runBlocking { log(client, database, Ddl.VALIDATE).append(stream(aggregate, id)) }
    }

    private fun privilege(database: String, collection: String, actions: List<String>) =
        Document("resource", Document("db", database).append("collection", collection)).append("actions", actions)

    private fun registry(database: String) = admin.getDatabase(database).getCollection(MongoEventLog.REGISTRY)

    private fun collectionOf(aggregate: String) = MongoNames.of("wow_log", CONTEXT, aggregate)

    private fun log(client: MongoClient, database: String, ddl: Ddl) =
        MongoEventLog(client, database, CONTEXT, PARTITIONS, options = MongoEventLogOptions(ddl = ddl))
            .also { it.createSchema() }

    private fun stream(aggregate: String, id: String): EventStreamRecord {
        val ref: AggregateRef = AggregateType(CONTEXT, aggregate).ref("t", id)
        return EventStreamRecord(
            ref,
            1,
            "c-$id",
            "r-$id",
            "${ref.type}.Act",
            1,
            listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
            Scope.NONE,
        )
    }

    private companion object {
        const val PORT = 27017
        const val ROOT = "root"
        const val ACCOUNT = "logs"
        const val PASSWORD = "grant-test-only"
        const val WRITER = "log_writer"
        const val CONTEXT = "grant"
        const val ORDER = "order"
        const val PARTITIONS = 1
        const val UNAUTHORIZED = 13
    }
}

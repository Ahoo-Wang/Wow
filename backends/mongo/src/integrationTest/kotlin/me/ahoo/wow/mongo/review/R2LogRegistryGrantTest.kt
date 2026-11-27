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

package me.ahoo.wow.mongo.review

import com.mongodb.MongoException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoEventLogOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Round two, the upgrade path (r2-upgrade-notes.md finding 3): the account a MongoDB log store runs under, against what
 * the pages say it needs on the registry `wow_log_aggregate`. Operate › Upgrades: "Grant the store's account `INSERT`
 * on both registries (on MongoDB `insert` and `find`), or insert each record the refusal names". Operate › MongoDB: a
 * collection "recorded already (by `ddl: auto`, another node, or by hand) is only read". The log's refusal: "Let the
 * store's account insert into wow_log_aggregate, or insert {…} into it".
 *
 * Read in the code: every open runs `createSchema`, which upserts the partition marker into the registry under every
 * `ddl` (`MongoEventLog.upsertOnce`, an update with `upsert`, so MongoDB asks for `update` and `insert`), and the first
 * use of a collection inserts its record first (`MongoLogCollections.record`), which MongoDB refuses as `Unauthorized`
 * before it looks at the key. #7127 made the timer store read its record first; the log was left.
 *
 * A standalone `mongod` with authorization on, as `MongoTimerRegistryGrantTest` runs.
 */
class R2LogRegistryGrantTest {
    @Test
    fun `an account granted insert and find on the registry, as Operate Upgrades says, should run the log under validate`(): Unit =
        withAccount(registry = listOf("insert", "find")) { _, account, context ->
            val outcome = runCatching {
                runBlocking { log(account, context, Ddl.VALIDATE).append(stream(AggregateType(context, ORDER).ref("t", "o-2"))) }
            }
            outcome.exceptionOrNull()?.message.assert().isNull()
        }

    @Test
    fun `an account that may only read the registry should run a log whose collections are all recorded, under validate`(): Unit =
        withAccount(registry = listOf("find")) { admin, account, context ->
            // Every record is there, as `ddl: auto` wrote it, or as an operator inserts what the refusal names.
            admin.getDatabase(DATABASE).getCollection(MongoEventLog.REGISTRY)
                .countDocuments(Filters.eq("_id", MongoNames.of("wow_log", context, ORDER))).assert().isEqualTo(1L)
            val outcome = runCatching {
                runBlocking { log(account, context, Ddl.VALIDATE).append(stream(AggregateType(context, ORDER).ref("t", "o-2"))) }
            }
            outcome.exceptionOrNull()?.message.assert().isNull()
        }

    @Test
    fun `an account that may update but not insert into the registry should run a log whose collections are all recorded`(): Unit =
        withAccount(registry = listOf("find", "update")) { _, account, context ->
            val outcome = runCatching {
                runBlocking { log(account, context, Ddl.VALIDATE).append(stream(AggregateType(context, ORDER).ref("t", "o-2"))) }
            }
            outcome.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" }.assert().isNull()
        }

    @Test
    fun `control - an account granted insert, update and find on the registry runs the log under validate`(): Unit =
        withAccount(registry = listOf("insert", "update", "find")) { _, account, context ->
            runBlocking { log(account, context, Ddl.VALIDATE).append(stream(AggregateType(context, ORDER).ref("t", "o-2"))) }
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }

    /**
     * A standalone `mongod` with authorization: [block] gets the root client, a client of an account that may read the
     * database, write every collection the log made but the registry, and take [registry]'s actions on the registry,
     * and the context, whose `order` collection the root's `ddl: auto` log made, recorded and appended to.
     */
    private fun withAccount(registry: List<String>, block: (admin: MongoClient, account: MongoClient, context: String) -> Unit) =
        GenericContainer(DockerImageName.parse(WowContainers.MONGO_IMAGE))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2))
            .use { mongo ->
                mongo.start()
                val address = "${mongo.host}:${mongo.getMappedPort(PORT)}"
                val context = "r2"
                MongoClients.create("mongodb://$ROOT:$PASSWORD@$address/?authSource=admin").use { admin ->
                    val auto = log(admin, context, Ddl.AUTO)
                    runBlocking { auto.append(stream(AggregateType(context, ORDER).ref("t", "o-1"))) }
                        .assert().isInstanceOf(AppendOutcome.Committed::class.java)
                    val database = admin.getDatabase(DATABASE)
                    val written = database.listCollectionNames().toList().filter { it != MongoEventLog.REGISTRY }
                    val privileges = written.map { name ->
                        Document("resource", Document("db", DATABASE).append("collection", name))
                            .append("actions", listOf("insert", "update", "remove", "find"))
                    } + Document("resource", Document("db", DATABASE).append("collection", MongoEventLog.REGISTRY))
                        .append("actions", registry)
                    database.runCommand(
                        Document("createRole", WRITER).append("privileges", privileges).append("roles", emptyList<Document>()),
                    )
                    database.runCommand(
                        Document("createUser", ACCOUNT).append("pwd", PASSWORD).append(
                            "roles",
                            listOf(
                                Document("role", "read").append("db", DATABASE),
                                Document("role", WRITER).append("db", DATABASE),
                            ),
                        ),
                    )
                    MongoClients.create("mongodb://$ACCOUNT:$PASSWORD@$address/?authSource=$DATABASE").use { account ->
                        // The control: the account may write the log's own collections.
                        account.getDatabase(DATABASE).getCollection(written.first()).countDocuments()
                        if ("insert" !in registry) {
                            assertThrows<MongoException> {
                                account.getDatabase(DATABASE).getCollection(MongoEventLog.REGISTRY)
                                    .insertOne(Document("_id", "probe"))
                            }.code.assert().isEqualTo(UNAUTHORIZED)
                        }
                        block(admin, account, context)
                    }
                }
            }

    private fun log(client: MongoClient, context: String, ddl: Ddl) =
        MongoEventLog(client, DATABASE, context, 1, options = MongoEventLogOptions(ddl = ddl)).also { it.createSchema() }

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    private companion object {
        const val PORT = 27017
        const val ROOT = "root"
        const val ACCOUNT = "logs"
        const val PASSWORD = "r2-test-only"
        const val WRITER = "r2_log_writer"
        const val DATABASE = "r2_log_registry"
        const val ORDER = "order"
        const val UNAUTHORIZED = 13
    }
}

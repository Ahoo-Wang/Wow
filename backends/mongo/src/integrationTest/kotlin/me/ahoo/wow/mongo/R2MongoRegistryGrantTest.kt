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
import com.mongodb.client.MongoClients
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * #6792 records a timer collection in `wow_timer_aggregate` under every `ddl`, and refuses a first use whose record
 * "this store could not write", telling the operator to grant `insert` or to insert the record. #6764, its PostgreSQL
 * twin, reads the record first and writes it only when it is missing, so an account that may only read the registry
 * goes on once the record is there (`PostgresStoreDdlTest`: "... and passes once it is there"). MongoDB inserts first:
 * its server checks the privilege before the key, so an account without `insert` gets `Unauthorized` (13), not a
 * duplicate key, and the first use is refused although the record is there. Before #6792, `validate` read the
 * registry only, and this account's timers ran.
 *
 * A standalone `mongod` with authorization on: the shared replica-set container runs without it, and a role is
 * enforced only with it.
 */
class R2MongoRegistryGrantTest {
    @Test
    fun `a timer collection already recorded serves an account that may only read the registry, under validate`(): Unit =
        GenericContainer(DockerImageName.parse(WowContainers.MONGO_IMAGE))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2))
            .use { mongo ->
                mongo.start()
                val address = "${mongo.host}:${mongo.getMappedPort(PORT)}"
                val order = AggregateType(CONTEXT, ORDER)
                MongoClients.create("mongodb://$ROOT:$PASSWORD@$address/?authSource=admin").use { admin ->
                    // The schema's owner made the collection and its record (ddl auto).
                    val auto = MongoTimerStore(admin, DATABASE, CONTEXT, PARTITIONS).also { it.createSchema() }
                    runBlocking { auto.put(timer(order.ref("t", "o-1"))) }.assert().isTrue()
                    val database = admin.getDatabase(DATABASE)
                    database.runCommand(
                        Document("createRole", WRITER)
                            .append(
                                "privileges",
                                listOf(
                                    Document(
                                        "resource",
                                        Document("db", DATABASE).append("collection", auto.collectionName(ORDER)),
                                    ).append("actions", listOf("insert", "update", "remove")),
                                ),
                            )
                            .append("roles", emptyList<Document>()),
                    )
                    // The service's account: it reads the database, and writes its timers' collection only.
                    database.runCommand(
                        Document("createUser", ACCOUNT).append("pwd", PASSWORD).append(
                            "roles",
                            listOf(
                                Document("role", "read").append("db", DATABASE),
                                Document("role", WRITER).append("db", DATABASE),
                            ),
                        ),
                    )
                }
                MongoClients.create("mongodb://$ACCOUNT:$PASSWORD@$address/?authSource=$DATABASE").use { account ->
                    // The account may not insert into the registry: the control that the role holds.
                    assertThrows<MongoException> {
                        account.getDatabase(DATABASE).getCollection(REGISTRY).insertOne(Document("_id", "probe"))
                    }.code.assert().isEqualTo(UNAUTHORIZED)
                    account.getDatabase(DATABASE).getCollection(REGISTRY).countDocuments().assert().isEqualTo(1L)

                    val validating = MongoTimerStore(account, DATABASE, CONTEXT, PARTITIONS, ddl = Ddl.VALIDATE)
                        .also { it.createSchema() }
                    runBlocking { validating.put(timer(order.ref("t", "o-2"))) }.assert().isTrue()
                    runBlocking { validating.due((0 until PARTITIONS).toSet(), Long.MAX_VALUE, 10) }.map { it.owner }
                        .assert().containsExactlyInAnyOrder(order.ref("t", "o-1"), order.ref("t", "o-2"))
                }
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
        const val PORT = 27017
        const val ROOT = "root"
        const val ACCOUNT = "timers"
        const val PASSWORD = "r2-test-only"
        const val WRITER = "r2_timer_writer"
        const val DATABASE = "r2_registry"
        const val CONTEXT = "r2"
        const val ORDER = "order"
        const val REGISTRY = "wow_timer_aggregate"
        const val PARTITIONS = 4
        const val UNAUTHORIZED = 13
    }
}

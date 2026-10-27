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

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

/**
 * Every write of every store is acknowledged by a majority (finding F18), whatever the client's default: an
 * acknowledged reservation, timer, checkpoint, lease or ledger entry survives a failover of the primary.
 */
class MongoWriteConcernTest {
    @Test
    fun `every store writes with w majority, though the client's default is w 1`(): Unit = runBlocking {
        val writes = CopyOnWriteArrayList<BsonDocument>()
        val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                if (event.commandName in WRITES) writes += event.command.clone()
            }
        }
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
            .writeConcern(WriteConcern.W1)
            .addCommandListener(listener)
            .build()
        MongoClients.create(settings).use { client ->
            val database = MongoLogFixture.DATABASE
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val ref = type.ref("tenant", "o-1")
            val log = MongoEventLog(client, database, context, 1).also { it.createSchema() }
            log.append(
                EventStreamRecord(
                    ref = ref,
                    version = 1,
                    commandId = "c-1",
                    requestId = "r-1",
                    commandType = "$context.order.Create",
                    commitTime = 1L,
                    events = listOf(EventRecord("$context.order.Created", 1, "{}")),
                    scope = Scope.NONE,
                ),
            )
            MongoSnapshotStore(client, database, context).save(
                SnapshotRecord(ref, 1, "fingerprint", "{}", firstEventTime = 1L, lastEventTime = 1L),
            )
            MongoTimerStore(client, database, context, 1).also { it.createSchema() }.put(
                Timer(
                    id = "$ref#k@1",
                    owner = ref,
                    key = "k",
                    version = 1,
                    partition = Partitioner.partitionOf(ref, 1),
                    rule = ScheduleRule.At(5),
                    dueAt = 5,
                    target = ref,
                    commandType = "$context.order.Cancel",
                    body = "{}",
                ),
            )
            MongoReservationStore(client, database)
                .reserve(ReservationScope.of(type, "tenant", "name"), "ahoo", ref, "h", 1.minutes)
            MongoCheckpointStore(client, database).also { it.createSchema() }.save(context, "s", 0, 1)
            MongoLeaseStore(client, database).also { it.createSchema() }.run {
                acquire(context, 0, "node-1", 1.minutes)
                heartbeat(context, "node-1", "127.0.0.1:1", 1.minutes, setOf(context))
            }
            MongoFailureLedger(client, database).also { it.createSchema() }
                .park(ParkedAggregate("s", ref, 1, "failed", 1L))
        }
        val targets = writes.map { it.getFirstKey() to it.getString(it.getFirstKey()).value }.toSet()
        // The log, its counters and registry, the snapshots, timers, reservations, checkpoints, leases and members,
        // the ledger and the format records.
        targets.size.assert().isGreaterThanOrEqualTo(10)
        writes.forEach { command ->
            val target = command.getString(command.getFirstKey()).value
            command.getDocument("writeConcern", null)?.get("w")?.let { if (it.isString) it.asString().value else it }
                .assert().describedAs("the write concern of $target").isEqualTo("majority")
        }
    }

    private companion object {
        val WRITES = setOf("insert", "update", "delete", "findAndModify")
    }
}

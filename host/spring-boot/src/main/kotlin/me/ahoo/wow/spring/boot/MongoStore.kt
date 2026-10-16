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
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore

/**
 * A store on MongoDB (wow-mongo) over the application's `MongoClient` ([client]), in [database]: each context its own
 * log, snapshot and timer collections; the checkpoints, leases, failure ledger and reservations shared. A log on it is
 * appended through the partition append queue by default (decision D52). Loaded only when it is configured.
 */
internal class MongoStore(
    name: String,
    val client: MongoClient,
    val database: String,
    /** How the store's collections and indexes are managed (`wow.ddl`, `wow.stores.<name>.ddl`; review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : HostStore(name, BackendKind.MONGO) {
    override val location: Any get() = client to database

    override fun openCheckpoints(): CheckpointStore =
        MongoCheckpointStore(client, database, ddl = ddl).also { schema(it::createSchema) }

    override fun openLeases(): LeaseStore =
        MongoLeaseStore(client, database, ddl = ddl).also { schema(it::createSchema) }

    override fun openLedger(): FailureLedger =
        MongoFailureLedger(client, database, service = service, ddl = ddl).also { schema(it::createSchema) }

    override fun openReservations(): ReservationStore =
        MongoReservationStore(client, database, ddl = ddl).also { schema(it::createSchema) }

    override fun openLog(context: String, partitions: Int): EventLog =
        MongoEventLog(
            client,
            database,
            context,
            partitions,
            pollInterval = tailPolling.interval,
            maxPollInterval = tailPolling.maxInterval,
            ddl = ddl,
        ).also { schema(it::createSchema) }

    override fun openSnapshots(context: String): SnapshotStore =
        MongoSnapshotStore(client, database, context, ddl = ddl).also { schema(it::createSchema) }

    override fun openTimers(context: String): TimerStore =
        MongoTimerStore(client, database, context, ddl = ddl).also { schema(it::createSchema) }

    companion object {
        /** The store [name] over [client] in [database], typed loosely so that the caller does not load the driver. */
        fun open(
            name: String,
            client: Any,
            database: String,
            ddl: StoreDdl = StoreDdl.AUTO,
        ): HostStore = MongoStore(name, client as MongoClient, database, ddl)
    }
}

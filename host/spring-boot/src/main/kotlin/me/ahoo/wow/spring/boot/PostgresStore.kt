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

import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import javax.sql.DataSource

/**
 * A store on PostgreSQL (wow-postgres) over the application's [dataSource]: each context its own log, snapshot and
 * timer tables; the checkpoints, leases, failure ledger and reservations shared. Loaded only when it is configured, so
 * the host runs without wow-postgres on the classpath.
 */
internal class PostgresStore(
    name: String,
    val dataSource: DataSource,
    /** How the store's tables are managed (`wow.ddl`, `wow.stores.<name>.ddl`; review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : HostStore(name, BackendKind.POSTGRES) {
    override val location: Any get() = dataSource

    override fun openCheckpoints(): CheckpointStore =
        PostgresCheckpointStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLeases(): LeaseStore = PostgresLeaseStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLedger(): FailureLedger =
        PostgresFailureLedger(dataSource, service = service, ddl = ddl).also { schema(it::createSchema) }

    override fun openReservations(): ReservationStore =
        PostgresReservationStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLog(context: String, partitions: Int): EventLog =
        PostgresEventLog(
            dataSource,
            context,
            partitions,
            pollInterval = tailPolling.interval,
            maxPollInterval = tailPolling.maxInterval,
            ddl = ddl,
        ).also { schema(it::createSchema) }

    override fun openSnapshots(context: String): SnapshotStore =
        PostgresSnapshotStore(dataSource, context, ddl = ddl).also { schema(it::createSchema) }

    override fun openTimers(context: String): TimerStore =
        PostgresTimerStore(dataSource, context, ddl = ddl).also { schema(it::createSchema) }

    companion object {
        /** The store [name] over [dataSource], typed loosely so that the caller does not load the JDBC classes. */
        fun open(name: String, dataSource: Any, ddl: StoreDdl = StoreDdl.AUTO): HostStore =
            PostgresStore(name, dataSource as DataSource, ddl)
    }
}

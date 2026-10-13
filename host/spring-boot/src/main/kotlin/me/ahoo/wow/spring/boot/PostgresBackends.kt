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
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextBackends
import javax.sql.DataSource

/**
 * The PostgreSQL backend (wow-postgres) over the application's [DataSource]: each context its own log, snapshot and
 * timer tables; the checkpoints, leases, failure ledger and reservations shared. Loaded only when it is selected, so the
 * host runs without wow-postgres on the classpath.
 */
internal object PostgresBackends {
    fun open(dataSource: DataSource, partitions: Int): HostBackends {
        val checkpoints = PostgresCheckpointStore(dataSource)
        val leases = PostgresLeaseStore(dataSource)
        val ledger = PostgresFailureLedger(dataSource)
        val reservations = PostgresReservationStore(dataSource)
        val shared = listOf(
            checkpoints::createSchema,
            leases::createSchema,
            ledger::createSchema,
            reservations::createSchema,
        )
        return HostBackends(BackendKind.POSTGRES, AppendQueueOptions.POSTGRES, shared) { context, schemas ->
            val log = PostgresEventLog(dataSource, context, partitions)
            val snapshots = PostgresSnapshotStore(dataSource, context)
            val timers = PostgresTimerStore(dataSource, context)
            schemas += log::createSchema
            schemas += snapshots::createSchema
            schemas += timers::createSchema
            ContextBackends(log, checkpoints, leases, ledger, snapshots, reservations, timers)
        }
    }
}

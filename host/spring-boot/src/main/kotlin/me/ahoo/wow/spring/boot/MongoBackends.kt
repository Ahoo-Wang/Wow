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
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextBackends

/**
 * The MongoDB backend (wow-mongo) over the application's `MongoClient` ([client]), in [database]: each context its
 * own log and snapshot collections; the checkpoints, leases, failure ledger and reservations shared. Its appends go
 * through the partition append queue by default (decision D52). Loaded only when it is selected; [client] is typed
 * loosely so that the caller does not load the driver's classes.
 */
internal object MongoBackends {
    fun open(client: Any, database: String, partitions: Int): HostBackends {
        val mongo = client as MongoClient
        val checkpoints = MongoCheckpointStore(mongo, database)
        val leases = MongoLeaseStore(mongo, database)
        val ledger = MongoFailureLedger(mongo, database)
        val reservations = MongoReservationStore(mongo, database)
        val shared = listOf(
            checkpoints::createSchema,
            leases::createSchema,
            ledger::createSchema,
            reservations::createSchema,
        )
        return HostBackends(BackendKind.MONGO, AppendQueueOptions.MONGO, shared) { context, schemas ->
            val log = MongoEventLog(mongo, database, context, partitions)
            val snapshots = MongoSnapshotStore(mongo, database, context)
            schemas += log::createSchema
            schemas += snapshots::createSchema
            ContextBackends(log, checkpoints, leases, ledger, snapshots, reservations)
        }
    }
}

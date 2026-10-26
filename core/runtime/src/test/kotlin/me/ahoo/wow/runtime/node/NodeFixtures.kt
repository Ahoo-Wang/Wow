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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.accountManifest
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.testOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val NODE_SERVICE = "bank-service"

/** The account aggregate of [context]: a deposit creates it or adds to it, and is recorded in [decided]. */
fun accountModel(context: String, decided: MutableList<String> = CopyOnWriteArrayList()): AggregateModel<Account> =
    aggregate<Account>(context, "account") {
        initial { Account() }
        decide<Deposit>(Creation.ALLOWED) { _, body ->
            decided += command.id
            Decision.events(Deposited(body.amount))
        }
        evolve<Deposited> { balance += it.amount }
    }

/** The Manifest of [accountModel] in each of [contexts]. */
fun accountsManifest(vararg contexts: String, service: String? = NODE_SERVICE): Manifest = Manifest(
    service = accountManifest(service).service,
    contexts = contexts.map { accountManifest(service, it).contexts.single() },
)

/** In-memory stores of every context, opened once per context and shared by the nodes given this factory. */
class InMemoryBackends(private val partitions: Int = 8) : BackendFactory {
    val opened = ConcurrentHashMap<String, ContextBackends>()

    override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
        ContextBackends(
            log = InMemoryEventLog(partitions),
            checkpoints = InMemoryCheckpointStore(),
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            reservations = InMemoryReservationStore(),
        )
    }
}

/** Node options with the short cluster and subscription timings of the other runtime tests. */
fun fastNodeOptions(): ServiceNodeOptions = ServiceNodeOptions(
    cluster = fastOptions(),
    subscription = testOptions(),
)

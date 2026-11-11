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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Nodes of one service over a loopback channel whose replies from node-2 wait on a gate, built and stopped per test:
 * what the node assembly's tests share, each test with its own.
 */
abstract class ServiceNodeFixtures {
    /** While not null, the replies of node-2 wait for it. */
    @Volatile
    protected var replyGate: CompletableDeferred<Unit>? = null

    protected val channel = LoopbackClusterChannel(latency = { address ->
        if (address.startsWith("node-2")) {
            replyGate?.await()
        }
    })
    protected val nodes = ConcurrentHashMap<String, ServiceNode>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        replyGate?.complete(Unit)
        nodes.values.forEach { it.stop() }
    }

    protected fun node(
        name: String,
        vararg contexts: String = arrayOf(BANK),
        backends: BackendFactory = InMemoryBackends(),
        decided: MutableList<String> = CopyOnWriteArrayList(),
        model: (String) -> AggregateModel<*> = { accountModel(it, decided) },
        options: ServiceNodeOptions = fastNodeOptions(),
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
        listener: (ServiceNode.Step) -> Unit = {},
        address: String? = "$name.internal:9090",
        peers: List<ManifestFragment> = emptyList(),
        observer: Observer = Observer.NONE,
    ): ServiceNode = ServiceNode(
        NODE_SERVICE,
        name,
        address,
        contexts.map(model),
        accountsManifest(*contexts),
        backends,
        channel,
        remoteSignals = { origin, signal, _ -> nodes[origin.node]?.waits?.signal(signal) },
        options = options,
        subscriptions = subscriptions,
        listener = listener,
        contracts = ContractDirectory(accountsManifest(*contexts), peers),
        observer = observer,
    ).also {
        nodes[name] = it
        if (it.hasAddress) {
            channel.bind(it.address, it.forwardHandler)
        }
    }

    protected fun deposit(
        context: String = BANK,
        id: String? = null,
        amount: Int = 1,
        wait: WaitPlan? = WaitPlan.COMMITTED,
    ) = ServerCommandRequest(
        context = context,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":$amount}"""),
        caller = Caller(TENANT),
        aggregateId = id,
        wait = wait,
    )

    protected companion object {
        const val BANK = "bank"
        const val LEDGER = "ledger"
        const val AUDIT = "audit"
        const val TENANT = "t"
        const val PROJECTOR = "projector"
    }
}

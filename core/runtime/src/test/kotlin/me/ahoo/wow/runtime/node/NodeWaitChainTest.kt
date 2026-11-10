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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.ContractFixtures
import me.ahoo.wow.runtime.wait.HeldDeadlines
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.runtime.wait.WaitOptions
import org.junit.jupiter.api.Test

/** The hops a node's wait chains may name: those of a peer's context, through the node's contracts. */
class NodeWaitChainTest {
    /** The deadlines of the nodes' waits, passed once a wait reached its commit: the peer never answers. */
    private val held = HeldDeadlines()

    /** A wait for the commit, then a subscription of the peer context `inventory`. */
    private val peer = WaitPlan.of(Hop.committed(), Hop.handled("StockProjector.onStock", "inventory"))

    private fun node(
        name: String,
        options: ServiceNodeOptions = fastNodeOptions(),
        peers: List<ManifestFragment> = emptyList(),
    ) = ServiceNode(
        NODE_SERVICE,
        name,
        "$name.internal:9090",
        listOf(accountModel(BANK)),
        accountsManifest(BANK),
        InMemoryBackends(),
        LoopbackClusterChannel(),
        options = options,
        contracts = ContractDirectory(accountsManifest(BANK), peers),
        deadlines = held,
    )

    private fun deposit(id: String) = ServerCommandRequest(
        context = BANK,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":1}"""),
        caller = Caller("t"),
        aggregateId = id,
        wait = peer,
    )

    /** The answer of a deposit that waits for [peer]: its deadline passes once the commit is reached and emitted. */
    private suspend fun ServiceNode.timedOut(id: String): CommandResult =
        held.timedOut(gateway.send(deposit(id))) { it.hop == Hop.committed(BANK) }.last()

    @Test
    fun `a wait chain may name a subscription of a peer's context through the node's contracts`(): Unit = runBlocking {
        // A node that knows no Manifest of the context lets the hop through (decision D190): it times out unheard.
        val alone = node("node-1")
        alone.start()
        val unheard = alone.timedOut("a-0")
        unheard.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        unheard.hop.assert().isEqualTo(Hop.committed(BANK))
        alone.stop()
        // Unless it rejects the contexts it does not know.
        val strict =
            node("node-3", fastNodeOptions().copyWith(wait = WaitOptions(unknownContexts = UnknownContexts.REJECT)))
        strict.start()
        val refused = strict.gateway.execute(deposit("a-1"))
        refused.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        strict.stop()

        val node = node("node-2", peers = ContractFixtures.peers)
        node.contracts.hasContext("inventory").assert().isTrue()
        node.start()
        // Admitted and committed; no signal comes from the peer here, so the wait ends at COMMITTED.
        val result = node.timedOut("a-2")
        result.error!!.code.assert().isNotEqualTo(ResultCodes.WAIT_INVALID)
        result.hop.assert().isEqualTo(Hop.committed(BANK))
        node.stop()
    }

    private companion object {
        const val BANK = "bank"
    }
}

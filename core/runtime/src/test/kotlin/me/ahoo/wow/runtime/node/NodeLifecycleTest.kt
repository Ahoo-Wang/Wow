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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/** The lifecycle of a node, as its facade drives it: once each, in order, the first failure thrown at the end. */
class NodeLifecycleTest {
    private val steps = CopyOnWriteArrayList<ServiceNode.Step>()

    private fun node(backends: BackendFactory = InMemoryBackends(), address: String? = "node-1.internal:9090") =
        ServiceNode(
            NODE_SERVICE,
            "node-1",
            address,
            listOf(accountModel(BANK)),
            accountsManifest(BANK),
            backends,
            LoopbackClusterChannel(),
            options = fastNodeOptions(),
            listener = { steps += it },
            contracts = ContractDirectory(accountsManifest(BANK)),
        )

    @Test
    fun `a node never started closes what it was built with when it stops, and refuses commands from then on`(): Unit =
        runBlocking {
            val backends = InMemoryBackends()
            val node = node(backends)
            node.stop()
            node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
            // It joined nothing to hand off; it closes its gateway, executors and logs in order.
            steps.assert().containsExactly(*(ServiceNode.Step.entries - ServiceNode.Step.HANDED_OFF).toTypedArray())
            val refused = node.gateway.execute(
                ServerCommandRequest(
                    context = BANK,
                    aggregate = "account",
                    command = "Deposit",
                    body = CommandBody.of("""{"amount":7}"""),
                    caller = Caller("t"),
                    aggregateId = "a-1",
                    wait = WaitPlan.COMMITTED,
                ),
            )
            refused.status.assert().isEqualTo(CommandResult.Status.FAILED)
            refused.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
            backends.opened.getValue(BANK).log.read(refused.ref).toList().assert().isEmpty()
            // The executor refuses too, and stopping again does nothing.
            val bank = node.context(BANK)
            val model = bank.models.first()
            bank.coordinator.executeHere(model, Command("c-1", "r-1", model.type.ref("t", "a-2"), Deposit()), null)
                .committed.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
            steps.clear()
            node.stop()
            steps.assert().isEmpty()
            assertThrows<IllegalStateException> { runBlocking { node.start() } }
        }

    @Test
    fun `a started node runs every step of its stop once, however often it is stopped`(): Unit = runBlocking {
        val node = node()
        node.start()
        node.isReady.assert().isTrue()
        node.stop()
        node.stop()
        steps.assert().containsExactly(*ServiceNode.Step.entries.toTypedArray())
        node.isReady.assert().isFalse()
    }

    @Test
    fun `a step that fails does not stop the next ones, and the stop throws its failure`(): Unit = runBlocking {
        val inMemory = InMemoryBackends()
        val leaving = object : LeaseStore by InMemoryLeaseStore() {
            override suspend fun leave(service: String, node: String): Unit = error("The lease store is down.")
        }
        val node = node({ context -> inMemory.backends(context).copyWith(leases = leaving) })
        node.start()
        assertThrows<IllegalStateException> { runBlocking { node.stop() } }
            .message.assert().isEqualTo("The lease store is down.")
        steps.assert().containsExactly(*ServiceNode.Step.entries.toTypedArray())
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
    }

    @Test
    fun `an address is bound once, before the node starts`(): Unit = runBlocking {
        val node = node(address = null)
        node.hasAddress.assert().isFalse()
        assertThrows<IllegalStateException> { runBlocking { node.start() } }
        node.bind("node-1.internal:9091")
        node.address.assert().isEqualTo("node-1.internal:9091")
        assertThrows<IllegalStateException> { node.bind("node-1.internal:9092") }
        node.start()
        assertThrows<IllegalStateException> { node.bind("node-1.internal:9093") }
        node.stop()
    }

    private companion object {
        const val BANK = "bank"
    }
}

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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.binding.DerivedCommands
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/**
 * A saga's derived commands to its own context and to another settle alike: each on its own, a rejection stopping
 * none of the others; those to another context go to its command topic, one batch per topic, each aggregate's in the
 * order the saga derived them.
 */
class DerivedAcrossContextsTest {
    private val log = InMemoryEventLog()
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(log)),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SALES_SERVICE),
        WaitOptions(),
    )
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val sender = CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))
    private val derived = DerivedCommands(listOf(orderModel(confirmable = { false })), coordinator, sender)

    private fun order(id: String, body: Any) = Command(id, id, ORDER.ref(TENANT, "o-1"), body)

    private fun item(item: String, id: String) = Command(id, id, ITEM.ref(TENANT, item), Reserve(1))

    @Test
    fun `local and remote commands should each be settled on their own, in order per aggregate`(): Unit = runBlocking {
        val commands = listOf(
            order("place", PlaceOrder(2)),
            item("i-1", "r-1"),
            // Rejected: the order cannot be confirmed. Neither the commands after it here nor those sent stop.
            order("confirm", ConfirmOrder()),
            item("i-2", "r-2"),
            order("cancel", CancelOrder()),
            item("i-1", "r-3"),
        )
        val executed = derived.execute(commands, null)
        executed.executed.assert().isEqualTo(commands.map { it.id })
        executed.rejected.map { it.command.id }.assert().containsExactly("confirm")
        executed.committed.map { it.command.id }.assert().containsExactly("place", "cancel")
        // Every command to the other context went out, each aggregate's in the order derived.
        val sent = topics.messages(COMMANDS).map { CommandMessages.commandOf(EnvelopeCodec.decode(it.value)) }
        sent.map { it.id }.assert().containsExactlyInAnyOrder("r-1", "r-2", "r-3")
        sent.filter { it.ref.id == "i-1" }.map { it.id }.assert().containsExactly("r-1", "r-3")
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
    }
}

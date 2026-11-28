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
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test

/**
 * Round two, the upgrade path (#7232's command-topic half): a context's command topic deleted and created again while
 * its intake keeps its checkpoints. The intake reads the topic in its context's log group
 * (`ContextAssembly.intakeOf`, a `SubscriptionRuntime` of `MessageEntries.commands` under the log group's name), so
 * its checkpoint is an offset of the old topic: on a new one at least as long, it resumes there, and the commands
 * before it are never taken.
 */
class R2RecreatedCommandTopicTest : CommandIntakeFixtures() {
    @Test
    fun `every command of a command topic created again should be taken, however far the old checkpoint was`(): Unit =
        runBlocking {
            val first = stockNode().also { it.start() }
            reserve("c-1", 1)
            reserve("c-2", 1)
            eventually { stock.decided.containsAll(listOf("c-1", "c-2")) }
            eventually { checkpoint(first, ITEM.ref(TENANT, "i-1")) == 2L }
            first.stop()
            nodes -= first

            // The topic deleted and created again: its offsets start at 0, and three commands are on it.
            val recreated = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
            val sending = CommandSender(ContractDirectory(stockManifest()), SourceTopics(recreated, PREFIX, recreated))
            listOf("c-3", "c-4", "c-5").forEach { sending.send(Command(it, "r-$it", ITEM.ref(TENANT, "i-1"), Reserve(1))) }
            reading(recreated).start()

            eventually { "c-5" in stock.decided }
            stock.decided.assert().containsExactly("c-1", "c-2", "c-3", "c-4", "c-5")
        }

    /** A stock node whose intake reads [source], with the stores of the node before it. */
    private fun reading(source: InMemoryTopics): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-2",
        "stock-2.internal:9090",
        listOf(stock.model),
        stockManifest(),
        stockBackends,
        channel,
        options = fastNodeOptions(),
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(source, PREFIX, source, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }
}

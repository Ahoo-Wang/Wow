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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.NodeSample
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.node.subscriptionsView
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A context's command topic deleted and created again (#7246): another incarnation, whose offsets name other commands.
 * The intake keeps its checkpoints per incarnation ([IntakeIncarnation]): a node that starts after the recreation takes
 * every command of the new topic, from its beginning, whatever the old checkpoint; a node that runs meanwhile places no
 * read of it until it starts again.
 */
class CommandIntakeRecreatedTopicTest : CommandIntakeFixtures() {
    private val commands = Recreatable()

    @Test
    fun `a node that starts after its command topic was created again takes every command of the new one`(): Unit =
        runBlocking {
            val warnings = warningsOf<IntakeIncarnation>()
            val first = reading("stock-1").also { it.start() }
            send("c-1", "c-2")
            eventually { stock.decided.containsAll(listOf("c-1", "c-2")) }
            eventually { checkpointOf(first, A) == 2L }
            first.stop()
            nodes -= first

            commands.recreate(B)
            send("c-3", "c-4", "c-5")
            val second = reading("stock-2").also { it.start() }

            eventually { "c-5" in stock.decided }
            stock.decided.assert().containsExactly("c-1", "c-2", "c-3", "c-4", "c-5")
            eventually { checkpointOf(second, B) == 3L }
            checkpointOf(second, A).assert().isEqualTo(2L)
            warnings.list.single { "earlier incarnations" in it.formattedMessage }.formattedMessage.assert()
                .contains(COMMANDS).contains("incarnation $B").contains("$A: ")
            // The incarnations are recorded under the log group's name, whose report passes over Wow's own names.
            second.subscriptionsView(NodeSample.EMPTY, maxParked = 10).orphans.assert().isEmpty()
        }

    @Test
    fun `a running node takes no command of its command topic created again until it starts again`(): Unit =
        runBlocking {
            val running = reading("stock-1").also { it.start() }
            send("c-1")
            eventually { "c-1" in stock.decided }

            commands.recreate(B)
            send("c-2")
            // Each read of the partition placed again asks the incarnation, and is refused.
            eventually { commands.askedSince() >= 3 }
            stock.decided.assert().containsExactly("c-1")

            running.stop()
            nodes -= running
            reading("stock-2").start()
            eventually { "c-2" in stock.decided }
            stock.decided.assert().containsExactly("c-1", "c-2")
        }

    private suspend fun send(vararg ids: String) {
        val current = commands.current
        val sender = CommandSender(ContractDirectory(stockManifest()), SourceTopics(current, PREFIX, current))
        ids.forEach { sender.send(Command(it, "r-$it", ITEM.ref(TENANT, "i-1"), Reserve(1))) }
    }

    /** The intake's checkpoint of item `i-1`'s partition under [incarnation]'s group. */
    private suspend fun checkpointOf(node: ServiceNode, incarnation: String): Long =
        stockBackends.backends(STOCK).checkpoints.load(
            "${node.context(STOCK).group.name}#$incarnation",
            CommandIntake.NAME,
            Partitioner.partitionOf(ITEM.ref(TENANT, "i-1"), PARTITIONS),
        ) ?: 0L

    /** A stock node whose intake reads [commands], its receipts on the fixture's topics. */
    private fun reading(name: String): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        stockBackends,
        channel,
        options = fastNodeOptions(),
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(commands, PREFIX, topics, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private inline fun <reified T> warningsOf(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(T::class.java) as Logger
        return ListAppender<ILoggingEvent>().apply { start() }.also { logger.addAppender(it) }
    }

    /**
     * The command topic, which may be deleted and created again ([recreate]): another [InMemoryTopics], another
     * incarnation. A read placed on the one deleted fails, as a read of a deleted topic does.
     */
    private class Recreatable : PartitionedSource {
        @Volatile
        var current = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
            private set

        @Volatile
        private var incarnation = A

        private val asked = AtomicInteger()

        @Volatile
        private var recreatedAt = 0

        fun recreate(next: String) {
            current = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
            incarnation = next
            recreatedAt = asked.get()
        }

        /** How often the incarnation was asked since the last [recreate]. */
        fun askedSince(): Int = asked.get() - recreatedAt

        override suspend fun incarnation(topic: String): String = incarnation.also { asked.incrementAndGet() }

        override suspend fun partitions(topic: String): List<Int> = current.partitions(topic)

        override suspend fun partitionOf(topic: String, key: String): Int = current.partitionOf(topic, key)

        override suspend fun offsets(topic: String, partition: Int): PartitionOffsets =
            current.offsets(topic, partition)

        override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = channelFlow {
            val bound = current
            launch {
                while (bound === current) {
                    delay(2)
                }
                throw IllegalStateException("Topic $topic was deleted.")
            }
            bound.read(topic, partition, from).collect { send(it) }
        }
    }

    private companion object {
        const val A = "incarnation-a"
        const val B = "incarnation-b"
    }
}

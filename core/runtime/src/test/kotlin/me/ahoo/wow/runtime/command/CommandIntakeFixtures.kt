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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stock service and a sales service whose saga reserves stock, over [InMemoryTopics] and a loopback channel, the
 * receipts' rejections and cancellations recorded: what the command intake's tests share, each test with its own.
 */
abstract class CommandIntakeFixtures {
    protected val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    protected val channel = LoopbackClusterChannel()
    protected val nodes = CopyOnWriteArrayList<ServiceNode>()
    protected val stock = StockItems()
    protected val stockBackends = InMemoryBackends(PARTITIONS)
    protected val salesBackends = InMemoryBackends(PARTITIONS)
    protected val cancelled = CopyOnWriteArrayList<String>()
    protected val rejections = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    protected fun stockNode(
        name: String = "stock-1",
        producer: TopicProducer = topics,
        backends: BackendFactory = stockBackends,
        options: ServiceNodeOptions = fastNodeOptions(),
    ): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        backends,
        channel,
        options = options,
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(topics, PREFIX, producer, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    protected fun salesNode(peer: ManifestFragment = peer(stockManifest())): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest(),
        salesBackends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))),
        contracts = ContractDirectory(salesManifest(), listOf(peer)),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    protected suspend fun ServiceNode.place(
        id: String,
        quantity: Int,
        headers: Map<String, String> = emptyMap(),
    ): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command(
                "place-$id",
                "place-$id",
                ORDER.ref(TENANT, id),
                PlaceOrder(quantity),
                headers = headers,
                wait = WaitPlan.COMMITTED,
            ),
        )
    }

    protected val sender = CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))

    protected suspend fun reserve(id: String, quantity: Int, item: String = "i-1"): Command<Reserve> =
        Command(id, "r-$id", ITEM.ref(TENANT, item), Reserve(quantity)).also { sender.send(it) }

    protected suspend fun checkpoint(node: ServiceNode, ref: AggregateRef): Long =
        stockBackends.backends(STOCK).checkpoints.load(
            node.context(STOCK).group.name,
            CommandIntake.NAME,
            Partitioner.partitionOf(ref, PARTITIONS),
        ) ?: 0L

    protected companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"

        /** The stock context's command intake, which parks the commands it cannot execute. */
        val INTAKE = SubscriptionId(STOCK, CommandIntake.NAME)
    }
}

/**
 * Waits until the receipts subscription of [context] in [service] has read every receipt on [topic]: a receipt handled
 * twice would have been by then. Its checkpoint in each partition, in [checkpoints], is the offset to read next, since
 * a receipt's position is its offset + 1, so all are read when the checkpoints add up to the topic's message count.
 */
internal suspend fun receiptsRead(
    topics: InMemoryTopics,
    topic: String,
    checkpoints: CheckpointStore,
    service: String,
    context: String,
) {
    val group = LeaseGroup.topic(service, topic).name
    eventually {
        topics.partitions(topic).sumOf { checkpoints.load(group, Receipts.nameOf(context), it) ?: 0L } ==
            topics.messages(topic).size.toLong()
    }
}

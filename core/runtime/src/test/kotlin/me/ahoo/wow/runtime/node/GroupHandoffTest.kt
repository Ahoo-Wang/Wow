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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.LogGroupsTest.GroupedStores
import me.ahoo.wow.runtime.node.LogGroupsTest.Issue
import me.ahoo.wow.runtime.node.LogGroupsTest.Issued
import me.ahoo.wow.runtime.node.LogGroupsTest.Item
import me.ahoo.wow.runtime.node.LogGroupsTest.Place
import me.ahoo.wow.runtime.node.LogGroupsTest.Placed
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration.Companion.milliseconds
/**
 * The hand-off of a partition in two log groups stops the two groups' subscription runtimes at once: each drains its
 * entries in flight within the subscriptions' stop timeout, so the partition's hand-off takes one stop timeout, not one
 * per group. Held by a gate, not a clock: each group's held handler, once cancelled, ends only when the other group's
 * has been cancelled too, which only a stop of both at once allows, since a runtime's stop waits for its handlers.
 */
class GroupHandoffTest {
    private val stores = GroupedStores()

    /** Each group's projector holding its entry, completed once its handler holds it. */
    private val holding = mapOf(ORDER.name to CompletableDeferred<Unit>(), INVOICE.name to CompletableDeferred())

    /** Each group's held handler cancelled by its runtime's stop. */
    private val cancelled = mapOf(ORDER.name to CompletableDeferred<Unit>(), INVOICE.name to CompletableDeferred())

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a partition held in two groups should have both groups' subscriptions stopped at once`(): Unit =
        runBlocking {
            val node = ServiceNode(
                SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(orderModel(), invoiceModel()),
                manifest(),
                stores,
                LoopbackClusterChannel(),
                options = fastNodeOptions().copyWith(subscription = testOptions(stopTimeout = STOP)),
                subscriptions = SubscriptionFactory { context ->
                    listOf(
                        // Holds its entry for good, so its group's drain takes the whole stop timeout; once cancelled,
                        // it ends only when the other group's handler is cancelled too.
                        Subscription(PROJECTOR, SubscriptionFilter(context.context)) { entry ->
                            val group = entry.stream.ref.type.name
                            holding.getValue(group).complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cancelled.getValue(group).complete(Unit)
                                    cancelled.getValue(if (group == ORDER.name) INVOICE.name else ORDER.name).await()
                                }
                            }
                        },
                    )
                },
            )
            node.start()
            // An order and an invoice in one partition, each in its own group.
            val order = ORDER.ref(TENANT, "o-1")
            val invoice = (1..100).map { INVOICE.ref(TENANT, "i-$it") }
                .first { Partitioner.partitionOf(it, PARTITIONS) == Partitioner.partitionOf(order, PARTITIONS) }
            node.send(order, Place(delayMillis = 60_000))
            node.send(invoice, Issue(delayMillis = 60_000))
            holding.values.forEach { it.await() }
            // Stopped one after the other, the first group's handler would never end, nor the stop.
            node.stop()
            cancelled.values.forEach { it.isCompleted.assert().isTrue() }
        }

    private suspend fun ServiceNode.send(ref: AggregateRef, body: Any) {
        val context = context(CONTEXT)
        val requestId = "${ref.id}-${body.javaClass.simpleName}"
        context.coordinator.execute(
            context.models.first { it.type == ref.type },
            Command(requestId, requestId, ref, body),
        ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    private companion object {
        const val SERVICE = "grouped-service"
        const val CONTEXT = "sales"
        const val TENANT = "t"
        const val PARTITIONS = 4
        const val PROJECTOR = "Projector.onEvent"
        val ORDER = AggregateType(CONTEXT, "order")
        val INVOICE = AggregateType(CONTEXT, "invoice")

        /** Each group's drain waits it out for its held entry. */
        val STOP = 200.milliseconds

        fun orderModel(): AggregateModel<Item> = aggregate<Item>(CONTEXT, "order") {
            initial { Item() }
            decide<Place>(Creation.REQUIRED) { _, body -> Decision.events(Placed(body.delayMillis)) }
            evolve<Placed> { }
        }

        fun invoiceModel(): AggregateModel<Item> = aggregate<Item>(CONTEXT, "invoice") {
            initial { Item() }
            decide<Issue>(Creation.REQUIRED) { _, body -> Decision.events(Issued(body.delayMillis)) }
            evolve<Issued> { }
        }

        fun manifest(): Manifest = Manifest(
            service = ServiceInfo(SERVICE),
            contexts = listOf(
                ContextManifest(
                    CONTEXT,
                    aggregates = listOf(
                        aggregateManifest("order", Place::class.java, Placed::class.java),
                        aggregateManifest("invoice", Issue::class.java, Issued::class.java),
                    ),
                ),
            ),
        )

        fun aggregateManifest(name: String, create: Class<*>, created: Class<*>): AggregateManifest = AggregateManifest(
            name,
            state = StateManifest(Manifest.schemaRef(Item::class.java.name)),
            commands = listOf(
                CommandManifest(
                    create.simpleName,
                    schema = Manifest.schemaRef(create.name),
                    creation = Creation.REQUIRED,
                ),
            ) + Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(CONTEXT, name, it) },
            events = listOf(EventManifest(created.simpleName, schema = Manifest.schemaRef(created.name))),
        )
    }
}

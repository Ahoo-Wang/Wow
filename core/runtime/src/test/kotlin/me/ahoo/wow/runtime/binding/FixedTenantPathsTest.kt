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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.timer.TimedBackends
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.timer.TimerOutcomes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A type's fixed tenant on every path a command of the node takes to it (#6556): the cart is fixed to [SHARED], and
 * the orders are in [TENANT]. A command that names no tenant, or the one it acts for, lands in [SHARED] whatever
 * made it (a saga's `@OnEvent`, `@OnRejected` and `@OnCommitted` handlers, an `@OnSchedule` job, a saga's schedule and
 * a decide function's); one that names another tenant is refused as `wow.command.malformed` and answered as its path
 * answers a refusal: to the saga's `@OnRejected` handler, parked when a receipt's handler derived it, a timer's
 * rejected command.
 */
class FixedTenantPathsTest {
    private val backends = TimedBackends()
    private val channel = LoopbackClusterChannel()
    private var node: ServiceNode? = null
    private val rejected = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()
    private val log get() = backends.opened.getValue(SHOP).log
    private val ledger get() = backends.opened.getValue(SHOP).ledger

    @AfterEach
    fun stop(): Unit = runBlocking { node?.stop() ?: Unit }

    private fun start(vararg handlers: ProcessorBinding<*>, order: AggregateModel<*> = orderModel()): ServiceNode =
        ServiceNode(
            SHOP_SERVICE,
            "node-1",
            "node-1.internal:9090",
            listOf(order, cartModel()),
            shopManifest(),
            backends,
            channel,
            options = fastNodeOptions().copyWith(
                timers = TimerOptions(
                    pollInterval = 20.milliseconds,
                    lookahead = 1.seconds,
                    claimTimeout = 300.milliseconds
                ),
            ),
            admission = AdmissionPlugins(tenants = TenantPolicy.fixed(mapOf(CART to SHARED))),
            subscriptions = SubscriptionBindings(mapOf(SHOP to handlers.toList())),
        ).also {
            node = it
            channel.bind(it.address, it.forwardHandler)
            runBlocking { it.start() }
        }

    /** Places the order [id] of [items] in [TENANT], as bob. */
    private suspend fun ServiceNode.place(id: String, vararg items: String) {
        val request = ServerCommandRequest(
            context = SHOP,
            aggregate = "order",
            command = "Place",
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope(ownerId = BOB)),
            aggregateId = id,
            wait = WaitPlan.COMMITTED,
        )
        gateway.execute(request).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    private suspend fun items(ref: AggregateRef): List<Any> = log.read(ref).toList().flatMap { stream ->
        stream.events.map { it.name }
    }

    /** The saga of the placed orders: each derives what [derive] makes of its owner and items. */
    private fun saga(
        rejections: List<RejectionBinding<*>> = emptyList(),
        commits: List<CommittedBinding<*>> = emptyList(),
        derive: (String, List<String>) -> List<CommandRequest<*>>,
    ) = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = rejections,
        commits = commits,
        derives = listOf(AddItems::class.java, RemoveItems::class.java),
    ) { event, _ ->
        val owner = event.scope.ownerId ?: return@saga emptyList()
        event.events.filterIsInstance<Placed>().flatMap { derive(owner, it.items) }
    }

    private val onAddRejected = RejectionBinding.of("CartSaga", "onAddRejected", AddItems::class.java) { receipt, _ ->
        rejected += receipt
        emptyList()
    }

    @Test
    fun `a saga's command that names no tenant lands in the type's fixed tenant`(): Unit = runBlocking {
        val node = start(saga { owner, items -> listOf(CommandRequest.to(CART.ref(TENANT, owner), AddItems(items))) })
        node.place("o-1", "apple")
        eventually { items(CART.ref(SHARED, BOB)).isNotEmpty() }
        items(CART.ref(SHARED, BOB)).assert().containsExactly("shop.cart.ItemsAdded")
        items(CART.ref(TENANT, BOB)).assert().isEmpty()
    }

    @Test
    fun `a saga's command that names another tenant is refused as malformed, to its OnRejected handler`(): Unit =
        runBlocking {
            val node = start(
                saga(rejections = listOf(onAddRejected)) { owner, items ->
                    listOf(CommandRequest.to(CART.ref(OTHER, owner), AddItems(items)))
                },
            )
            node.place("o-1", "apple")
            eventually { rejected.isNotEmpty() }
            rejected.single().error.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            rejected.single().error.message.assert().contains("fixed to $SHARED")
            rejected.single().ref.assert().isEqualTo(CART.ref(OTHER, BOB))
            items(CART.ref(OTHER, BOB)).assert().isEmpty()
            items(CART.ref(SHARED, BOB)).assert().isEmpty()
        }

    @Test
    fun `an OnRejected handler's command lands in the fixed tenant, or parks when it names another`(): Unit =
        runBlocking {
            // The order's tenant is the receipt's execution's: its compensation names none, or another.
            val compensations = mapOf("o-1" to TENANT, "o-2" to OTHER)
            val onRemoveRejected = RejectionBinding.of(
                "CartSaga",
                "onRemoveRejected",
                RemoveItems::class.java,
                derives = listOf(AddItems::class.java),
            ) { receipt, _ ->
                val tenant = compensations.getValue(receipt.executionId.ref.id)
                listOf(
                    CommandRequest.to(CART.ref(tenant, BOB), AddItems(listOf("restored-${receipt.executionId.ref.id}")))
                )
            }
            val node = start(
                saga(rejections = listOf(onRemoveRejected)) { owner, items ->
                    listOf(
                        CommandRequest.to(CART.ref(TENANT, owner), AddItems(items)),
                        CommandRequest.to(CART.ref(TENANT, owner), RemoveItems(listOf(FORBIDDEN_ITEM))),
                    )
                },
            )
            node.place("o-1", "apple")
            eventually { items(CART.ref(SHARED, BOB)).size == 2 }
            log.read(CART.ref(SHARED, BOB)).toList().last().events.single().payload.assert()
                .contains("restored-o-1")
            items(CART.ref(TENANT, BOB)).assert().isEmpty()

            node.place("o-2", "pear")
            eventually { ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2")) }
            ledger.parked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2"))!!.reason.assert()
                .contains(FrameworkCodes.COMMAND_MALFORMED)
            items(CART.ref(OTHER, BOB)).assert().isEmpty()
        }

    @Test
    fun `an OnCommitted handler's command lands in the fixed tenant, or parks when it names another`(): Unit =
        runBlocking {
            val removals = mapOf("o-1" to TENANT, "o-2" to OTHER)
            val onAdded = CommittedBinding.of(
                "CartSaga",
                "onAdded",
                AddItems::class.java,
                derives = listOf(RemoveItems::class.java),
            ) { receipt, _ ->
                val tenant = removals.getValue(receipt.executionId.ref.id)
                listOf(CommandRequest.to(CART.ref(tenant, BOB), RemoveItems(listOf("apple"))))
            }
            val node = start(
                saga(commits = listOf(onAdded)) { owner, items ->
                    listOf(CommandRequest.to(CART.ref(TENANT, owner), AddItems(items)))
                },
            )
            node.place("o-1", "apple")
            eventually { items(CART.ref(SHARED, BOB)) == listOf("shop.cart.ItemsAdded", "shop.cart.ItemsRemoved") }
            items(CART.ref(TENANT, BOB)).assert().isEmpty()

            node.place("o-2", "apple")
            eventually { ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2")) }
            ledger.parked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2"))!!.reason.assert()
                .contains(FrameworkCodes.COMMAND_MALFORMED)
            items(CART.ref(OTHER, BOB)).assert().isEmpty()
        }

    @Test
    fun `a SYSTEM job's command lands in the fixed tenant, and parks the job when it names another`(): Unit =
        runBlocking {
            // A SYSTEM job runs in the default tenant: one cart it names none for, one in another tenant.
            val job = ScheduleJob("restock", ScheduleRule.Cron("* * * * * *"))
            val handler = ProcessorBinding.schedule(
                "Jobs",
                "onRestock",
                job,
                derives = listOf(AddItems::class.java),
            ) { tick, _ ->
                listOf(
                    CommandRequest.to(CART.ref(tick.tenantId, "job"), AddItems(listOf("tick"))),
                    CommandRequest.to(CART.ref(OTHER, "job"), AddItems(listOf("tick"))),
                )
            }
            start(handler)
            eventually { items(CART.ref(SHARED, "job")).isNotEmpty() }
            items(CART.ref(Caller.DEFAULT_TENANT, "job")).assert().isEmpty()
            val instance = ScheduleJobModel.refOf(SHOP, job.name)
            eventually { ledger.isParked(SubscriptionId(SHOP, "Jobs.onRestock"), instance) }
            ledger.parked(SubscriptionId(SHOP, "Jobs.onRestock"), instance)!!.reason.assert()
                .contains(FrameworkCodes.COMMAND_MALFORMED)
            items(CART.ref(OTHER, "job")).assert().isEmpty()
        }

    @Test
    fun `a saga's schedule is set on the cart of the fixed tenant, and refused when it names another`(): Unit =
        runBlocking {
            val onScheduleRejected =
                RejectionBinding.of("CartSaga", "onScheduleRejected", SetSchedule::class.java) { receipt, _ ->
                    rejected += receipt
                    emptyList()
                }
            val saga = ProcessorBinding.saga(
                "CartSaga",
                "onOrderPlaced",
                OrderState::class.java,
                rejections = listOf(onScheduleRejected),
                derives = listOf(AddItems::class.java),
                schedules = listOf(RemoveItems::class.java),
            ) { event, context ->
                val tenant = if (event.ref.id == "o-1") TENANT else OTHER
                context.schedule(
                    "empty",
                    ScheduleRule.after(1.seconds),
                    CommandRequest.to(CART.ref(tenant, BOB), RemoveItems(listOf("apple"))),
                )
                listOf(CommandRequest.to(CART.ref(TENANT, BOB), AddItems(listOf("apple"))))
            }
            val node = start(saga)
            node.place("o-1", "apple")
            eventually { backends.opened.getValue(SHOP).timers!!.find(CART.ref(SHARED, BOB), "empty") != null }
            backends.opened.getValue(SHOP).timers!!.find(CART.ref(TENANT, BOB), "empty").assert().isNull()

            node.place("o-2", "apple")
            eventually { rejected.isNotEmpty() }
            rejected.single().error.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            rejected.single().ref.assert().isEqualTo(CART.ref(OTHER, BOB))
            backends.opened.getValue(SHOP).timers!!.find(CART.ref(OTHER, BOB), "empty").assert().isNull()
        }

    @Test
    fun `a decide function's timer lands in the fixed tenant, and is a rejected command when it names another`(): Unit =
        runBlocking {
            val node = start(order = schedulingOrderModel())
            node.place("o-1", TENANT)
            node.place("o-2", OTHER)
            eventually { items(CART.ref(SHARED, BOB)).isNotEmpty() }
            items(CART.ref(TENANT, BOB)).assert().isEmpty()
            val timers = backends.opened.getValue(SHOP).timers!!
            eventually { timers.find(ORDER.ref(TENANT, "o-2"), FILL)?.outcome == TimerOutcomes.REJECTED }
            items(CART.ref(OTHER, BOB)).assert().isEmpty()
        }

    /** The order, whose placement schedules bob's cart to be filled in the tenant its first item names. */
    private fun schedulingOrderModel(): AggregateModel<OrderState> = aggregate<OrderState>(SHOP, "order") {
        initial { OrderState() }
        decide<Place>(Creation.REQUIRED) { _, body ->
            schedule(
                FILL,
                ScheduleRule.after(1.milliseconds),
                AddItems(listOf("scheduled")),
                CART.ref(body.items.first(), BOB)
            )
            Decision.events(Placed(body.items))
        }
        evolve<Placed> { items = it.items }
    }

    private companion object {
        const val SHARED = "shared"
        const val OTHER = "other"
        const val BOB = "bob"
        const val FILL = "fill"
        const val SAGA = "CartSaga.onOrderPlaced"
    }
}

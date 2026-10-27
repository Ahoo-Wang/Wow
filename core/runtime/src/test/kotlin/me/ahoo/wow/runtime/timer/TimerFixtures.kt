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

package me.ahoo.wow.runtime.timer

import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.Reserve
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.timer.TimerStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

/*
 * An example of scheduled commands (decision D166): an order that cancels itself when it is not paid in time. Placing
 * it schedules its own cancellation; paying cancels the schedule; a cancellation that arrives once the order is paid is
 * rejected by the order's state, which is the guard a schedule relies on.
 */

const val SHOP = "shop"
const val SHOP_SERVICE = "shop-service"
const val TENANT = "t"
const val PAY_TIMEOUT = "pay-timeout"
const val PAID = "order.paid"

val ORDER: AggregateType = AggregateType(SHOP, "order")

fun order(id: String): AggregateRef = ORDER.ref(TENANT, id)

/** Places an order to be paid within [payWithinMillis]; it ships [reserve] items of the stock context, if any. */
data class PlaceOrder(val payWithinMillis: Long = 200, val reserve: Int = 0)

/** Pays the order; [cancelTimeout] false pays it as a racing writer would, before the cancel reaches the store. */
data class PayOrder(val cancelTimeout: Boolean = true)
data class CancelOrder(val reason: String)

/** Schedules a reminder at [at] and changes nothing else: a decision that only schedules. */
data class Remind(val at: Long)
data class OrderPlaced(val payWithinMillis: Long)
data class OrderPaid(val paid: Boolean = true)
data class OrderCancelled(val reason: String)

class OrderState {
    var status: String = "NEW"
}

/** The order of the shop context. */
fun orderModel(): AggregateModel<OrderState> = aggregate<OrderState>(SHOP, "order") {
    initial { OrderState() }
    decide<PlaceOrder>(Creation.REQUIRED) { _, body ->
        schedule(PAY_TIMEOUT, ScheduleRule.after(body.payWithinMillis.milliseconds), CancelOrder("unpaid"))
        if (body.reserve > 0) {
            schedule(
                "reserve",
                ScheduleRule.After(0),
                Reserve(body.reserve),
                ITEM.ref(command.ref.tenantId, command.ref.id)
            )
        }
        Decision.events(OrderPlaced(body.payWithinMillis))
    }
    decide<PayOrder> { state, body ->
        if (state.status != "PLACED") {
            Decision.reject("order.not-placed", state.status)
        } else {
            if (body.cancelTimeout) {
                cancelSchedule(PAY_TIMEOUT)
            }
            Decision.events(OrderPaid())
        }
    }
    decide<CancelOrder> { state, body ->
        when (state.status) {
            "PAID" -> Decision.reject(PAID, "The order is paid.")
            "CANCELLED" -> Decision.NoOp
            else -> Decision.events(OrderCancelled(body.reason))
        }
    }
    decide<Remind> { _, body ->
        schedule("remind", ScheduleRule.At(body.at), CancelOrder("reminded"))
        Decision.NoOp
    }
    evolve<OrderPlaced> { status = "PLACED" }
    evolve<OrderPaid> { status = "PAID" }
    evolve<OrderCancelled> { status = "CANCELLED" }
}

/** The shop service's Manifest: its orders. */
fun shopManifest(): Manifest = Manifest(
    service = ServiceInfo(SHOP_SERVICE),
    contexts = listOf(
        ContextManifest(
            SHOP,
            aggregates = listOf(
                AggregateManifest(
                    "order",
                    state = StateManifest(Manifest.schemaRef(OrderState::class.java.name)),
                    commands = listOf(
                        PlaceOrder::class.java to ManifestCreation.REQUIRED,
                        PayOrder::class.java to ManifestCreation.NONE,
                        CancelOrder::class.java to ManifestCreation.NONE,
                        Remind::class.java to ManifestCreation.NONE,
                    ).map { (command, creation) ->
                        CommandManifest(
                            command.simpleName,
                            schema = Manifest.schemaRef(command.name),
                            creation = creation
                        )
                    },
                    events = listOf(OrderPlaced::class.java, OrderPaid::class.java, OrderCancelled::class.java).map {
                        EventManifest(it.simpleName, schema = Manifest.schemaRef(it.name))
                    },
                    lifecycle = Lifecycle.COMMANDS.keys.toList(),
                ),
            ),
        ),
    ),
)

/**
 * A saga of the shop context that reminds each placed order to pay: it schedules a [CancelOrder] of the order at the
 * commit's time plus [afterMillis], under the key `remind` (decision D198: the system command `wow.schedule.Set` to
 * the order), and derives nothing else.
 */
fun reminderSaga(afterMillis: Long = 100): ProcessorBinding<OrderState> = ProcessorBinding.saga(
    "ReminderSaga",
    "onOrderPlaced",
    OrderState::class.java,
) { event, context ->
    if (event.events.any { it is OrderPlaced }) {
        context.schedule(
            "remind",
            ScheduleRule.after(afterMillis.milliseconds),
            CommandRequest.to(event.ref, CancelOrder("reminder")),
        )
    }
    emptyList()
}

/** In-memory stores of every context, a timer store included, shared by the nodes given this factory. */
class TimedBackends(
    private val partitions: Int = 4,
    private val timers: (String) -> TimerStore = { InMemoryTimerStore() },
) : BackendFactory {
    val opened = ConcurrentHashMap<String, ContextBackends>()

    override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
        ContextBackends(
            log = InMemoryEventLog(partitions),
            checkpoints = InMemoryCheckpointStore(),
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            timers = timers(context),
        )
    }
}

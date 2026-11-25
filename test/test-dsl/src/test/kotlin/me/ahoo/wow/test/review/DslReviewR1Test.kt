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

package me.ahoo.wow.test.review

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessAttributes
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateIds
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.WritePolicies
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.test.ClassPathCommandTypesTest
import me.ahoo.wow.test.DEFAULT_TENANT
import me.ahoo.wow.test.accessPolicySpec
import me.ahoo.wow.test.aggregateSpec
import me.ahoo.wow.test.answeringCommandClient
import me.ahoo.wow.test.rejectedResult
import me.ahoo.wow.test.sagaSpec
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Deep review round 1 of the test kit: where a spec passes or fails otherwise than a node.
 *
 * Each case asserts what a node does, so it fails on main where the test DSL does otherwise; its comment names the
 * production code path the spec disagrees with.
 */
class DslReviewR1Test {
    data class Placed(val total: Int)
    data class Paid(val amount: Int)
    data class Reserve(val orderId: String, val quantity: Int)
    data class Cancel(val reason: String)
    data class CreateShipment(val orderId: String)

    class Order(val id: String) {
        var total = 0
        var paid = 0

        /** What is left to ship: kept in memory only, so a state decoded from its JSON has none. */
        @get:JsonIgnore
        var unshipped = 0
    }

    private val orderModel: AggregateModel<Order> = aggregate("shop", "order") {
        initial { Order(it.id) }
        evolve<Placed> {
            total = it.total
            unshipped = it.total
        }
        evolve<Paid> { paid += it.amount }
    }

    private val stock = AggregateType("inventory", "stock")
    private val shipment = AggregateType("logistics", "shipment")

    /** Reserves the unpaid part of an order, and cancels the order when the inventory refuses ([onRejected] first). */
    private fun reserving(
        retry: HandlerRetry? = null,
        onRejected: (CommandReceipt.Rejected<Reserve>) -> Unit = {},
    ): List<ProcessorBinding<*>> {
        val compensate = RejectionBinding.of(
            "OrderSaga",
            "onReserveRejected",
            Reserve::class.java,
            derives = listOf(Cancel::class.java),
        ) { rejected, _ ->
            onRejected(rejected)
            listOf(CommandRequest.to(rejected.source, Cancel(rejected.error.code)))
        }
        val saga = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            rejections = listOf(compensate),
            retry = retry,
            derives = listOf(Reserve::class.java),
        ) { event: StateEvent<Order>, _ ->
            val reserve = Reserve(event.ref.id, event.state.total - event.state.paid)
            listOf(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), reserve))
        }
        return listOf(saga)
    }

    /** Creates a shipment for each order commit, naming the shipment by an id its context draws. */
    private val drawnShipping = ProcessorBinding.saga(
        "ShippingSaga",
        "onOrder",
        Order::class.java,
        derives = listOf(CreateShipment::class.java),
    ) { event: StateEvent<Order>, context ->
        val target = shipment.ref(event.ref.tenantId, context.ids.next(shipment))
        listOf(CommandRequest.to(target, CreateShipment(event.ref.id)))
    }

    /** Runs [block] with the inventory's peer contract (`commands/peer`) on the class path, as its api jar puts it. */
    private fun <T> withContracts(block: () -> T): T {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(requireNotNull(javaClass.getResource("/commands/peer/"))), javaClass.classLoader)
        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    // region #1 ids drawn per handling

    @Test
    fun `a saga that names its target by an id its context draws should fail expectDeterministic`() =
        sagaSpec(listOf(drawnShipping)) {
            // Node: SubscriptionBindings.executionOf (runtime binding/SubscriptionBindings.kt:348-366) gives each
            // handling the node's id generators, new ids every time, and the derived request id encodes the target's
            // id (kernel DerivedRequests.keyOf): a retry creates a second shipment. The spec gives each handling
            // SequentialIds() anew (ProcessorHandling.kt:105), so handling it again draws the same ids.
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) { assertThrownBy<AssertionError> { expectDeterministic() } }
        }

    @Test
    fun `a commit redelivered to a saga that names its target by a drawn id should derive another request`() =
        sagaSpec(listOf(drawnShipping)) {
            // Node: as above, a redelivery draws a new id, so the saga derives another request to another shipment.
            // The spec's redelivery gets SequentialIds() anew (ProcessorHandling.kt:105).
            givenAggregate(orderModel, "order-1", Placed(10))
            lateinit var first: Command<*>
            whenEvent(Paid(1)) { first = commands.single() }
            whenRedelivered { commands.single().requestId.assert().isNotEqualTo(first.requestId) }
        }

    // endregion

    // region #2 access policy

    data class Draft(val department: String)
    data class Drafted(val department: String)
    data class Edit(val text: String)
    data class Edited(val text: String)

    class Document(val id: String) {
        var department: String? = null
    }

    @Suppress("PropertyName", "VariableNaming")
    private object DocumentAccess : AccessAttributes<Document>() {
        val DEPARTMENT: AccessAttribute.Single<Document, String> =
            AccessAttribute.single("department", String::class.java)
    }

    private val documentModel: AggregateModel<Document> = aggregate("office", "document") {
        initial { Document(it.id) }
        decide<Draft>(Creation.REQUIRED) { _, body -> Decision.events(Drafted(body.department)) }
        decide<Edit> { _, body -> Decision.events(Edited(body.text)) }
        evolve<Drafted> { department = it.department }
        evolve<Edited> { }
        access(DocumentAccess.DEPARTMENT) { it.department }
    }

    /** Its owner reads a document; a caller of its department changes it. */
    private val documentAccess = accessPolicy<Document> {
        read { caller -> DocumentAccess.OWNER eq caller.subject }
        write { caller -> DocumentAccess.DEPARTMENT isIn caller.claims("departments") }
    }

    private fun author(department: String) = Caller.of(
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf("departments" to listOf(department)),
    )

    @Test
    fun `a command the aggregate's access policy denies should be refused by aggregateSpec, as by a node's kernel`() {
        // Node: ContextAssembly (runtime node/ContextAssembly.kt:421-437) gives the kernel the service's policies and
        // the gateway sets the command's caller, so CommandReach decides by the policy on the caller's claims
        // (kernel KernelAnswers.kt:53-54): alice, now of hr, may read her sales document but not change it. The
        // spec's kernel has no policies and its commands no caller (AggregateSpec.kt:76-84, 351-360): the default
        // scope rule lets her owner scope change it.
        val sales = author("sales")
        val hr = author("hr")
        val kernel = Kernel(
            InMemoryEventLog(partitions = 1),
            policies = WritePolicies.of(listOf(documentAccess), listOf(documentModel)),
        )
        val ref = documentModel.type.ref(DEFAULT_TENANT, "aggregate-1")
        val onNode = runBlocking {
            val draft = Command("c-1", "r-1", ref, Draft("sales"), scope = sales.scope, caller = sales)
            kernel.execute(documentModel, draft)
            kernel.execute(documentModel, Command("c-2", "r-2", ref, Edit("moved"), scope = hr.scope, caller = hr))
        }
        (onNode as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        aggregateSpec(documentModel, caller = sales) {
            givenCommands(Draft("sales"))
            asCaller(hr) { whenCommand(Edit("moved")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) } }
        }
    }

    // endregion

    // region #3 a schedule for another aggregate type

    data class Plan(val orderId: String)
    data class Planned(val orderId: String)

    class Planner(val id: String) {
        var orderId = ""
    }

    /** Plans an order's restock: the inventory's stock is sent the peer's Reserve a day later. */
    private val plannerModel: AggregateModel<Planner> = aggregate("shop", "planner") {
        initial { Planner(it.id) }
        decide<Plan>(Creation.REQUIRED) { _, body ->
            val restock = ClassPathCommandTypesTest.Reserve(body.orderId)
            schedule("restock", ScheduleRule.after(1.days), restock, stock.ref(command.ref.tenantId, "mug"))
            Decision.events(Planned(body.orderId))
        }
        evolve<Planned> { orderId = it.orderId }
    }

    @Test
    fun `a decision that schedules a peer's command for its aggregate should commit the schedule`() = withContracts {
        // Node: ContextAssembly (runtime node/ContextAssembly.kt:427) gives the kernel the context's command types,
        // which name a peer's command by its contract (ContextCommandTypes, runtime timer/ContextTimers.kt:42-76).
        // AggregateSpec.kt:76-84 gives it none, so AttemptScope.typeOf throws Misuse.Argument.
        aggregateSpec(plannerModel) {
            whenCommand(Plan("order-1")) {
                val restock = ClassPathCommandTypesTest.Reserve("order-1")
                expectScheduled("restock", restock, target = stock.ref(DEFAULT_TENANT, "mug"))
            }
        }
    }

    // endregion

    // region #4, #5 a scheduled command's envelope and failure

    data class Submit(val total: Int)
    data class Submitted(val total: Int)
    data class Expire(val reason: String)

    /** What the decision of a scheduled Expire read of its command: its operator, its owner and a header. */
    data class Expired(val operator: String?, val owner: String?, val trace: String?)

    class Ticket(val id: String) {
        var expired = false
    }

    /** A ticket that expires 30 minutes after its submission, as [expire] decides. */
    private fun ticketModel(
        expire: DecideScope.(Ticket, Expire) -> Decision = { _, _ ->
            Decision.events(Expired(command.operator?.id, command.scope.ownerId, command.headers[TRACE]))
        },
    ): AggregateModel<Ticket> = aggregate("shop", "ticket") {
        initial { Ticket(it.id) }
        decide<Submit>(Creation.REQUIRED) { _, body ->
            schedule("expire", ScheduleRule.after(30.minutes), Expire("unpaid"))
            Decision.events(Submitted(body.total))
        }
        decide<Expire> { state, body -> expire(state, body) }
        evolve<Submitted> { }
        evolve<Expired> { expired = true }
    }

    private val buyer = Caller.of(tenantId = "acme", scope = Scope("alice"), subject = "alice")

    @Test
    fun `a scheduled command should carry the scheduling commit's operator and scope, and only propagated facts`() =
        aggregateSpec(ticketModel(), caller = buyer) {
            // Node: ContextTimerFirer.fired (runtime timer/ContextTimers.kt:117-129) sends the timer's command with
            // the operator and scope the timer took from the scheduling commit (spi Timer.of), and of its headers only
            // RequestFacts.PROPAGATED. AggregateTimers.kt:68-75 sends it with no operator, no scope and every header.
            whenCommand(Submit(10), headers = mapOf(TRACE to "t-1")) { expectEventTypes(Submitted::class) }
            advanceTime(30.minutes) { expectEvents(Expired(operator = "alice", owner = "alice", trace = null)) }
        }

    @Test
    fun `a scheduled command whose decision throws should leave its timer pending, to be sent again`() =
        aggregateSpec(ticketModel { _, body -> throw NumberFormatException(body.reason) }) {
            // Node: TimerRunner.attempt catches the failed send, and TimerRunner.failed (runtime
            // timer/TimerRunner.kt:352-395) writes the timer PENDING, attempts + 1, due after the backoff, PARKED
            // after 10 attempts. AggregateTimers.kt:76-80 settles it FIRED whatever the outcome, and advanceTime
            // without expect does not fail.
            whenCommand(Submit(10)) { expectEventTypes(Submitted::class) }
            advanceTime(30.minutes)
            timer("expire")?.status.assert().isEqualTo(TimerStatus.PENDING)
        }

    // endregion

    // region #6, #7 rejections

    @Test
    fun `a compensation should be derived in the scope of the rejected command's commit`() = sagaSpec(reserving()) {
        // Node: DerivedCommands.Rejected.toReceipt (runtime binding/DerivedCommands.kt:272-283) gives the receipt the
        // rejected command's scope, which rejectionContextOf hands the compensations. ProcessorSpec.kt:58-73
        // (rejectedReceipt) leaves the receipt's scope Scope.NONE.
        givenAggregate(orderModel, "order-1", Placed(10), scope = Scope("buyer"))
        whenEvent(Paid(1)) {
            val reserve = expectCommandEnvelope<Reserve> { scope.assert().isEqualTo(Scope("buyer")) }
            whenRejected(reserve, "insufficient") {
                expectCommandEnvelope<Cancel> { scope.assert().isEqualTo(Scope("buyer")) }
            }
        }
    }

    @Test
    fun `the rejection of a compensation for a call's rejection should park, as a compensation is not compensated`() {
        // Node: SubscriptionBindings.callRejected hands the call's rejection to SagaReceipts.rejected, which executes
        // the compensations replying to the @OnRejected handler's execution (runtime binding/SagaReceipts.kt:157):
        // their rejection parks. ProcessorHandling.kt:117-119 records them as the @OnEvent handler's, so whenRejected
        // hands their rejection to the saga's catch-all @OnRejected handler.
        val compensate = RejectionBinding.of(
            "OrderSaga",
            "onReserveRejected",
            Reserve::class.java,
            derives = listOf(Cancel::class.java),
        ) { rejected, _ -> listOf(CommandRequest.to(rejected.source, Cancel(rejected.error.code))) }
        val anyRejected = RejectionBinding.of<Any>("OrderSaga", "onAnyRejected", null) { _, _ -> emptyList() }
        val saga = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            rejections = listOf(compensate, anyRejected),
            calls = listOf(Reserve::class.java),
        ) { event: StateEvent<Order>, context ->
            context.call(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), Reserve(event.ref.id, 1)))
            emptyList()
        }
        sagaSpec(listOf(saga)) {
            answerCalls { rejectedResult(it, "inventory.stock.insufficient") }
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) {
                val cancel = expectCommandEnvelope<Cancel>()
                whenRejected(cancel, "closed") { expectParked() }
            }
        }
    }

    // endregion

    // region #8 the state a handler reads

    @Test
    fun `a handler should read the state decoded from its JSON, without what the JSON does not keep`() {
        // Node: SubscriptionBindings.stateEventOf (runtime binding/SubscriptionBindings.kt:531-551) hands each handler
        // a state decoded from the view's JSON. GivenAggregate.commit (GivenAggregate.kt:111-136) hands the state
        // object it replayed, unshipped and all.
        val shipping = ProcessorBinding.saga(
            "ShippingSaga",
            "onOrder",
            Order::class.java,
            derives = listOf(CreateShipment::class.java),
        ) { event: StateEvent<Order>, _ ->
            val target = shipment.ref(event.ref.tenantId, "shipment-1")
            listOf(CommandRequest.to(target, CreateShipment(event.ref.id))).filter { event.state.unshipped > 0 }
        }
        sagaSpec(listOf(shipping)) {
            val order = givenAggregate(orderModel, "order-1", Placed(10))
            // The state as a node's view decodes it, with the kernel's codec.
            val codec = EventCodec()
            codec.decodeState(orderModel, codec.stateText(order.state)).unshipped.assert().isEqualTo(0)
            whenEvent(Paid(1)) { expectNoCommand() }
        }
    }

    // endregion

    // region #9 the execution's context

    @Test
    fun `a handler of another context's stream should not derive its ids in that context's execution`() {
        // Node: SubscriptionBindings.executionOf (runtime binding/SubscriptionBindings.kt:348-366) names the execution
        // by the context the handler is bound in, and every derived id encodes it (kernel DerivedRequests.executionOf).
        // ProcessorHandling.kt:100-102 names it by the commit's context for an @OnEvent handler. PaymentSaga is bound
        // in a payment context, as the eShop's OrderPaymentSaga is; no input of the spec can name that context.
        val payment = ProcessorBinding.saga(
            "PaymentSaga",
            "onOrder",
            Order::class.java,
            aggregates = listOf("shop.order"),
            derives = listOf(Reserve::class.java),
        ) { event: StateEvent<Order>, _ ->
            listOf(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), Reserve(event.ref.id, 1)))
        }
        sagaSpec(listOf(payment)) {
            val order = givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) {
                val upstream = ExecutionId("shop", payment.name, order.ref, 2)
                val upstreamId =
                    ExecutionSagaContext.derivedId(upstream, stock.ref(DEFAULT_TENANT, "mug"), Reserve::class.java)
                commands.single().id.assert().isNotEqualTo(upstreamId)
            }
        }
    }

    // endregion

    // region #10, #11 receipts of another context, and a schedule's name

    /** A failure the saga's @Retry takes for unrecoverable. */
    class BadCode(message: String) : RuntimeException(message)

    @Test
    fun `a failing receipt of another context's command should be retried by no handler's @Retry lists`() {
        // Node: a receipt from another context is handled by the receipts subscription (runtime command/Receipts.kt:88,
        // Subscription.messages, with no handler retry): the saga's @Retry does not classify it, so BadCode is
        // recoverable. ProcessorSpec.kt:414 checks it against the saga handler's @Retry.
        val retry = HandlerRetry(maxAttempts = 2, unrecoverable = listOf(BadCode::class.java))
        sagaSpec(reserving(retry) { throw BadCode(it.error.code) }) {
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) { whenRejected(commands.single(), "insufficient") { expectRetried() } }
        }
    }

    @Test
    fun `a saga that schedules a command its target does not decide should park`() = withContracts {
        // Node: ContextCommandTypes.nameOf (runtime timer/ContextTimers.kt:49-52) names a peer's command by its
        // contract only, null for one the target does not decide, so SagaDerivation.schedule throws Misuse.Argument.
        // ClassPathCommandTypes.nameOf (ClassPathCommandTypes.kt:46-47) names any class by its simple name.
        val saga = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            schedules = listOf(Cancel::class.java),
        ) { event: StateEvent<Order>, context ->
            val target = stock.ref(event.ref.tenantId, "mug")
            context.schedule("later", ScheduleRule.after(1.days), CommandRequest.to(target, Cancel("late")))
            emptyList()
        }
        sagaSpec(listOf(saga)) {
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) {
                expectError<Misuse.Argument>()
                expectParked()
            }
        }
    }

    // endregion

    // region #12 and after: low

    @Test
    fun `a handling that meets backpressure should park after the runtime's 20 pushed-back attempts`() {
        // Node: EntryHandling (runtime subscription/EntryHandling.kt:168-180) fails the entry once
        // options.backpressure.maxAttempts (20, SubscriptionOptions.kt:258) are spent, and the aggregate parks.
        // ProcessorExpectations.kt:308-309 retries backpressure at every attempt.
        val saga = ProcessorBinding.saga("OrderSaga", "onOrder", Order::class.java) { _: StateEvent<Order>, _ ->
            throw StorageBusyException("The store is busy.")
        }
        sagaSpec(listOf(saga)) {
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) { expectParked(attempt = 20) }
        }
    }

    @Test
    fun `a command without a caller should be refused the scope it names, as the gateway refuses the anonymous`() {
        // Node: a request without a caller is Caller.ANONYMOUS (runtime admission/ServerCommandRequest.kt:58), and
        // CallerAdmission.admit refuses a scope it names without crossScope. AggregateSpec.kt:349-357 builds the
        // caller-less command in that scope, without admission.
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(ticketModel()) { whenCommand(Submit(10), scope = Scope("bob")) { } }
        }.hasMessageContaining(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `an answering client's stream should emit a rejected result, as a client's stream emits every status`() {
        // Contract: CommandClient (api client/CommandClient.kt) says stream emits every result, whatever its status.
        // RouteCalls.kt:85-102 throws a rejected one as a CommandRejectedException.
        val client = answeringCommandClient { it.rejected("inventory.stock.gone") }
        val request = CommandRequest.to(stock.ref(DEFAULT_TENANT, "mug"), Reserve("order-1", 1))
        val results = runBlocking { client.stream(request).toList() }
        results.single().status.assert().isEqualTo(CommandResult.Status.REJECTED)
    }

    @Test
    fun `a given stream that sets a schedule should keep its timer, as a node's timers subscription does`() {
        // Node: TimersSubscription (runtime timer/TimersSubscription.kt:77-97) writes a timer from any stream that
        // holds a ScheduleSet. AggregateSpec.kt:253-270 (commitGiven) appends the stream without recording it.
        val model = ticketModel()
        val expireType = model.commandTypes.first { it.type == Expire::class.java }.name
        aggregateSpec(model) {
            val body = WowJson.mapper.valueToTree<ObjectNode>(Expire("unpaid"))
            given(Submitted(10), ScheduleSet("expire", ScheduleRule.after(30.minutes), ref, expireType, body))
            timer("expire").assert().isNotNull()
        }
    }

    @Test
    fun `a service caller should change an aggregate its policy governs, as the kernel trusts a service`() =
        accessPolicySpec(documentModel, documentAccess) {
            // Kernel: CommandReach (kernel CommandReach.kt:91) trusts a service caller under a policy.
            // AccessPolicySpec.kt:92-122 evaluates the policy's predicate for every caller.
            given(Drafted("sales"))
            whenCaller(Caller.ofService("billing")) { expectWrite() }
        }

    @Test
    fun `a request id past the admission limit should be refused as malformed, as the gateway refuses it`() {
        // Node: CommandGateway (runtime admission/CommandGateway.kt:493, AdmissionLimits.oversized) refuses a request
        // id longer than AggregateIds.MAX_LENGTH, wow.command.malformed. AggregateSpec.commandOf checks no id length.
        val tooLong = "r".repeat(AggregateIds.MAX_LENGTH + 1)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(ticketModel()) { whenCommand(Submit(10), requestId = tooLong) { } }
        }.hasMessageContaining(FrameworkCodes.COMMAND_MALFORMED)
    }

    // endregion

    private companion object {
        /** A request fact no command derived from it inherits. */
        const val TRACE = "X-Trace"
    }
}

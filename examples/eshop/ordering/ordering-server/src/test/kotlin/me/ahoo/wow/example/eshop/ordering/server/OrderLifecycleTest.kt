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

package me.ahoo.wow.example.eshop.ordering.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * The lifecycle routes the Manifest lists for every aggregate (decision D113), on the ordering's orders, which belong
 * to the buyers who submitted them (decision D189): an order deleted and restored, an order moved to another buyer's
 * account, and an order tagged and moved into a fulfilment space. The typed clients send the lifecycle commands by
 * their classes, to the routes the order's contract lists for them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLifecycleTest {
    private val server = wowServer<OrderingServer>("dev")

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `a deleted order should refuse its next command until it is restored`() {
        val alice = buyer()
        val id = submitted(alice)
        lifecycle(id, DeleteAggregate(), alice).version.assert().isEqualTo(VERSION_AFTER_VALIDATION + 1)
        read(id, alice)!!.deleted.assert().isTrue()

        rejected { confirm(id, alice) }.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)

        lifecycle(id, RestoreAggregate(), alice)
        read(id, alice)!!.deleted.assert().isFalse()
        confirm(id, alice)
    }

    @Test
    fun `an order moved to another buyer's account should be that buyer's alone`() {
        val second = buyer()
        val main = buyer()
        val id = submitted(second)
        lifecycle(id, TransferOwner.of(main.subject!!), second)
        read(id, main)!!.scope.ownerId.assert().isEqualTo(main.subject)

        // The former owner neither reads nor changes it: its state is not found for it (decision D254 ④), and neither
        // is the aggregate its commands name (decision D94, the kernel's scope guard).
        read(id, second).assert().isNull()
        rejected { confirm(id, second) }.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        confirm(id, main)
    }

    @Test
    fun `an employee should move an order into a fulfilment space`() {
        val alice = buyer()
        val id = submitted(alice)
        lifecycle(id, TransferSpace.of("warehouse-east"), EMPLOYEE)
        val order = read(id, EMPLOYEE)!!
        order.scope.spaceId.assert().isEqualTo("warehouse-east")
        // The order is still its buyer's.
        order.scope.ownerId.assert().isEqualTo(alice.subject)
    }

    /** A buyer of this test only: the user, and the owner of what it creates. */
    private fun buyer(): Caller =
        ("buyer-" + UUID.randomUUID().toString().take(8)).let { Caller.of(subject = it, scope = Scope(ownerId = it)) }

    /** Submits an order as [buyer] and waits until the order process has it await its stock validation; its id. */
    private fun submitted(buyer: Caller): String {
        val id = "order-" + UUID.randomUUID()
        val items = listOf(OrderItem("mug", BigDecimal("8.50"), 2), OrderItem("cap", BigDecimal("12.00"), 1))
        val request = CommandRequest.builder(SubmitOrder(items)).id(id).wait(TO_VALIDATION).build()
        runBlocking { server.commands(buyer).wait(request) }
        return id
    }

    /** Sends the lifecycle [command] (`wow.lifecycle.*`) to the order [id] as [caller]. */
    private fun lifecycle(id: String, command: Any, caller: Caller): CommandResult = runBlocking {
        server.commands(caller).wait(CommandRequest.of(command, id = id, type = EshopOrderingContract.Order.TYPE))
    }

    private fun confirm(id: String, caller: Caller) {
        runBlocking { server.commands(caller).wait(ConfirmOrderStock("mug"), id) }
    }

    private fun read(id: String, caller: Caller): Snapshot<OrderStateView>? =
        runBlocking { server.queries(caller).snapshots(OrderStateView::class.java).state(id) }

    private fun rejected(send: () -> Unit): CommandRejectedException = assertThrows<CommandRejectedException> { send() }

    private companion object {
        /** The order's version once the order process had it await its validation: submitted, then awaiting. */
        const val VERSION_AFTER_VALIDATION = 2
        val EMPLOYEE = Caller.of(
            subject = "erin",
            attributes = mapOf(DevelopmentHeaders.ROLES_ATTRIBUTE to listOf("employee")),
        )
        val TO_VALIDATION: WaitPlan = WaitPlan.of(
            Hop.handled("OrderProcessSaga.onOrderSubmitted", EshopOrderingContract.NAME),
            Hop.snapshot(EshopOrderingContract.NAME),
        )
    }
}

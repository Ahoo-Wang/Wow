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

package me.ahoo.wow.test

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.toCommandRequest
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.spi.call.DecidingCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A call a decide function sends through the test DSL's [answeringCommandClient] takes the request id a node's client
 * gives it: the command being decided's next call, the same at every attempt of the command, and a call that changes
 * at a later attempt fails the command, as on a node ([DecidingCommand]). A spec's decide runs in the kernel, which
 * puts the command being decided in its context; here the context is given as the kernel gives it.
 */
class DecideTimeRequestIdsTest {
    data class Reserve(val sku: String)

    private val deciding = DecidingCommand(ORDER.ref("acme", "o-1"), "r-1")

    private fun reserve(sku: String) = Reserve(sku).toCommandRequest(to = STOCK.ref("acme", sku))

    @Test
    fun `a call sent while deciding should take the request id a node's client derives`() {
        val inventory = answeringCommandClient { it.committed() }
        runBlocking(deciding) {
            inventory.wait(reserve("mug"))
            inventory.wait(reserve("cup"))
        }
        inventory.calls.map { it.requestId }.assert().containsExactly(
            DecidingCommand.requestIdOf(ORDER, "acme", "r-1", 0),
            DecidingCommand.requestIdOf(ORDER, "acme", "r-1", 1),
        )
        // The committed result answers under that id, as the target's commit of the call does.
        inventory.calls.first().committed().requestId.assert().isEqualTo(inventory.calls.first().requestId)
    }

    @Test
    fun `a call decided again should take the same request id, and one that changed should fail as on a node`() {
        val inventory = answeringCommandClient { it.committed() }
        runBlocking(deciding) { inventory.wait(reserve("mug")) }
        deciding.nextAttempt()
        runBlocking(deciding) { inventory.wait(reserve("mug")) }
        inventory.calls.map { it.requestId }.distinct().assert().hasSize(1)
        deciding.nextAttempt()
        assertThrows<Misuse.State> { runBlocking(deciding) { inventory.wait(reserve("cup")) } }
    }

    @Test
    fun `a call that gives its request id, or is sent outside a decision, should keep its own`() {
        val inventory = answeringCommandClient { it.committed() }
        val own = Reserve("mug").toCommandRequest(STOCK.ref("acme", "mug")) { requestId("own") }
        runBlocking(deciding) { inventory.wait(own) }
        runBlocking { inventory.wait(reserve("cup")) }
        inventory.calls.map { it.requestId }.assert().containsExactly("own", null)
    }

    private companion object {
        val ORDER = AggregateType("sales", "order")
        val STOCK = AggregateType("inventory", "stock")
    }
}

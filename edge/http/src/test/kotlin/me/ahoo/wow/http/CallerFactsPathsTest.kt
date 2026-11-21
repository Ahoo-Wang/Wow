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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One caller's command records the same request facts by every path that sends it (issue #6536): the node's own
 * `CommandClient`, the public port and the test DSL, each deriving them from the caller as admission does
 * (`CallerAdmission`). A path that records other facts than the others fails here.
 */
class CallerFactsPathsTest {
    private val caller =
        Caller(TENANT, Scope("alice"), subject = "alice", attributes = mapOf(Caller.APP to listOf("app-a")))

    // The client's address and User-Agent are the public port's alone: off, so that only the caller's facts remain.
    private val node = HttpNode(callers = CallerResolver { caller }, options = WowHttpOptions(clientFacts = false))

    private fun inProcess(): Map<String, String> = runBlocking {
        InProcessCommandClient(node.gateway).of(caller).wait(CommandRequest.of(AddItem("apple"), id = "c-1"))
        node.commands.last().headers
    }

    private fun publicPort(): Map<String, String> {
        node.post("/shop/cart/c-2/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        return node.commands.last().headers
    }

    private fun spec(): Map<String, String> {
        val decided = CopyOnWriteArrayList<Command<*>>()
        aggregateSpec(cartModel(decided), id = "c-3", caller = caller) {
            whenCommand(AddItem("apple")) { expectNoError() }
        }
        return decided.single().headers
    }

    @Test
    fun `the node's client, the public port and the test DSL record the same facts of one caller`() {
        val facts = mapOf(RequestFacts.APP_ID to "app-a")
        inProcess().assert().isEqualTo(facts)
        publicPort().assert().isEqualTo(facts)
        spec().assert().isEqualTo(facts)
    }
}

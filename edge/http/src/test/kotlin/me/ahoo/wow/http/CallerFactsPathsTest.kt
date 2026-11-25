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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.ServerCommandRequest
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
    // The host lists `app_id` among its request facts, as a host that tried to take it from a header would.
    private val node = HttpNode(
        callers = CallerResolver { caller },
        options = WowHttpOptions(requestFacts = setOf(RequestFacts.APP_ID), clientFacts = false),
    )

    private val forged = mapOf(RequestFacts.APP_ID to "forged")

    private fun inProcess(): Map<String, String> = runBlocking {
        InProcessCommandClient(node.gateway).of(caller).wait(CommandRequest.of(AddItem("apple"), aggregateId = "c-1"))
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
    fun `a request naming its own application gets the caller's in process and on the port, and fails a spec`() {
        val facts = mapOf(RequestFacts.APP_ID to "app-a")
        runBlocking {
            node.gateway.execute(
                ServerCommandRequest.builder(AddItem("apple"), cartModel.type.ref(TENANT, "c-4")).caller(caller)
                    .headers(forged).build(),
            )
        }.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        node.commands.last().headers.assert().isEqualTo(facts)
        node.post("/shop/cart/c-5/add-item", """{"sku":"apple"}""", headers = forged).status.assert().isEqualTo(200)
        node.commands.last().headers.assert().isEqualTo(facts)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(cartModel(CopyOnWriteArrayList()), id = "c-6", caller = caller) {
                whenCommand(AddItem("apple"), headers = forged) { }
            }
        }.hasMessage(
            "A specification's headers do not name app_id: it is the caller's application, which admission takes " +
                "from the caller alone, never from a request. Give it with caller(…), a Caller whose 'app' " +
                "attribute names the application.",
        )
    }

    @Test
    fun `the node's client, the public port and the test DSL record the same facts of one caller`() {
        val facts = mapOf(RequestFacts.APP_ID to "app-a")
        inProcess().assert().isEqualTo(facts)
        publicPort().assert().isEqualTo(facts)
        spec().assert().isEqualTo(facts)
    }
}

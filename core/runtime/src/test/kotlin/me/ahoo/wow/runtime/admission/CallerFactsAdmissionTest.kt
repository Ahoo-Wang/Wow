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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.client.InProcessCommandClient
import org.junit.jupiter.api.Test

/**
 * The request facts admission derives from the caller (`CallerAdmission.factsOf`, issue #6536): every request's command
 * records its caller's application, whoever built the request, and no request names one in its place.
 */
class CallerFactsAdmissionTest : CommandGatewayFixtures() {
    private val inApp = alice.copyWith(attributes = mapOf(Caller.APP to listOf("app-a")))

    @Test
    fun `a command sent through the node's own client records its caller's application`(): Unit = runBlocking {
        val client = InProcessCommandClient(gateway()).of(inApp)
        val result = client.wait(CommandRequest.of(AddItem("apple"), aggregateId = "cart-1"))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.single().headers.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "app-a"))
    }

    @Test
    fun `a request's own application never stands in for its caller's, and the other facts are kept`(): Unit =
        runBlocking {
            val forged = mapOf(RequestFacts.APP_ID to "forged", "X-Trace" to "t-1")
            gateway().execute(request(caller = inApp, headers = forged)).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            gateway().execute(request(caller = alice, headers = forged, aggregateId = "cart-2")).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            commands.map { it.headers }.assert().containsExactly(
                mapOf("X-Trace" to "t-1", RequestFacts.APP_ID to "app-a"),
                mapOf("X-Trace" to "t-1"),
            )
        }

    @Test
    fun `a request's facts are kept as they are when the caller has no application to add`(): Unit = runBlocking {
        val facts = mapOf("X-Trace" to "t-1")
        gateway().execute(request(headers = facts)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.single().headers.assert().isEqualTo(facts)
    }
}

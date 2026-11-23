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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import org.junit.jupiter.api.Test

/**
 * A request id is a tenant's: the same one sent in two tenants is two requests, each committed in its own tenant and
 * answered with its own commit, never with the other's (review TO-8).
 */
class RequestTenantsTest : CommandGatewayFixtures() {
    /** The caller alice of another tenant: the same owner id, another tenant. */
    private val aliceOfGlobex = Caller(OTHER_TENANT, Scope("alice", "home"), subject = "alice")

    private val gateway = gateway(committed = CommittedRequests.of(log))

    private suspend fun committed(caller: Caller, aggregateId: String?, command: String = "AddItem"): CommandResult =
        gateway.execute(
            request(
                command = command,
                body = if (command == "OpenCart") "{}" else """{"sku":"apple"}""",
                caller = caller,
                aggregateId = aggregateId,
                requestId = REQUEST,
                wait = WaitPlan.COMMITTED,
            ),
        ).also { it.status.assert().withFailMessage { "$it" }.isEqualTo(CommandResult.Status.SUCCEEDED) }

    @Test
    fun `a request id reused in another tenant commits there, and each is answered with its own commit`(): Unit =
        runBlocking {
            val acme = committed(alice, "cart-1")
            val globex = committed(aliceOfGlobex, "cart-1")
            acme.ref.assert().isEqualTo(model.type.ref(TENANT, "cart-1"))
            globex.ref.assert().isEqualTo(model.type.ref(OTHER_TENANT, "cart-1"))
            globex.duplicate.assert().isFalse()
            globex.commandId.assert().isNotEqualTo(acme.commandId)
            acme.version.assert().isEqualTo(1)
            globex.version.assert().isEqualTo(1)
            decided.assert().containsExactly(acme.commandId, globex.commandId)
            // Each tenant's retry is answered with its own commit.
            val again = committed(aliceOfGlobex, "cart-1")
            again.duplicate.assert().isTrue()
            again.ref.assert().isEqualTo(globex.ref)
            again.version.assert().isEqualTo(1)
            val acmeAgain = committed(alice, "cart-1")
            acmeAgain.duplicate.assert().isTrue()
            acmeAgain.ref.assert().isEqualTo(acme.ref)
            decided.assert().hasSize(2)
        }

    @Test
    fun `a creation whose id admission draws, reused in another tenant, draws its own aggregate there`(): Unit =
        runBlocking {
            val acme = committed(alice, null, "OpenCart")
            val globex = committed(aliceOfGlobex, null, "OpenCart")
            acme.ref.tenantId.assert().isEqualTo(TENANT)
            globex.ref.tenantId.assert().isEqualTo(OTHER_TENANT)
            globex.duplicate.assert().isFalse()
            globex.commandId.assert().isNotEqualTo(acme.commandId)
            decided.assert().containsExactly(acme.commandId, globex.commandId)
            // A retry in the first tenant keeps its aggregate, not the one the other tenant was given.
            committed(alice, null, "OpenCart").ref.assert().isEqualTo(acme.ref)
            committed(aliceOfGlobex, null, "OpenCart").ref.assert().isEqualTo(globex.ref)
        }

    private companion object {
        const val OTHER_TENANT = "globex"
        const val REQUEST = "r-1"
    }
}

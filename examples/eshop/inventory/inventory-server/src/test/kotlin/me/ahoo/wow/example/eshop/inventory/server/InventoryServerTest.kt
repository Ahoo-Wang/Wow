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

package me.ahoo.wow.example.eshop.inventory.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.example.eshop.inventory.api.stock.ReceiveStock
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/** The inventory service as its `application.yaml` configures it, on random ports, driven by its typed clients. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryServerTest {
    private val server = wowServer<InventoryServer>("dev")

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the server should run one node of the inventory context on the memory backend`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-inventory-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-inventory")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }.assert().containsExactly("stock")
    }

    @Test
    fun `a quantity that is not positive should be invalid before the stock decides`() {
        for (command in listOf(ReceiveStock(0), ReserveStock("o-1", -1))) {
            val invalid = rejected { server.commands(EMPLOYEE).wait(command, "mug") }
            invalid.code.assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
            invalid.result.error!!.violations.single().let {
                it.path.assert().isEqualTo("quantity")
                it.code.assert().isEqualTo("positive")
            }
        }
    }

    @Test
    fun `a buyer should not change the stock, which an employee receives (UI-49)`() {
        val refused = rejected { server.commands(BUYER).wait(ReceiveStock(3), "cap") }
        refused.code.assert().isEqualTo(FrameworkErrors.AUTH_FORBIDDEN)
        refused.result.error!!.message.assert().contains("Only an employee changes the stock")
        rejected { server.commands(BUYER).wait(ReserveStock("o-1", 1), "cap") }
            .code.assert().isEqualTo(FrameworkErrors.AUTH_FORBIDDEN)
        runBlocking { server.commands(EMPLOYEE).wait(ReceiveStock(3), "cap") }
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    /**
     * Over HTTP: a context's job commands (`wow.schedule.Disable`, `Enable`) are no command of its contract, which the
     * typed clients send by, so they go to the job's routes themselves.
     */
    @Test
    fun `the scheduled job's commands should be an employee's alone (UI-50)`() {
        val disable = "/eshop-inventory/wow.schedule/receive-deliveries/disable"
        for (caller in listOf(emptyMap(), BUYER_HEADERS)) {
            val refused = server.http.post(disable, "{}", caller)
            refused.problem!!.code.assert().isEqualTo(FrameworkErrors.AUTH_FORBIDDEN)
            refused.problem!!.detail.assert().contains("Only an employee sends wow.schedule.Disable")
        }
        server.http.post(disable, "{}", EMPLOYEE_HEADERS).problem.assert().isNull()
        val enable = server.http.post(
            "/eshop-inventory/wow.schedule/receive-deliveries/enable",
            """{"job":"receive-deliveries"}""",
            EMPLOYEE_HEADERS,
        )
        enable.problem.assert().describedAs(enable.text).isNull()
    }

    private fun rejected(send: suspend () -> Unit): CommandRejectedException =
        assertThrows<CommandRejectedException> { runBlocking { send() } }

    private companion object {
        val BUYER = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
        val EMPLOYEE = Caller.of(
            subject = "erin",
            attributes = mapOf(DevelopmentHeaders.ROLES_ATTRIBUTE to listOf("employee"))
        )
        val BUYER_HEADERS = mapOf(DevelopmentHeaders.USER to "alice", DevelopmentHeaders.OWNER to "alice")
        val EMPLOYEE_HEADERS = mapOf(DevelopmentHeaders.USER to "erin", DevelopmentHeaders.ROLES to "employee")
    }
}

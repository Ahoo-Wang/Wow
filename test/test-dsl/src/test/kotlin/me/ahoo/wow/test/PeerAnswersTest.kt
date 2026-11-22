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
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.toCommandRequest
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Application code that calls a peer through a [CommandClient], tested with the peer's three answers: a success with
 * the values its decision answered, a rejection with the fields the command broke, and a failure. Built with the test
 * DSL's [answeringCommandClient] alone, as an application's test is: `documentation/docs/{en,zh}/guide/
 * testing-services.md` shows it.
 */
class PeerAnswersTest {
    data class Reserve(val sku: String)

    // region code
    /** Reserves [sku] at the inventory and says how it went: the code under test, which calls a peer. */
    suspend fun reserve(inventory: CommandClient, sku: String): String = try {
        val result = inventory.wait(Reserve(sku).toCommandRequest(to = STOCK.ref("acme", sku)))
        "reserved as ${result.results["reservationId"]}"
    } catch (rejected: CommandRejectedException) {
        "refused: ${rejected.code} at ${rejected.violations.map { it.path }}"
    } catch (problem: WowProblemException) {
        if (problem.retryable) "try again later" else throw problem
    }
    // endregion

    @Test
    fun `the code sees the peer's values, its rejection and its failure, as a real client gives them`() {
        // region answers
        val inventory = answeringCommandClient { call ->
            when ((call.request.command as Reserve).sku) {
                "mug" -> call.committed(results = mapOf("reservationId" to "R-1"))
                "x" -> call.rejected(
                    "inventory.stock.unknown-sku",
                    violations = listOf(Violation.of("sku", "unknown", "No product has this SKU.")),
                )
                else -> call.failed(FrameworkCodes.STORAGE_UNAVAILABLE)
            }
        }
        runBlocking { reserve(inventory, "mug") }.assert().isEqualTo("reserved as R-1")
        runBlocking { reserve(inventory, "x") }.assert().isEqualTo("refused: inventory.stock.unknown-sku at [sku]")
        runBlocking { reserve(inventory, "cup") }.assert().isEqualTo("try again later")
        // endregion
    }

    @Test
    fun `a rejection carries its extensions, and a failure what a retry may do`() {
        val inventory = answeringCommandClient { call ->
            when ((call.request.command as Reserve).sku) {
                "x" -> call.rejected("inventory.stock.short", "Short.", extensions = mapOf("available" to 2))
                else -> call.failed(FrameworkCodes.STORAGE_UNAVAILABLE, "down")
            }
        }
        val rejected = assertThrows<CommandRejectedException> {
            runBlocking { inventory.wait(Reserve("x").toCommandRequest(to = STOCK.ref("acme", "x"))) }
        }
        rejected.error.message.assert().isEqualTo("Short.")
        rejected.error.extensions.assert().isEqualTo(mapOf("available" to 2))
        rejected.violations.assert().isEmpty()
        val failed = assertThrows<WowProblemException> {
            runBlocking { inventory.wait(Reserve("y").toCommandRequest(to = STOCK.ref("acme", "y"))) }
        }
        failed.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        failed.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        failed.result!!.error!!.message.assert().isEqualTo("down")
    }

    @Test
    fun `a committed result without values answers none`() {
        val inventory = answeringCommandClient { it.committed(version = 2) }
        val result = runBlocking { inventory.wait(Reserve("mug").toCommandRequest(to = STOCK.ref("acme", "mug"))) }
        result.version.assert().isEqualTo(2)
        result.results.assert().isEmpty()
    }

    private companion object {
        val STOCK = AggregateType("inventory", "stock")
    }
}

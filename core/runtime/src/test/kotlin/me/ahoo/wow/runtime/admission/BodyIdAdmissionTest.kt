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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Admission of a command whose body names its aggregate (`@AggregateId`): its body is read before it is authorized,
 * so the action names that aggregate; a ref beside the body names the same one; a creation whose body names none is
 * given one.
 */
class BodyIdAdmissionTest {
    data class Stock(val productId: String?, val quantity: Int)
    data class Restock(val productId: String, val quantity: Int)
    data class Audit(val productId: String)
    data class Count(val note: String)
    data class Stocked(val quantity: Int)

    private val actions = CopyOnWriteArrayList<CommandAction>()

    private val product: AggregateModel<Any> = aggregate(CONTEXT, "product") {
        initial { Any() }
        decide<Stock>(Creation.REQUIRED) { _, body -> Decision.events(Stocked(body.quantity)) }
        decide<Restock> { _, body -> Decision.events(Stocked(body.quantity)) }
        decide<Audit> { _, _ -> Decision.NoOp }
        decide<Count> { _, _ -> Decision.NoOp }
        evolve<Stocked> { }
        aggregateId<Stock> { it.productId }
        aggregateId<Restock> { it.productId }
        // A computed id that cannot be computed from the fields given.
        aggregateId<Audit> { error("No product is audited as ${it.productId}.") }
        // A body that names no aggregate, as a Java class's field left null does.
        aggregateId<Count> { null }
    }

    private val waits = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2))),
        waits,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, waits),
        origin,
        WaitOptions(maxTimeout = 30.seconds),
    )
    private val gateway = CommandGateway(
        ModelRegistry(listOf(product)),
        mapOf(CONTEXT to coordinator),
        authorizer = CommandAuthorizer { _, action ->
            actions += action
            Authorization.Allowed
        },
    )

    private fun request(command: String, body: String, aggregateId: String? = null, requestId: String? = null) =
        ServerCommandRequest(
            context = CONTEXT,
            aggregate = "product",
            command = command,
            body = CommandBody.of(body),
            caller = alice,
            aggregateId = aggregateId,
            requestId = requestId,
            wait = WaitPlan.COMMITTED,
        )

    private suspend fun succeeded(request: ServerCommandRequest): CommandResult =
        gateway.execute(request).also {
            it.status.assert().withFailMessage { "$it" }.isEqualTo(CommandResult.Status.SUCCEEDED)
        }

    private suspend fun refused(request: ServerCommandRequest, message: String) {
        val result = gateway.execute(request)
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        result.error!!.message.assert().isEqualTo(message)
    }

    @Test
    fun `a request without an id goes to the aggregate its body names, authorized as that aggregate`(): Unit =
        runBlocking {
            succeeded(request("Stock", """{"productId":"mug","quantity":1}""")).ref.assert()
                .isEqualTo(product.type.ref(TENANT, "mug"))
            val restocked = succeeded(request("Restock", """{"productId":"mug","quantity":2}"""))
            restocked.ref.assert().isEqualTo(product.type.ref(TENANT, "mug"))
            restocked.version.assert().isEqualTo(2)
            actions.map { it.ref }.assert().containsOnly(product.type.ref(TENANT, "mug"))
            // A ref beside the body names the same aggregate.
            succeeded(request("Restock", """{"productId":"mug","quantity":3}""", aggregateId = "mug"))
                .version.assert().isEqualTo(3)
        }

    @Test
    fun `a creation whose body names no aggregate is given one, or goes to the ref beside it`(): Unit = runBlocking {
        val created = succeeded(request("Stock", """{"productId":null,"quantity":1}""", requestId = "stock-1"))
        created.ref.isAssigned.assert().isTrue()
        // A retry of the request is given the same one.
        gateway.execute(request("Stock", """{"quantity":1}""", requestId = "stock-1")).ref.assert()
            .isEqualTo(created.ref)
        succeeded(request("Stock", """{"quantity":1}""", aggregateId = "cup")).ref.assert()
            .isEqualTo(product.type.ref(TENANT, "cup"))
    }

    @Test
    fun `a body that names another aggregate than the request, a blank one or none is malformed`(): Unit =
        runBlocking {
            refused(
                request("Restock", """{"productId":"mug","quantity":1}""", aggregateId = "cup"),
                "The request names the aggregate cup, but the body of $CONTEXT.product.Restock names mug.",
            )
            refused(
                request("Restock", """{"productId":"","quantity":1}"""),
                "The body of $CONTEXT.product.Restock names a blank aggregate id.",
            )
            refused(
                request("Audit", """{"productId":"mug"}"""),
                "The aggregate the body of $CONTEXT.product.Audit names cannot be read: No product is audited as mug.",
            )
            refused(request("Count", """{"note":"n"}"""), "The body of $CONTEXT.product.Count names no aggregate.")
            refused(request("Restock", """{"productId":"mug"}"""), "The body is not a $CONTEXT.product.Restock.")
            refused(
                request("Restock", """{"productId":"mug\u0000"}"""),
                "The body writes \\u0000 in a string, which no store keeps.",
            )
            // Each was refused before it was authorized.
            actions.assert().isEmpty()
        }

    @Test
    fun `no transformer takes a command whose body names its aggregate`() {
        val restock = product.commandType(Restock::class.java)
        val transformer = object : CommandTransformer {
            override val input = restock

            override suspend fun transform(input: CommandInput): CommandTarget =
                CommandTarget(restock, null, input.body)
        }
        assertThrownBy<IllegalArgumentException> {
            CommandGateway(
                ModelRegistry(listOf(product)),
                mapOf(CONTEXT to coordinator),
                transformers = listOf(transformer),
            )
        }
            .hasMessageContaining("takes $CONTEXT.product.Restock, whose body names its aggregate (@AggregateId)")
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
        val alice = Caller(TENANT, Scope("alice", "home"), subject = "alice")
    }
}

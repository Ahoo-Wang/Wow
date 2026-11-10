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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What admission answers from the execution: a full lane, a closed executor, an accepted-only plan run detached and
 * the closing that waits for it, a plan the runtime cannot follow, and a store unavailable while running or before.
 */
class CommandGatewayExecutionTest : CommandGatewayFixtures() {
    @Test
    fun `a full lane answers busy, as a result`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { hold = it }
        val gateway = gateway()
        val held = async(Dispatchers.Default) { gateway.execute(request()) }
        eventually { decided.size == 1 }
        val busy = gateway.execute(request())
        busy.status.assert().isEqualTo(CommandResult.Status.FAILED)
        busy.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_BUSY)
        busy.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        gate.complete(Unit)
        held.await().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a closed executor answers node closing, as a result`(): Unit = runBlocking {
        executor.close(10.seconds)
        val result = gateway().execute(request())
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
    }

    @Test
    fun `an accepted-only plan is answered at admission and runs detached`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { hold = it }
        val gateway = gateway()
        val results = withTimeout(5.seconds) { gateway.send(request(wait = WaitPlan.ACCEPTED)).toList() }
        val accepted = results.single()
        accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        accepted.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        eventually { decided.contains(accepted.commandId) }
        registry.size.assert().isEqualTo(0)
        // Closing waits for the detached command, then refuses new requests.
        val closing = async(Dispatchers.Default) { gateway.close(5.seconds) }
        delay(100)
        closing.isCompleted.assert().isFalse()
        gate.complete(Unit)
        withTimeout(5.seconds) { closing.await() }
        executor.activeLanes.assert().isEqualTo(0)
        val refused = gateway.execute(request())
        refused.status.assert().isEqualTo(CommandResult.Status.FAILED)
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        refused.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        // The detached command committed: the next one finds the aggregate at version 1.
        hold = null
        gateway().execute(request()).version.assert().isEqualTo(2)
    }

    @Test
    fun `closing waits for a detached command whose admission was under way`(): Unit = runBlocking {
        // Finding A1-9: the request passed the closed check before close() and launches only after it began.
        val authorizing = CompletableDeferred<Unit>()
        val authorized = CompletableDeferred<Unit>()
        val gateway = gateway(authorizer = { _, _ ->
            authorizing.complete(Unit)
            authorized.await()
            Authorization.Allowed
        })
        val decide = CompletableDeferred<Unit>().also { hold = it }
        val sending = async(Dispatchers.Default) { gateway.execute(request(wait = WaitPlan.ACCEPTED)) }
        authorizing.await()
        val closing = async(Dispatchers.Default) { gateway.close(5.seconds) }
        delay(50)
        closing.isCompleted.assert().isFalse()
        authorized.complete(Unit)
        val accepted = withTimeout(5.seconds) { sending.await() }
        accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { decided.contains(accepted.commandId) }
        delay(50)
        closing.isCompleted.assert().isFalse()
        decide.complete(Unit)
        withTimeout(5.seconds) { closing.await() }
        hold = null
        gateway().execute(request()).version.assert().isEqualTo(2)
    }

    @Test
    fun `a plan the runtime cannot follow is still rejected`(): Unit = runBlocking {
        val gateway = gateway()
        val result = gateway.execute(request(wait = WaitPlan.of(Hop.accepted(), Hop.accepted())))
        result.assertRefused(FrameworkCodes.WAIT_INVALID)
        decided.assert().isEmpty()
    }

    @Test
    fun `a detached command's outcome is only logged`(): Unit = runBlocking {
        val gateway = gateway()
        // Rejected by the kernel: the cart does not exist.
        val missing = gateway.execute(request(command = "Checkout", body = "{}", wait = WaitPlan.ACCEPTED))
        missing.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // A route that fails: nobody waits, so the failure is logged and the scope goes on.
        val failing = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            LocalSignalSender(origin.node, registry),
            origin,
            WaitOptions(),
            router = { _, _, _, _ -> error("unreachable") },
        )
        val throwing = CommandGateway(ModelRegistry(listOf(model)), mapOf(CONTEXT to failing))
        throwing.execute(request(wait = WaitPlan.ACCEPTED)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        throwing.close(5.seconds)
        gateway.close(5.seconds)
        decided.assert().isEmpty()
        runCatching { failing.accept(model, Command("c", "r", model.type.ref(TENANT, "x"), Checkout()), this) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a store unavailable while a command runs fails it as retryable, its retry commits (F2)`(): Unit = runBlocking {
        var down = true
        val failing = object : EventLog by log {
            override suspend fun findByRequestId(ref: AggregateRef, requestId: String) =
                if (down) {
                    throw StorageUnavailableException("The log is unavailable.", IllegalStateException("reset"))
                } else {
                    log.findByRequestId(ref, requestId)
                }
        }
        val coordinator = WaitCoordinator(
            Executor(Kernel(failing)),
            registry,
            SubscriptionDirectory.NONE,
            LocalSignalSender(origin.node, registry),
            origin,
            WaitOptions(),
        )
        val gateway = gateway(coordinator = coordinator)

        val failed = gateway.execute(request(requestId = "add-1"))
        failed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        failed.error!!.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        failed.error!!.message.assert().isEqualTo("The log is unavailable.")
        failed.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        failed.requestId.assert().isEqualTo("add-1")
        decided.assert().isEmpty()

        down = false
        val retried = gateway.execute(request(requestId = "add-1"))
        retried.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        retried.version.assert().isEqualTo(1)
        gateway.execute(request(requestId = "add-1")).duplicate.assert().isTrue()
        decided.assert().hasSize(1)
    }

    @Test
    fun `a store unavailable at admission refuses the request as retryable, before it runs (F2)`(): Unit = runBlocking {
        val committed = CommittedRequests { _, _, _ -> throw StorageUnavailableException("The log is unavailable.") }
        val result = gateway(committed = committed).execute(
            request(command = "OpenCart", body = "{}", aggregateId = null, requestId = "open-1"),
        )
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        result.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        result.requestId.assert().isEqualTo("open-1")
        decided.assert().isEmpty()
    }
}

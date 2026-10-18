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

package me.ahoo.wow.runtime.wait

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test
import java.io.IOException

/** Design 08 §7: the kernel's outcomes as outward results. */
class CommandResultsTest {
    data class Place(val amount: Int)
    data class Unknown(val x: Int)

    private val model = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, _ -> Decision.NoOp }
    }
    private val command: Command<Any> = Command("c-1", "r-1", model.type.ref("t", "o-1"), Place(1))
    private val function = commandFunction(model, command)
    private val hop = Hop.committed("shop")
    private val stream = EventStreamRecord(
        command.ref,
        3,
        "c-1",
        "r-1",
        "shop.order.Place",
        0L,
        listOf(EventRecord("shop.order.Placed", 1, "{}")),
        Scope.NONE,
    )

    private fun map(outcome: CommandOutcome): CommandResult = outcome.toResult(command, function, hop, 42L)

    @Test
    fun `a command's function is its aggregate and its model name`() {
        function.assert().isEqualTo(FunctionInfo(FunctionKind.COMMAND, "shop", "order", "Place"))
        commandFunction(model, command.copyWith(body = Unknown(1))).name.assert().isEqualTo("Unknown")
        subscriptionFunction(FunctionKind.SAGA, "shop", "order-saga.onPlaced")
            .assert().isEqualTo(FunctionInfo(FunctionKind.SAGA, "shop", "order-saga", "onPlaced"))
        subscriptionFunction(FunctionKind.SNAPSHOT, "shop", "snapshot")
            .assert().isEqualTo(FunctionInfo(FunctionKind.SNAPSHOT, "shop", "snapshot", "snapshot"))
    }

    @Test
    fun `committed succeeds with the new version and the results`() {
        val result = map(CommandOutcome.Committed(stream, LogPosition(0, 1), Any(), mapOf("id" to 7)))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.version.assert().isEqualTo(3)
        result.results.assert().isEqualTo(mapOf("id" to 7))
        result.duplicate.assert().isFalse()
        result.correlationId.assert().isEqualTo("c-1")
        result.hop.assert().isEqualTo(hop)
        result.signalTime.assert().isEqualTo(42L)
    }

    @Test
    fun `already committed succeeds as a duplicate without results`() {
        val result = map(CommandOutcome.AlreadyCommitted(stream))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.duplicate.assert().isTrue()
        result.version.assert().isEqualTo(3)
        result.results.assert().isEmpty()
    }

    @Test
    fun `a duplicate answers with the committed stream's aggregate, not the retry's`() {
        // Review R2.1-3: a creation without an id retried before its first id committed was given another one.
        val first = stream.copyWith(ref = model.type.ref("t", "o-first"), version = 1)
        val result = map(CommandOutcome.AlreadyCommitted(first))
        result.ref.assert().isEqualTo(first.ref)
        result.version.assert().isEqualTo(1)
        result.duplicate.assert().isTrue()
        result.commandId.assert().isEqualTo(command.id)
    }

    @Test
    fun `unchanged succeeds at the same version`() {
        map(CommandOutcome.Unchanged(5)).let {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.version.assert().isEqualTo(5)
        }
    }

    @Test
    fun `a rejection, contention or an unknown outcome do not succeed`() {
        map(CommandOutcome.Rejected(Rejection("shop.order.closed", "closed", mapOf("closedAt" to 7)))).let {
            it.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            it.error!!.code.assert().isEqualTo("shop.order.closed")
            it.error!!.extensions.assert().isEqualTo(mapOf("closedAt" to 7))
        }
        map(CommandOutcome.Contended(3)).let {
            it.status.assert().isEqualTo(CommandResult.Status.FAILED)
            it.error!!.code.assert().isEqualTo(ResultCodes.CONTENDED)
        }
        map(CommandOutcome.Unknown(IOException("timed out"))).let {
            it.status.assert().isEqualTo(CommandResult.Status.FAILED)
            it.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
        }
    }

    @Test
    fun `a derived command belongs to its root's chain`() {
        val derived = command.copyWith(causation = Causation("root", "shop.order/t/o-1@1"))
        map(CommandOutcome.Unchanged(1)).correlationId.assert().isEqualTo("c-1")
        CommandOutcome.Unchanged(1).toResult(derived, function, hop, 1L).correlationId.assert().isEqualTo("root")
        stream.copyWith(causation = Causation("root", "x")).correlationId.assert().isEqualTo("root")
    }
}

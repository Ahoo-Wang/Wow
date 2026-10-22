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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Where the in-process sender delivers a signal: to its own registry, or to the remote sender (decision D65). */
class LocalSignalSenderTest {
    private val result = CommandResult(
        commandId = "c-1",
        requestId = "r-1",
        correlationId = "c-1",
        ref = AggregateType("shop", "order").ref("t", "o-1"),
        hop = Hop.committed("shop"),
        function = FunctionInfo(FunctionKind.COMMAND, "shop", "order", "Place"),
        status = CommandResult.Status.SUCCEEDED,
        signalTime = 1L,
    )

    @Test
    fun `a signal for another service's node of the same id goes to the remote sender`(): Unit = runBlocking {
        // Review R2.1-6: node ids are unique within a service only.
        val sent = CopyOnWriteArrayList<String>()
        val sender = LocalSignalSender("node-1", "payment", WaitRegistry()) { origin, signal, _ ->
            sent += "${origin.service}/${origin.node}:${signal.waitId}"
        }
        sender.send(Origin("node-1", "10.0.0.1:9090", "order"), WaitSignal(waitId = "w-7", result = result), DEADLINE)
        sender.send(Origin("node-1", "10.0.0.2:9090", "payment"), WaitSignal(waitId = "w-6", result = result), DEADLINE)
        sent.assert().containsExactly("order/node-1:w-7")
    }

    @Test
    fun `without a service the node id alone decides`(): Unit = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val sender = LocalSignalSender("node-1", WaitRegistry()) { origin, signal, deadline ->
            sent += "${origin.node}:${signal.waitId}@$deadline"
        }
        sender.send(Origin("node-1", "10.0.0.1:9090", "order"), WaitSignal(waitId = "w-5", result = result), DEADLINE)
        sender.send(Origin("node-2", "10.0.0.2:9090", "order"), WaitSignal(waitId = "w-4", result = result), DEADLINE)
        // The remote sender is given the wait's deadline, which bounds its request (roadmap §6 item 1).
        sent.assert().containsExactly("node-2:w-4@$DEADLINE")
    }
}

private const val DEADLINE = 1_700_000_000_000L

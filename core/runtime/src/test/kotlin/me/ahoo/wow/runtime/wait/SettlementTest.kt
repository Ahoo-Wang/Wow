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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** What a settlement tells its callers: the backpressure a command met only when the node refused it as busy. */
class SettlementTest {
    data class Place(val amount: Int)

    private val model = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, _ -> Decision.NoOp }
    }
    private val command: Command<Any> = Command("c-1", "r-1", model.type.ref("t", "o-1"), Place(1))
    private val committed = reachedResult(command, commandFunction(model, command), Hop.committed("shop"), 0L)

    @Test
    fun `only a command refused as busy met backpressure, not one rejected nor one failed otherwise`() {
        val busy = Settlement(committed.failed(ResultCodes.BUSY, "The lane is full.")).busyOf(command)!!
        busy.ref.assert().isEqualTo(command.ref)
        busy.commandId.assert().isEqualTo("c-1")
        busy.message.assert().contains("The lane is full.")
        Settlement(committed.failed(ResultCodes.FAILED, "It threw.")).busyOf(command).assert().isNull()
        val rejected = committed.copyWith(
            status = CommandResult.Status.REJECTED,
            error = ErrorInfo(ResultCodes.BUSY, "A domain code that reads busy."),
        )
        Settlement(rejected).busyOf(command).assert().isNull()
        Settlement(committed).busyOf(command).assert().isNull()
    }
}

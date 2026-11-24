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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import org.junit.jupiter.api.Test

/**
 * A duplicate of the root whose plan names its commit and a later hop: its commit is the hop reached and the wait's
 * end, one result, emitted once.
 */
class DuplicateCommitTwiceTest {
    private val committed = Hop.committed("shop")
    private val audit = Hop.handled("audit", "shop")

    @Test
    fun `a duplicate's commit, a hop of its plan and its end, should be emitted once`() {
        val state = WaitState(listOf(committed, audit), "root")
        val duplicate = CommandResult(
            commandId = "root",
            requestId = "r-root",
            correlationId = "root",
            ref = AggregateType("shop", "order").ref("t", "root"),
            hop = committed,
            function = FunctionInfo(FunctionKind.COMMAND, "shop", "order", "Place"),
            status = CommandResult.Status.SUCCEEDED,
            duplicate = true,
            signalTime = 1L,
        )
        // apply() emits it as the plan's committed hop reached; endAtDuplicate() then adds it again as the outcome.
        state.offer(duplicate)!!.assert().containsExactly(duplicate) // fails: [duplicate, duplicate]
        state.outcome.assert().isEqualTo(duplicate)
    }
}

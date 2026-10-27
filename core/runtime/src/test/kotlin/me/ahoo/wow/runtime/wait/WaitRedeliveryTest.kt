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

import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import org.junit.jupiter.api.Test

/**
 * A signal that lands twice, as a request its sender sent again does (decision D320), ends its wait once: the wait
 * deduplicates it by its hop, command and source (design 08 §8).
 */
class WaitRedeliveryTest {
    @Test
    fun `a signal delivered twice ends its wait once`(): Unit = runBlocking {
        val registry = WaitRegistry()
        val waiting = checkNotNull(registry.register("w-1", listOf(Hop.committed("shop")), "c-1"))
        val signal = WaitSignal(waitId = "w-1", result = committed())
        registry.signal(signal).assert().isTrue()
        registry.signal(signal).assert().isFalse()
        waiting.results.toList().assert().containsExactly(committed())
    }

    private fun committed() = CommandResult(
        commandId = "c-1",
        requestId = "r-1",
        correlationId = "c-1",
        ref = AggregateType("shop", "order").ref("t", "o-1"),
        hop = Hop.committed("shop"),
        function = FunctionInfo(FunctionKind.COMMAND, "shop", "order", "Place"),
        status = CommandResult.Status.SUCCEEDED,
        signalTime = 1L,
    )
}

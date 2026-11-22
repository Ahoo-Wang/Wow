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

package me.ahoo.wow.test.server

import kotlinx.coroutines.delay
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class AssertRejectedTest {
    private fun result(status: CommandResult.Status) = CommandResult(
        commandId = "c",
        requestId = "r",
        correlationId = "c",
        ref = AggregateType("bank", "account").ref("t", "alice"),
        hop = Hop.committed("bank"),
        function = FunctionInfo(FunctionKind.COMMAND, "bank", "account", "Withdraw"),
        status = status,
        error = ErrorInfo.of("bank.account.insufficient-balance", "Insufficient.").takeIf {
            status != CommandResult.Status.SUCCEEDED
        },
        signalTime = 0,
    )

    @Test
    fun `a rejection should be returned, what it sent suspending first`() {
        val rejection = CommandRejectedException(result(CommandResult.Status.REJECTED))
        assertRejected {
            delay(1.milliseconds)
            throw rejection
        }.assert().isSameAs(rejection)
    }

    @Test
    fun `a command answered without a rejection should fail the assertion`() {
        assertThrows<AssertionError> { assertRejected { result(CommandResult.Status.SUCCEEDED) } }
            .message.assert().isEqualTo("Not rejected: the command was answered without a rejection.")
    }

    @Test
    fun `a command that failed otherwise should fail the assertion, its failure the cause`() {
        val failure = IllegalStateException("Down.")
        val thrown = assertThrows<AssertionError> { assertRejected { throw failure } }
        thrown.message.assert().isEqualTo("Not rejected: the command failed with java.lang.IllegalStateException.")
        thrown.cause.assert().isSameAs(failure)
    }

    @Test
    fun `a cancellation should be thrown as it is`() {
        val cancelled = CancellationException("Stop.")
        assertThrows<CancellationException> { assertRejected { throw cancelled } }.assert().isSameAs(cancelled)
    }
}

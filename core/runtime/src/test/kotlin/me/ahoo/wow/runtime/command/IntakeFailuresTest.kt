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

package me.ahoo.wow.runtime.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.runtime.subscription.RuntimeFailures
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.failed
import me.ahoo.wow.runtime.wait.reachedResult
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test

/** How the intake's subscription classifies a command that settled FAILED, by its code as a saga's command is. */
class IntakeFailuresTest {
    private val command = Command("c-1", "r-1", ITEM.ref(TENANT, "i-1"), Reserve(1))

    private fun failed(code: String, cause: Throwable? = null): Settlement {
        val function = FunctionInfo(FunctionKind.COMMAND, STOCK, "item", "Reserve")
        val result = reachedResult(command, function, Hop.committed(STOCK), 0L).failed(code, "$code happened")
        return Settlement(result, cause = cause)
    }

    private fun classOf(settlement: Settlement): FailureClass =
        RuntimeFailures.DEFAULT.classify(failureOf(command.id, command.ref, settlement), null)

    @Test
    fun `a store that is unavailable is systemic, whether or not the refusal carries its cause`() {
        // A refusal from the owner over the cluster channel carries only its code.
        classOf(failed(ResultCodes.STORAGE_UNAVAILABLE)).assert().isEqualTo(FailureClass.SYSTEMIC)
        val local = failed(ResultCodes.STORAGE_UNAVAILABLE, StorageUnavailableException("the log is down"))
        classOf(local).assert().isEqualTo(FailureClass.SYSTEMIC)
        failureOf(command.id, command.ref, local).cause.assert().isInstanceOf(StorageUnavailableException::class.java)
    }

    @Test
    fun `a wait that ran out is backpressure, and a failed handling recoverable`() {
        classOf(failed(ResultCodes.WAIT_TIMEOUT)).assert().isEqualTo(FailureClass.BACKPRESSURE)
        classOf(failed(ResultCodes.FAILED)).assert().isEqualTo(FailureClass.RECOVERABLE)
        failureOf(command.id, command.ref, failed(ResultCodes.FAILED)).message.assert()
            .isEqualTo("The command c-1 on ${command.ref} failed: ${ResultCodes.FAILED} ${ResultCodes.FAILED} happened")
    }
}

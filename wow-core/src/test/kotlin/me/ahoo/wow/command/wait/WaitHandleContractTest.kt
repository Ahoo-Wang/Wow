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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class WaitHandleContractTest {
    @Test
    fun lastHandleImplementsCommonHandleContract() {
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        val waitHandle: WaitHandle = handle

        waitHandle.plan.assert().isEqualTo(handle.plan)
        waitHandle.waitCommandId.assert().isEqualTo("wait-id")
        waitHandle.next(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id"))
            .assert().isTrue()
        waitHandle.cancel()
    }

    @Test
    fun streamHandleImplementsCommonHandleContract() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        val waitHandle: WaitHandle = handle

        waitHandle.plan.assert().isEqualTo(handle.plan)
        waitHandle.waitCommandId.assert().isEqualTo("wait-id")
        waitHandle.next(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id"))
            .assert().isTrue()
        waitHandle.cancel()
    }
}

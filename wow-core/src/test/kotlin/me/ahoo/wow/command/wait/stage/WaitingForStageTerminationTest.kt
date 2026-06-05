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

package me.ahoo.wow.command.wait.stage

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class WaitingForStageTerminationTest {

    @Test
    fun `error terminates waiting flux with error`() {
        val strategy = WaitingForStage.processed("wait-id")
        val error = IllegalStateException("boom")

        StepVerifier.create(strategy.waiting())
            .then { strategy.error(error) }
            .expectErrorMatches { it === error }
            .verify()

        strategy.terminated.assert().isTrue()
    }

    @Test
    fun `failed prerequisite signal completes strategy early`() {
        val strategy = WaitingForStage.snapshot("wait-id")
        val failedProcessed = testSignal(CommandStage.PROCESSED, errorCode = "FAILED")

        StepVerifier.create(strategy.waiting())
            .then { strategy.next(failedProcessed) }
            .expectNext(failedProcessed)
            .verifyComplete()

        strategy.terminated.assert().isTrue()
    }
}

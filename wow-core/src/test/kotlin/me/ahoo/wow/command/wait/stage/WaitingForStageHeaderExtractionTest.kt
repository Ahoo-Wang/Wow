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
import me.ahoo.wow.command.wait.propagateWaitFunction
import me.ahoo.wow.command.wait.propagateWaitingStage
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.extractWaitingForStage
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class WaitingForStageHeaderExtractionTest {

    @Test
    fun `missing stage header extracts null`() {
        DefaultHeader.empty().extractWaitingForStage().assert().isNull()
    }

    @Test
    fun `stage header extracts stage and function criteria`() {
        val function = testNamedFunction()
        val header = DefaultHeader.empty()
            .propagateWaitingStage(CommandStage.PROJECTED)
            .propagateWaitFunction(function)

        val materialized = header.extractWaitingForStage()!!

        materialized.stage.assert().isEqualTo(CommandStage.PROJECTED)
        materialized.function.assert().isEqualTo(function)
    }
}

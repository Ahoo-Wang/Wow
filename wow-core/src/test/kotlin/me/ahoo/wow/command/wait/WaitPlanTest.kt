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

class WaitPlanTest {
    @Test
    fun stageFactoriesShouldCreateImmutablePlans() {
        CommandWait.sent("wait-id").let { plan ->
            plan.waitCommandId.assert().isEqualTo("wait-id")
            plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.SENT))
            plan.supportVoidCommand.assert().isTrue()
        }
        CommandWait.processed("wait-id").let { plan ->
            plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.PROCESSED))
            plan.supportVoidCommand.assert().isFalse()
        }
        CommandWait.snapshot("wait-id").target.assert().isEqualTo(StageWaitTarget(CommandStage.SNAPSHOT))
        CommandWait.projected("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION).target.assert()
            .isEqualTo(StageWaitTarget(CommandStage.PROJECTED, testNamedFunction()))
        CommandWait.eventHandled("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION).target.assert()
            .isEqualTo(StageWaitTarget(CommandStage.EVENT_HANDLED, testNamedFunction()))
        CommandWait.sagaHandled("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION).target.assert()
            .isEqualTo(StageWaitTarget(CommandStage.SAGA_HANDLED, testNamedFunction()))
    }
}

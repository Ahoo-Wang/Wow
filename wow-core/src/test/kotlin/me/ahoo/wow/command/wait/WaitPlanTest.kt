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
import me.ahoo.wow.command.wait.chain.ChainWaitState
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.stage.StageWaitState
import me.ahoo.wow.messaging.DefaultHeader
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

    @Test
    fun stageFactoryShouldDelegateEveryEnumStage() {
        CommandStage.entries.forEach { stage ->
            val plan = CommandWait.stage(
                waitCommandId = "wait-id",
                stage = stage,
                contextName = TEST_CONTEXT,
                processorName = TEST_PROCESSOR,
                functionName = TEST_FUNCTION,
            )

            plan.assert().isEqualTo(expectedStagePlan(stage))
        }
    }

    @Test
    fun stringStageFactoryShouldNormalizeStageName() {
        val plan = CommandWait.stage(
            waitCommandId = "wait-id",
            stage = "projected",
            contextName = TEST_CONTEXT,
            processorName = TEST_PROCESSOR,
            functionName = TEST_FUNCTION,
        )

        plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.PROJECTED, testNamedFunction()))
    }

    @Test
    fun stageFactoriesShouldSupportDefaultSelectors() {
        CommandWait.stage("wait-id", CommandStage.SNAPSHOT)
            .target.assert().isEqualTo(StageWaitTarget(CommandStage.SNAPSHOT))
        CommandWait.stage("wait-id", "snapshot")
            .target.assert().isEqualTo(StageWaitTarget(CommandStage.SNAPSHOT))
    }

    @Test
    fun waitPlanShouldPropagateHeaders() {
        val header = DefaultHeader.empty()
        val endpoint = SimpleCommandWaitEndpoint(TEST_ENDPOINT)
        val waitPlan: WaitPlan = CommandWait.projected("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION)

        waitPlan.propagate(endpoint, header)

        header[WAIT_COMMAND_ID].assert().isEqualTo("wait-id")
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        header[COMMAND_WAIT_STAGE].assert().isEqualTo(CommandStage.PROJECTED.name)
        header.extractWaitFunction().assert().isEqualTo(testNamedFunction())
    }

    @Test
    fun functionStageFactoriesShouldSupportDefaultSelectors() {
        CommandWait.projected("wait-id", TEST_CONTEXT).target.assert()
            .isEqualTo(
                StageWaitTarget(
                    CommandStage.PROJECTED,
                    testNamedFunction(processorName = "", name = ""),
                )
            )
        CommandWait.eventHandled("wait-id", TEST_CONTEXT).target.assert()
            .isEqualTo(
                StageWaitTarget(
                    CommandStage.EVENT_HANDLED,
                    testNamedFunction(processorName = "", name = ""),
                )
            )
        CommandWait.sagaHandled("wait-id", TEST_CONTEXT).target.assert()
            .isEqualTo(
                StageWaitTarget(
                    CommandStage.SAGA_HANDLED,
                    testNamedFunction(processorName = "", name = ""),
                )
            )
    }

    @Test
    fun chainFactoriesShouldCreateEquivalentTargets() {
        val tailFunction = testNamedFunction(name = "tail-function")
        val tail = CommandStage.PROJECTED.toWaitingChainTail(tailFunction)

        val plan = CommandWait.chain("wait-id", testNamedFunction(), tail)
        val delegated = CommandWait.chain("wait-id", testNamedFunction(), CommandStage.PROJECTED, tailFunction)

        plan.target.assert().isInstanceOf(ChainWaitTarget::class.java)
        delegated.target.assert().isInstanceOf(ChainWaitTarget::class.java)
        val target = plan.target as ChainWaitTarget
        val delegatedTarget = delegated.target as ChainWaitTarget
        target.function.assert().isEqualTo(testNamedFunction())
        delegatedTarget.function.assert().isEqualTo(target.function)
        delegatedTarget.tail.stage.assert().isEqualTo(target.tail.stage)
        delegatedTarget.tail.function.assert().isEqualTo(target.tail.function)
    }

    @Test
    fun waitTargetsShouldFilterByStageAndFunction() {
        val projectedTarget = StageWaitTarget(CommandStage.PROJECTED, testNamedFunction())

        projectedTarget.shouldNotify(CommandStage.PROCESSED).assert().isTrue()
        projectedTarget.shouldNotify(testSignal(CommandStage.SENT)).assert().isTrue()
        projectedTarget.shouldNotify(testSignal(CommandStage.SNAPSHOT)).assert().isFalse()
        projectedTarget.shouldNotify(testSignal(CommandStage.PROJECTED, function = testFunction())).assert().isTrue()
        projectedTarget.shouldNotify(
            testSignal(CommandStage.PROJECTED, function = testFunction(name = "other-function"))
        ).assert().isFalse()
        StageWaitTarget(CommandStage.PROCESSED, testNamedFunction())
            .shouldNotify(testSignal(CommandStage.PROCESSED, function = testFunction(name = "other-function")))
            .assert().isTrue()
        StageWaitTarget(CommandStage.PROCESSED)
            .shouldNotify(testSignal(CommandStage.PROCESSED, function = testFunction(name = "other-function")))
            .assert().isTrue()
    }

    @Test
    fun createWaitStateShouldMatchPlanTarget() {
        createWaitState(CommandWait.processed("wait-id")).assert().isInstanceOf(StageWaitState::class.java)
        createWaitState(
            CommandWait.chain(
                waitCommandId = "wait-id",
                function = testNamedFunction(),
                tailStage = CommandStage.PROCESSED,
                tailFunction = testNamedFunction(name = "tail-function"),
            )
        ).assert().isInstanceOf(ChainWaitState::class.java)
    }

    private fun expectedStagePlan(stage: CommandStage): WaitPlan =
        when (stage) {
            CommandStage.SENT -> CommandWait.sent("wait-id")
            CommandStage.PROCESSED -> CommandWait.processed("wait-id")
            CommandStage.SNAPSHOT -> CommandWait.snapshot("wait-id")
            CommandStage.PROJECTED -> CommandWait.projected(
                "wait-id",
                TEST_CONTEXT,
                TEST_PROCESSOR,
                TEST_FUNCTION,
            )
            CommandStage.EVENT_HANDLED -> CommandWait.eventHandled(
                "wait-id",
                TEST_CONTEXT,
                TEST_PROCESSOR,
                TEST_FUNCTION,
            )
            CommandStage.SAGA_HANDLED -> CommandWait.sagaHandled(
                "wait-id",
                TEST_CONTEXT,
                TEST_PROCESSOR,
                TEST_FUNCTION,
            )
        }
}

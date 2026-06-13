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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.SIMPLE_CHAIN
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.propagateWaitingChainTail
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class WaitPlanHeaderExtractionTest {
    @Test
    fun extractStagePlan() {
        val header = DefaultHeader.empty()
        CommandWait.projected("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION)
            .propagate(TestCommandWaitEndpoint, header)

        val extracted = header.extractWaitPlan()!!

        extracted.endpoint.assert().isEqualTo(TEST_ENDPOINT)
        extracted.waitCommandId.assert().isEqualTo("wait-id")
        extracted.plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.PROJECTED, testNamedFunction()))
    }

    @Test
    fun extractChainPlan() {
        val header = DefaultHeader.empty()
        val tailFunction = testNamedFunction(name = "tail-function")

        CommandWait.chain("wait-id", testNamedFunction(), CommandStage.PROJECTED, tailFunction)
            .propagate(TestCommandWaitEndpoint, header)

        val extracted = header.extractWaitPlan()!!

        extracted.waitCommandId.assert().isEqualTo("wait-id")
        extracted.plan.target.assertChainTarget(
            function = testNamedFunction(),
            tailStage = CommandStage.PROJECTED,
            tailFunction = tailFunction,
        )
    }

    @Test
    fun extractLegacyFullSimpleChainPlan() {
        val tailFunction = testNamedFunction(name = "tail-function")
        val header = DefaultHeader.empty()
            .propagateWaitCommandId("wait-id")
            .propagateCommandWaitEndpoint(TEST_ENDPOINT)
            .propagateWaitFunction(testNamedFunction())
            .with(COMMAND_WAIT_CHAIN, SIMPLE_CHAIN)
            .propagateWaitingChainTail(CommandStage.PROJECTED, tailFunction)

        val extracted = header.extractWaitPlan()!!

        header.containsKey(COMMAND_WAIT_STAGE).assert().isFalse()
        extracted.waitCommandId.assert().isEqualTo("wait-id")
        extracted.plan.target.assertChainTarget(
            function = testNamedFunction(),
            tailStage = CommandStage.PROJECTED,
            tailFunction = tailFunction,
        )
    }

    @Test
    fun extractLegacyTailOnlyPlan() {
        val tailFunction = testNamedFunction(name = "tail-function")
        val header = DefaultHeader.empty()
            .propagateWaitCommandId("wait-id")
            .propagateCommandWaitEndpoint(TEST_ENDPOINT)
            .propagateWaitingChainTail(CommandStage.PROJECTED, tailFunction)

        val extracted = header.extractWaitPlan()!!

        header.containsKey(COMMAND_WAIT_STAGE).assert().isFalse()
        extracted.waitCommandId.assert().isEqualTo("wait-id")
        extracted.plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.PROJECTED, tailFunction))
    }

    @Test
    fun propagateChainPlanFromCommandUpstream() {
        val tailFunction = testNamedFunction(name = "tail-function")
        val extracted = ExtractedWaitPlan(
            endpoint = TEST_ENDPOINT,
            waitCommandId = "wait-id",
            plan = CommandWait.chain("wait-id", testNamedFunction(), CommandStage.PROJECTED, tailFunction),
        )
        val header = DefaultHeader.empty()

        extracted.propagate(header, TestCommandMessage())

        header[WAIT_COMMAND_ID].assert().isEqualTo("wait-id")
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        header[COMMAND_WAIT_CHAIN].assert().isEqualTo(SIMPLE_CHAIN)
        header.containsKey(COMMAND_WAIT_STAGE).assert().isFalse()
        header[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROJECTED.name)
        header.extractWaitPlan()!!.plan.target.assertChainTarget(
            function = testNamedFunction(),
            tailStage = CommandStage.PROJECTED,
            tailFunction = tailFunction,
        )
    }

    @Test
    fun propagateChainPlanFromNonCommandUpstreamAsTailOnly() {
        val tailFunction = testNamedFunction(name = "tail-function")
        val extracted = ExtractedWaitPlan(
            endpoint = TEST_ENDPOINT,
            waitCommandId = "wait-id",
            plan = CommandWait.chain("wait-id", testNamedFunction(), CommandStage.PROJECTED, tailFunction),
        )
        val header = DefaultHeader.empty()

        extracted.propagate(header, TestDomainEvent())

        header[WAIT_COMMAND_ID].assert().isEqualTo("wait-id")
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        header.containsKey(COMMAND_WAIT_CHAIN).assert().isFalse()
        header.containsKey(COMMAND_WAIT_STAGE).assert().isFalse()
        header[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROJECTED.name)
        header.extractWaitPlan()!!.plan.target.assert().isEqualTo(StageWaitTarget(CommandStage.PROJECTED, tailFunction))
    }

    private object TestCommandWaitEndpoint : CommandWaitEndpoint {
        override val endpoint: String = TEST_ENDPOINT
    }

    private fun WaitTarget.assertChainTarget(
        function: NamedFunctionInfoData,
        tailStage: CommandStage,
        tailFunction: NamedFunctionInfoData,
    ) {
        (this is ChainWaitTarget).assert().isTrue()
        val chainTarget = this as ChainWaitTarget
        chainTarget.function.assert().isEqualTo(function)
        chainTarget.tail.stage.assert().isEqualTo(tailStage)
        chainTarget.tail.function.assert().isEqualTo(tailFunction)
    }
}

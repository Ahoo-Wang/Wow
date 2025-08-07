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

package me.ahoo.wow.command.wait.chain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SimpleWaitingForChainTest {

    private fun createTestSignal(
        commandId: String = generateGlobalId(),
        waitCommandId: String = generateGlobalId(),
        stage: CommandStage = CommandStage.SAGA_HANDLED,
        commands: List<String> = listOf(),
        errorInfo: ErrorInfo = ErrorInfo.OK,
        function: FunctionInfoData = FunctionInfoData(
            functionKind = FunctionKind.COMMAND,
            contextName = "test-context",
            processorName = "test-processor",
            name = "test-function"
        )
    ): WaitSignal {
        return SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitCommandId,
            commandId = commandId,
            aggregateId = DefaultAggregateId(
                namedAggregate = MaterializedNamedAggregate("test-context", "test-aggregate"),
                id = generateGlobalId(),
                tenantId = TenantId.DEFAULT_TENANT_ID
            ),
            stage = stage,
            function = function,
            commands = commands,
            errorCode = errorInfo.errorCode,
            errorMsg = errorInfo.errorMsg,
            bindingErrors = errorInfo.bindingErrors
        )
    }

    @Test
    fun constructor() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        waitingForChain.waitCommandId.assert().isEqualTo(waitCommandId)
        waitingForChain.materialized.assert().isEqualTo(chain)
    }

    @Test
    fun isPreviousSignal() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        val signal = createTestSignal(waitCommandId = waitCommandId)
        waitingForChain.isPreviousSignal(signal).assert().isTrue()
    }

    @Test
    fun waiting() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then { waitingForChain.complete() }
            .expectComplete()
            .verify()
    }

    @Test
    fun waitingLast() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        val signal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.SAGA_HANDLED
        )

        waitingForChain.waitingLast()
            .test()
            .expectSubscription()
            .then {
                waitingForChain.next(signal)
                waitingForChain.complete()
            }
            .expectNext(signal)
            .expectComplete()
            .verify()
    }

    @Test
    fun waitingLastEmpty() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        waitingForChain.waitingLast()
            .test()
            .expectSubscription()
            .then { waitingForChain.complete() }
            .expectComplete()
            .verify()
    }

    @Test
    fun nextWithMainSignal() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        val signal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.SAGA_HANDLED
        )

        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then { waitingForChain.next(signal) }
            .expectNext(signal)
            .then { waitingForChain.complete() }
            .expectComplete()
            .verify()
    }

    @Test
    fun nextWithMainErrorSignal() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        val signal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.PROCESSED,
            errorInfo = ErrorInfo.of("error-code", "error-msg")
        )

        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then { waitingForChain.next(signal) }
            .expectNext(signal)
            .expectComplete()
            .verify()
    }

    @Test
    fun nextWithTailSignal() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        // First send main signal to initialize tail waiting
        val mainProcessedSignal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.PROCESSED,
        )
        val mainSagaSignal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.SAGA_HANDLED,
            commands = listOf("tail-command-id"),
            function = FunctionInfoData(
                functionKind = FunctionKind.EVENT,
                contextName = function.contextName,
                processorName = function.processorName,
                name = function.name
            )
        )

        val tailSignal = createTestSignal(
            commandId = "tail-command-id",
            waitCommandId = "tail-command-id",
            stage = CommandStage.PROCESSED,
            function = mainSagaSignal.function
        )

        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then { waitingForChain.next(mainProcessedSignal) }
            .expectNext(mainProcessedSignal)
            .then { waitingForChain.next(mainSagaSignal) }
            .expectNext(mainSagaSignal)
            .then { waitingForChain.next(tailSignal) }
            .expectNext(tailSignal)
            .expectComplete()
            .verify()
    }

    @Test
    fun nextWithTailErrorSignal() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        // First send main signal to initialize tail waiting
        val mainProcessedSignal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.PROCESSED,
        )
        val mainSagaSignal = createTestSignal(
            commandId = waitCommandId,
            waitCommandId = waitCommandId,
            stage = CommandStage.SAGA_HANDLED,
            commands = listOf("tail-command-id"),
            function = FunctionInfoData(
                functionKind = FunctionKind.EVENT,
                contextName = function.contextName,
                processorName = function.processorName,
                name = function.name
            )
        )

        val tailSignal = createTestSignal(
            commandId = "tail-command-id",
            waitCommandId = "tail-command-id",
            stage = CommandStage.PROCESSED,
            function = mainSagaSignal.function,
            errorInfo = ErrorInfo.of("error-code", "error-msg")
        )

        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then { waitingForChain.next(mainProcessedSignal) }
            .expectNext(mainProcessedSignal)
            .then { waitingForChain.next(mainSagaSignal) }
            .expectNext(mainSagaSignal)
            .then { waitingForChain.next(tailSignal) }
            .expectNext(tailSignal)
            .expectComplete()
            .verify()
    }

    @Test
    fun chain() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tailStage = CommandStage.PROCESSED
        val tailFunction = NamedFunctionInfoData("tail-context", "tail-processor", "tail-function")

        val waitingForChain = SimpleWaitingForChain.chain(
            waitCommandId = waitCommandId,
            function = function,
            tailStage = tailStage,
            tailFunction = tailFunction
        )

        waitingForChain.waitCommandId.assert().isEqualTo(waitCommandId)
        waitingForChain.materialized.function.assert().isEqualTo(function)
        waitingForChain.materialized.tail.stage.assert().isEqualTo(tailStage)
        waitingForChain.materialized.tail.function.assert().isEqualTo(NamedFunctionInfoData.EMPTY)
    }

    @Test
    fun tailWaitingCompletedWhenMainWaitingSignalIsNull() {
        val waitCommandId = generateGlobalId()
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val waitingForChain = SimpleWaitingForChain(waitCommandId, chain)

        // tailWaitingCompleted should return false when mainWaitingSignal is null
        // We can't directly test this private method, but we can verify the behavior
        // by checking that the chain doesn't complete when there's no main signal
        waitingForChain.waiting()
            .test()
            .expectSubscription()
            .then {
                val tailSignal = createTestSignal(
                    commandId = "tail-command-id",
                    waitCommandId = "tail-command-id",
                    stage = CommandStage.PROCESSED
                )
                waitingForChain.next(tailSignal)
            }
            .thenCancel()
            .verify()
    }
}

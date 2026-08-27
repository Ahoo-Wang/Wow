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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.DefaultAggregateId
import reactor.core.publisher.Mono

internal const val TEST_CONTEXT = "test-context"
internal const val TEST_AGGREGATE = "test-aggregate"
internal const val TEST_PROCESSOR = "test-processor"
internal const val TEST_FUNCTION = "test-function"
internal const val TEST_ENDPOINT = "test-endpoint"

internal object TestNamedAggregate : NamedAggregate {
    override val contextName: String = TEST_CONTEXT
    override val aggregateName: String = TEST_AGGREGATE
}

internal data class WaitTestCommand(
    val aggregateId: String = "aggregate-id",
)

internal data class WaitTestEvent(
    val value: String = "event",
)

internal fun testAggregateId(id: String = "aggregate-id"): AggregateId =
    DefaultAggregateId(TestNamedAggregate, id)

internal fun testFunction(
    kind: FunctionKind = FunctionKind.EVENT,
    contextName: String = TEST_CONTEXT,
    processorName: String = TEST_PROCESSOR,
    name: String = TEST_FUNCTION,
): FunctionInfoData =
    FunctionInfoData(
        functionKind = kind,
        contextName = contextName,
        processorName = processorName,
        name = name,
    )

internal fun testNamedFunction(
    contextName: String = TEST_CONTEXT,
    processorName: String = TEST_PROCESSOR,
    name: String = TEST_FUNCTION,
): NamedFunctionInfoData =
    NamedFunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = name,
    )

internal data class TestCommandMessage(
    override val id: String = generateGlobalId(),
    override val requestId: String = id,
    override val body: WaitTestCommand = WaitTestCommand(),
    override val header: Header = DefaultHeader.empty(),
    override val aggregateId: AggregateId = testAggregateId(body.aggregateId),
    override val aggregateVersion: Int? = null,
    override val isCreate: Boolean = false,
    override val allowCreate: Boolean = false,
    override val isVoid: Boolean = false,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override val spaceId: String = SpaceIdCapable.DEFAULT_SPACE_ID,
    override val createTime: Long = 1,
) : CommandMessage<WaitTestCommand> {
    override val contextName: String
        get() = aggregateId.contextName
    override val aggregateName: String
        get() = aggregateId.aggregateName
    override val name: String = "WaitTestCommand"

    override fun copy(): CommandMessage<WaitTestCommand> = copy(header = header.copy())
}

internal data class TestDomainEvent(
    override val id: String = generateGlobalId(),
    override val commandId: String = generateGlobalId(),
    override val body: WaitTestEvent = WaitTestEvent(),
    override val header: Header = DefaultHeader.empty(),
    override val aggregateId: AggregateId = testAggregateId(),
    override val version: Int = 1,
    override val isLast: Boolean = true,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override val spaceId: String = SpaceIdCapable.DEFAULT_SPACE_ID,
    override val createTime: Long = 1,
) : DomainEvent<WaitTestEvent> {
    override val contextName: String
        get() = aggregateId.contextName
    override val aggregateName: String
        get() = aggregateId.aggregateName
    override val name: String = "WaitTestEvent"
}

internal fun waitPlanHeader(
    waitCommandId: String = "wait-command-id",
    endpoint: String = TEST_ENDPOINT,
    stage: CommandStage,
    function: NamedFunctionInfoData? = null,
): Header =
    DefaultHeader.empty()
        .propagateWaitCommandId(waitCommandId)
        .propagateCommandWaitEndpoint(endpoint)
        .propagateWaitingStage(stage)
        .propagateWaitFunction(function)

internal fun testSignal(
    stage: CommandStage,
    waitCommandId: String = "wait-command-id",
    commandId: String = waitCommandId,
    function: FunctionInfoData = testFunction(),
    result: Map<String, Any> = emptyMap(),
    signalTime: Long = 1,
    isLastProjection: Boolean = false,
    errorCode: String = "Ok",
    errorMsg: String = "",
    commands: List<String> = emptyList(),
): WaitSignal =
    SimpleWaitSignal(
        id = generateGlobalId(),
        waitCommandId = waitCommandId,
        commandId = commandId,
        aggregateId = testAggregateId(),
        stage = stage,
        function = function,
        isLastProjection = isLastProjection,
        errorCode = errorCode,
        errorMsg = errorMsg,
        result = result,
        signalTime = signalTime,
        commands = commands,
    )

internal class RecordingCommandWaitNotifier : CommandWaitNotifier {
    data class Notification(
        val endpoint: String,
        val signal: WaitSignal,
    )

    val notifications: MutableList<Notification> = mutableListOf()

    override fun notify(
        commandWaitEndpoint: String,
        waitSignal: WaitSignal,
    ): Mono<Void> =
        Mono.fromRunnable {
            notifications += Notification(commandWaitEndpoint, waitSignal)
        }
}

internal fun testCommandExchange(
    waitCommandId: String = "wait-command-id",
    stage: CommandStage = CommandStage.PROCESSED,
    function: NamedFunctionInfoData? = null,
): SimpleServerCommandExchange<WaitTestCommand> =
    SimpleServerCommandExchange(
        TestCommandMessage(
            id = "command-id",
            header = waitPlanHeader(
                waitCommandId = waitCommandId,
                stage = stage,
                function = function,
            ),
        ),
    )

@Suppress("UNCHECKED_CAST")
internal fun testDomainEventExchange(
    waitCommandId: String = "wait-command-id",
    commandId: String = "command-id",
    stage: CommandStage,
    function: NamedFunctionInfoData? = null,
    isLast: Boolean = true,
): DomainEventExchange<Any> =
    SimpleDomainEventExchange(
        TestDomainEvent(
            commandId = commandId,
            header = waitPlanHeader(
                waitCommandId = waitCommandId,
                stage = stage,
                function = function,
            ),
            isLast = isLast,
        ),
    ) as DomainEventExchange<Any>

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

package me.ahoo.wow.tck.wire

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate

/**
 * Deterministic messages for the v9 wire-format golden tests.
 *
 * Every id, timestamp and header value is fixed, so serializing a sample gives the same bytes on every run. The headers
 * are stamped the way the 9.2.x runtime stamps them: request headers and `local_first` and the operator from the web
 * edge, `trace_id` from command creation, `command_wait_*` from a real [WaitPlan] propagated by the gateway, and the
 * event-side headers from the SPI propagators (`MessagePropagatorProvider`).
 *
 * The golden files built from these samples are frozen v9 wire contracts (design WP G1); see the golden tests in
 * wow-core, wow-kafka, wow-redis and wow-spring-boot-starter.
 */
object WireSamples {
    const val COMMAND_ID = "0V7tYbLz0001001"
    const val REQUEST_ID = "0V7tYbLz0001002"
    const val AGGREGATE_ID = "0V7tYbLz0001003"
    const val WAIT_COMMAND_ID = "0V7tYbLz0001004"
    const val EVENT_STREAM_ID = "0V7tYbLz0001005"
    const val CREATED_EVENT_ID = "0V7tYbLz0001006"
    const val CHANGED_EVENT_ID = "0V7tYbLz0001007"
    const val WAIT_SIGNAL_ID = "0V7tYbLz0001008"
    const val SAGA_COMMAND_ID = "0V7tYbLz0001009"
    const val TENANT_ID = "tenant-wire"
    const val OWNER_ID = "owner-wire"
    const val SPACE_ID = "space-wire"
    const val OPERATOR = "operator-wire"
    const val USER_AGENT = "wire-agent/1.0"
    const val REMOTE_IP = "10.0.0.7"
    const val COMMAND_WAIT_ENDPOINT = "http://10.0.0.8:8080/wow/command/wait"
    const val COMMAND_CREATE_TIME = 1_759_536_000_000L
    const val EVENT_CREATE_TIME = 1_759_536_000_100L
    const val SIGNAL_TIME = 1_759_536_000_200L

    /** The wait plan the gateway propagates on [commandMessage]: wait for a named projection. */
    val stageWaitPlan: WaitPlan
        get() = CommandWait.projected(
            waitCommandId = WAIT_COMMAND_ID,
            contextName = "wire-context",
            processorName = "WireProjector",
            functionName = "onEvent",
        )

    /** The wait plan the gateway propagates on [chainWaitCommandMessage]: a saga chain ending in a snapshot. */
    val chainWaitPlan: WaitPlan
        get() = CommandWait.chain(
            waitCommandId = WAIT_COMMAND_ID,
            function = NamedFunctionInfoData("wire-context", "WireSaga", "onEvent"),
            tailStage = CommandStage.SNAPSHOT,
            tailFunction = NamedFunctionInfoData("", "", ""),
        )

    /** A create command as the web edge and the gateway send it, waiting for a named projection. */
    fun commandMessage(): CommandMessage<MockCreateAggregate> = commandMessage(stageWaitPlan)

    /** The same command waiting on a saga chain, which adds the `command_wait_chain` and `command_wait_tail_*` keys. */
    fun chainWaitCommandMessage(): CommandMessage<MockCreateAggregate> = commandMessage(chainWaitPlan)

    private fun commandMessage(waitPlan: WaitPlan): CommandMessage<MockCreateAggregate> {
        val header = DefaultHeader.empty()
            .withUserAgent(USER_AGENT)
            .withRemoteIp(REMOTE_IP)
            .withLocalFirst(false)
            .withOperator(OPERATOR)
        val command = MockCreateAggregate(id = AGGREGATE_ID, data = "wire").toCommandMessage(
            id = COMMAND_ID,
            requestId = REQUEST_ID,
            aggregateId = AGGREGATE_ID,
            tenantId = TENANT_ID,
            ownerId = OWNER_ID,
            spaceId = SPACE_ID,
            namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
            header = header,
            createTime = COMMAND_CREATE_TIME,
        )
        waitPlan.propagate(SimpleCommandWaitEndpoint(COMMAND_WAIT_ENDPOINT), command.header)
        return command
    }

    /** The event stream the aggregate appends for [commandMessage], with headers propagated from the command. */
    fun domainEventStream(): DomainEventStream {
        val command = commandMessage()
        val header = DefaultHeader.empty().propagate(command)
        val events = listOf(
            MockAggregateCreated("wire"),
            MockAggregateChanged("wire-changed"),
        ).mapIndexed { index, body ->
            body.toDomainEvent(
                aggregateId = command.aggregateId,
                commandId = command.commandId,
                id = if (index == 0) CREATED_EVENT_ID else CHANGED_EVENT_ID,
                version = 1,
                ownerId = command.ownerId,
                spaceId = command.spaceId,
                sequence = index + 1,
                isLast = index == 1,
                header = header.copy(),
                createTime = EVENT_CREATE_TIME,
            )
        }
        return SimpleDomainEventStream(
            id = EVENT_STREAM_ID,
            requestId = command.requestId,
            header = header,
            body = events,
        )
    }

    /** The state event published after sourcing [domainEventStream]. */
    fun stateEvent(): StateEvent<MockStateAggregate> =
        domainEventStream().toStateEvent(
            state = MockStateAggregate(id = AGGREGATE_ID, createdAt = EVENT_CREATE_TIME),
            tags = mapOf("dept" to listOf("wire")),
        )

    /** The signal a processor posts to the remote wait endpoint after processing [commandMessage]. */
    fun processedWaitSignal(): WaitSignal =
        SimpleWaitSignal(
            id = WAIT_SIGNAL_ID,
            waitCommandId = WAIT_COMMAND_ID,
            commandId = COMMAND_ID,
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id = AGGREGATE_ID, tenantId = TENANT_ID),
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "tck",
                processorName = "MockCommandAggregate",
                name = "onCommand",
            ),
            aggregateVersion = 1,
            result = mapOf("orderId" to AGGREGATE_ID),
            commands = listOf(SAGA_COMMAND_ID),
            signalTime = SIGNAL_TIME,
        )

    /** A failed signal, which carries the error code, message and binding errors. */
    fun failedWaitSignal(): WaitSignal =
        SimpleWaitSignal(
            id = WAIT_SIGNAL_ID,
            waitCommandId = WAIT_COMMAND_ID,
            commandId = COMMAND_ID,
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id = AGGREGATE_ID, tenantId = TENANT_ID),
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "tck",
                processorName = "MockCommandAggregate",
                name = "onCommand",
            ),
            errorCode = "IllegalArgument",
            errorMsg = "data must not be blank",
            bindingErrors = listOf(BindingError("data", "must not be blank")),
            signalTime = SIGNAL_TIME,
        )
}

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

package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.WAIT_COMMAND_ID
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withTraceId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

class MessagePropagatorProviderTest {

    @Test
    fun `provider composes service-loaded propagators`() {
        val upstream = SimpleCommandMessage(
            id = "command-id",
            header = DefaultHeader.empty()
                .withOperator("operator")
                .withTraceId("trace-id")
                .withUserAgent("JUnit")
                .withRemoteIp("127.0.0.1"),
            body = ProviderCommand,
            aggregateId = "wow-core-test.messaging_aggregate".toNamedAggregate().aggregateId("aggregate-id"),
        )
        WaitingForStage.sent(upstream.commandId).propagate("wait-endpoint", upstream.header)
        val target = DefaultHeader.empty()

        target.propagate(upstream)

        target.operator.assert().isEqualTo("operator")
        target.traceId.assert().isEqualTo("trace-id")
        target.upstreamId.assert().isEqualTo("command-id")
        target.userAgent.assert().isEqualTo("JUnit")
        target.remoteIp.assert().isEqualTo("127.0.0.1")
        target[WAIT_COMMAND_ID].assert().isEqualTo("command-id")
        target[COMMAND_WAIT_ENDPOINT].assert().isEqualTo("wait-endpoint")
    }
}

private object ProviderCommand

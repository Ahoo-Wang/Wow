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
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.ensureTraceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamName
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withTraceId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

class TraceMessagePropagatorTest {

    @Test
    fun `propagate copies trace id and upstream identity`() {
        val upstream = TestNamedMessage(
            id = "upstream-id",
            name = "upstream-name",
            header = DefaultHeader.empty().withTraceId("trace-id"),
        )
        val target = DefaultHeader.empty()

        TraceMessagePropagator().propagate(target, upstream)

        target.traceId.assert().isEqualTo("trace-id")
        target.upstreamId.assert().isEqualTo("upstream-id")
        target.upstreamName.assert().isEqualTo("upstream-name")
    }

    @Test
    fun `ensureTraceId uses command id when trace id is absent`() {
        val command = SimpleCommandMessage(
            id = "command-id",
            body = TraceCommand,
            aggregateId = "wow-core-test.messaging_aggregate".toNamedAggregate().aggregateId("aggregate-id"),
        )

        val returned = command.ensureTraceId()

        returned.assert().isSameAs(command)
        command.header.traceId.assert().isEqualTo("command-id")
    }
}

private object TraceCommand

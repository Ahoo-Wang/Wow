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

package me.ahoo.wow.webflux.route.state

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.command.IllegalAccessOwnerAggregateException
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.identity.identity
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class OwnerAggregatePreconditionTest {

    @Test
    fun `should pass when owner check is NEVER`() {
        val stateAggregate = mockk<StateAggregate<Any>>()
        OwnerAggregatePrecondition(OwnerPolicy.NEVER) { error("The owner is not read.") }.check(stateAggregate)
    }

    @Test
    fun `should pass when owner matches`() {
        val customerId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, customerId)
            .build()

        val stateAggregate = mockk<StateAggregate<Any>> {
            every { ownerId } returns customerId
        }

        OwnerAggregatePrecondition(request.identity(MOCK_AGGREGATE_METADATA), OwnerPolicy.ALWAYS).check(stateAggregate)
    }

    @Test
    fun `should throw IllegalAccessOwnerAggregateException when owner does not match`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .build()

        val stateAggregate = mockk<StateAggregate<Any>> {
            every { ownerId } returns generateGlobalId()
            every { aggregateId } returns "test.test".toNamedAggregate().aggregateId()
        }
        assertThrownBy<IllegalAccessOwnerAggregateException> {
            OwnerAggregatePrecondition(
                request.identity(MOCK_AGGREGATE_METADATA),
                OwnerPolicy.ALWAYS
            ).check(stateAggregate)
        }
    }

    @Test
    fun `a blank owner path segment does not let the owner header pass the check`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, " ")
            .header(CommandHeaders.OWNER_ID, "victim")
            .build()
        val stateAggregate = mockk<StateAggregate<Any>> {
            every { ownerId } returns "victim"
        }

        assertThrownBy<IllegalArgumentException> {
            OwnerAggregatePrecondition(
                request.identity(MOCK_AGGREGATE_METADATA),
                OwnerPolicy.ALWAYS
            ).check(stateAggregate)
        }.hasMessage("Path variable [ownerId] must not be blank.")
    }
}

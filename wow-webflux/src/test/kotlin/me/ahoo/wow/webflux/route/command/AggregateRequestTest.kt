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

package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.serialization.MessageRecords
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest

class AggregateRequestTest {
    @Test
    fun getOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns ownerId
        }
        assertThat(request.getOwnerId(), equalTo(ownerId))
    }

    @Test
    fun getOwnerIdFromHeader() {
        val ownerId = generateGlobalId()
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns null
            every { headers().firstHeader(CommandRequestHeaders.OWNER_ID) } returns ownerId
        }
        assertThat(request.getOwnerId(), equalTo(ownerId))
    }

    @Test
    fun getAggregateIdWithOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = mockk<ServerRequest>()
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, ownerId), equalTo(ownerId))
    }

    @Test
    fun getAggregateIdWithOwnerIdIsNullFromPathVariable() {
        val aggregateId = generateGlobalId()
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns null
            every { pathVariables()[RoutePaths.ID_KEY] } returns aggregateId
        }
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, null), equalTo(aggregateId))
    }

    @Test
    fun getAggregateIdWithOwner() {
        val ownerId = generateGlobalId()
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns ownerId
        }
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID), equalTo(ownerId))
    }

    @Test
    fun getCommandStage() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns "SENT"
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.SENT))
    }

    @Test
    fun getCommandStageIfNull() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns null
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.PROCESSED))
    }

    @Test
    fun getWaitContext() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns "test"
        }
        assertThat(request.getWaitContext(), equalTo("test"))
    }

    @Test
    fun getWaitProcessor() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns "test"
        }
        assertThat(request.getWaitProcessor(), equalTo("test"))
    }
}

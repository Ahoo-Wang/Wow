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

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.aggregate.command.CommandRequestHeaders
import me.ahoo.wow.serialization.MessageRecords
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class AggregateRequestTest {
    @Test
    fun getOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()

        assertThat(request.getOwnerId(), equalTo(ownerId))
    }

    @Test
    fun getOwnerIdFromHeader() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().header(CommandRequestHeaders.OWNER_ID, ownerId).build()
        assertThat(request.getOwnerId(), equalTo(ownerId))
    }

    @Test
    fun getAggregateIdWithOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().build()
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, ownerId), equalTo(ownerId))
    }

    @Test
    fun getAggregateIdWithOwnerIdIsNullFromPathVariable() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(RoutePaths.ID_KEY, aggregateId)
            .build()
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, null), equalTo(aggregateId))
    }

    @Test
    fun getAggregateIdWithOwner() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()
        assertThat(request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID), equalTo(ownerId))
    }

    @Test
    fun getCommandStage() {
        val request = MockServerRequest.builder()
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.SENT.name).build()
        assertThat(request.getCommandStage(), equalTo(CommandStage.SENT))
    }

    @Test
    fun getCommandStageIfNull() {
        val request = MockServerRequest.builder().build()
        assertThat(request.getCommandStage(), equalTo(CommandStage.PROCESSED))
    }

    @Test
    fun getWaitContext() {
        val request = MockServerRequest.builder()
            .header(CommandRequestHeaders.WAIT_CONTEXT, "test").build()
        assertThat(request.getWaitContext(), equalTo("test"))
    }

    @Test
    fun getWaitProcessor() {
        val request = MockServerRequest.builder()
            .header(CommandRequestHeaders.WAIT_PROCESSOR, "test").build()
        assertThat(request.getWaitProcessor(), equalTo("test"))
    }
}

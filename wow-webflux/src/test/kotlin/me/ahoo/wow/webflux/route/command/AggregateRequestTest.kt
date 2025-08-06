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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class AggregateRequestTest {
    @Test
    fun getOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()

        request.getOwnerId().assert().isEqualTo(ownerId)
    }

    @Test
    fun getOwnerIdFromHeader() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().header(CommandComponent.Header.OWNER_ID, ownerId).build()
        request.getOwnerId().assert().isEqualTo(ownerId)
    }

    @Test
    fun getAggregateIdWithOwnerIdFromPathVariable() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().build()
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, ownerId).assert().isEqualTo(ownerId)
    }

    @Test
    fun getAggregateIdWithOwnerIdIsNullFromPathVariable() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()

        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, null).assert().isEqualTo(aggregateId)
    }

    @Test
    fun getAggregateIdWithOwner() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID).assert().isEqualTo(ownerId)
    }

    @Test
    fun getCommandStage() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name).build()
        request.getWaitStage().assert().isEqualTo(CommandStage.SENT)
    }

    @Test
    fun getCommandStageIfNull() {
        val request = MockServerRequest.builder().build()
        request.getWaitStage().assert().isEqualTo(CommandStage.PROCESSED)
    }

    @Test
    fun getWaitContext() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_CONTEXT, "test").build()
        request.getWaitContext().assert().isEqualTo("test")
    }

    @Test
    fun getWaitProcessor() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_PROCESSOR, "test").build()
        request.getWaitProcessor().assert().isEqualTo("test")
    }
}

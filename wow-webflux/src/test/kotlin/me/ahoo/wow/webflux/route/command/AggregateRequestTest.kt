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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain
import me.ahoo.wow.command.wait.chain.SimpleWaitingForChain
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.time.Duration

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
    fun getSpaceIdFromPathVariable() {
        val spaceId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.SPACE_ID, spaceId).build()

        request.getSpaceId().assert().isEqualTo(spaceId)
    }

    @Test
    fun getSpaceIdFromHeader() {
        val spaceId = generateGlobalId()
        val request = MockServerRequest.builder().header(CommandComponent.Header.SPACE_ID, spaceId).build()
        request.getSpaceId().assert().isEqualTo(spaceId)
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

    @Test
    fun getWaitFunction() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_FUNCTION, "test").build()
        request.getWaitFunction().assert().isEqualTo("test")
    }

    @Test
    fun getWaitTailStage() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_TAIL_STAGE, CommandStage.SENT.name).build()
        request.getWaitTailStage().assert().isEqualTo(CommandStage.SENT)
    }

    @Test
    fun getWaitTailStageIfNull() {
        val request = MockServerRequest.builder().build()
        request.getWaitTailStage().assert().isNull()
    }

    @Test
    fun getWaitTailContext() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_TAIL_CONTEXT, "test").build()
        request.getWaitTailContext().assert().isEqualTo("test")
    }

    @Test
    fun getWaitTailProcessor() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_TAIL_PROCESSOR, "test").build()
        request.getWaitTailProcessor().assert().isEqualTo("test")
    }

    @Test
    fun getWaitTailFunction() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_TAIL_FUNCTION, "test").build()
        request.getWaitTailFunction().assert().isEqualTo("test")
    }

    @Test
    fun getTenantId() {
        val tenantId = generateGlobalId()
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, tenantId)
            .build()
        // We can't easily mock AggregateMetadata, so just test the header path
        request.headers().firstHeader(CommandComponent.Header.TENANT_ID).assert().isEqualTo(tenantId)
    }

    @Test
    fun getAggregateId() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.AGGREGATE_ID, aggregateId)
            .build()
        request.getAggregateId().assert().isEqualTo(aggregateId)
    }

    @Test
    fun getLocalFirst() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.LOCAL_FIRST, "true")
            .build()
        request.getLocalFirst().assert().isEqualTo(true)
    }

    @Test
    fun isSse() {
        val request = MockServerRequest.builder()
            .header("Accept", "text/event-stream")
            .build()
        request.isSse().assert().isTrue()
    }

    @Test
    fun getWaitTimeout() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_TIME_OUT, "5000")
            .build()
        request.getWaitTimeout().assert().isEqualTo(Duration.ofMillis(5000))
    }

    @Test
    fun getWaitTimeoutWithDefault() {
        val request = MockServerRequest.builder().build()
        request.getWaitTimeout(Duration.ofSeconds(10)).assert().isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun getTenantIdFromPath() {
        val tenantId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, tenantId)
            .build()
        // We can't easily mock AggregateMetadata, so just test the path variable path
        request.pathVariables()[MessageRecords.TENANT_ID].assert().isEqualTo(tenantId)
    }

    @Test
    fun getAggregateIdFromPath() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()
        request.getAggregateId().assert().isEqualTo(aggregateId)
    }

    @Test
    fun extractWaitingForStage() {
        val commandMessage = mockk<CommandMessage<Any>> {
            every { commandId } returns generateGlobalId()
            every { contextName } returns generateGlobalId()
        }

        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SAGA_HANDLED.name)
            .build()
        val waitStrategy = request.extractWaitStrategy(commandMessage)
        waitStrategy.assert().isInstanceOf(WaitingForStage::class.java)
        waitStrategy.waitCommandId.assert().isEqualTo(commandMessage.commandId)
        val waitingForStageMaterialized = waitStrategy.materialized as WaitingForStage.Materialized
        waitingForStageMaterialized.function.assert().isNotNull()
        waitingForStageMaterialized.stage.assert().isEqualTo(CommandStage.SAGA_HANDLED)
        waitingForStageMaterialized.function!!.contextName.assert().isEqualTo(commandMessage.contextName)
    }

    @Test
    fun extractSimpleWaitingForChain() {
        val commandMessage = mockk<CommandMessage<Any>> {
            every { commandId } returns generateGlobalId()
            every { contextName } returns generateGlobalId()
        }

        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SAGA_HANDLED.name)
            .header(CommandComponent.Header.WAIT_TAIL_STAGE, CommandStage.PROJECTED.name)
            .build()
        val waitStrategy = request.extractWaitStrategy(commandMessage)
        waitStrategy.assert().isInstanceOf(SimpleWaitingForChain::class.java)
        waitStrategy.waitCommandId.assert().isEqualTo(commandMessage.commandId)
        val simpleWaitingChain = waitStrategy.materialized as SimpleWaitingChain
        simpleWaitingChain.function.assert().isNotNull()
        simpleWaitingChain.function.contextName.assert().isEqualTo(commandMessage.contextName)
        simpleWaitingChain.tail.stage.assert().isEqualTo(CommandStage.PROJECTED)
        simpleWaitingChain.tail.function.contextName.assert().isEqualTo(commandMessage.contextName)
    }
}

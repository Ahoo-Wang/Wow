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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.ChainWaitTarget
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.StageWaitTarget
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.identity.identity
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.time.Duration

class AggregateRequestTest {
    @Test
    fun `should get owner id from path variable`() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()

        request.getOwnerId().assert().isEqualTo(ownerId)
    }

    @Test
    fun `should get owner id from header`() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().header(CommandHeaders.OWNER_ID, ownerId).build()
        request.getOwnerId().assert().isEqualTo(ownerId)
    }

    @Test
    fun `should get aggregate id from owner id path variable`() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().build()
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, ownerId).assert().isEqualTo(ownerId)
    }

    @Test
    fun `should get space id from header`() {
        val spaceId = generateGlobalId()
        val request = MockServerRequest.builder().header(WowHeaders.SPACE_ID, spaceId).build()
        request.getSpaceId().assert().isEqualTo(spaceId)
    }

    @Test
    fun `should get aggregate id from path when owner id is null`() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()

        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, null).assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should get aggregate id with owner id`() {
        val ownerId = generateGlobalId()
        val request = MockServerRequest.builder().pathVariable(MessageRecords.OWNER_ID, ownerId).build()
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID).assert().isEqualTo(ownerId)
    }

    @Test
    fun `should get wait stage from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_STAGE, CommandStage.SENT.name).build()
        request.getWaitStage().assert().isEqualTo(CommandStage.SENT)
    }

    @Test
    fun `should default to processed stage when header is null`() {
        val request = MockServerRequest.builder().build()
        request.getWaitStage().assert().isEqualTo(CommandStage.PROCESSED)
    }

    @Test
    fun `should get wait context from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_CONTEXT, "test").build()
        request.getWaitContext().assert().isEqualTo("test")
    }

    @Test
    fun `should get wait processor from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_PROCESSOR, "test").build()
        request.getWaitProcessor().assert().isEqualTo("test")
    }

    @Test
    fun `should get wait function from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_FUNCTION, "test").build()
        request.getWaitFunction().assert().isEqualTo("test")
    }

    @Test
    fun `should get wait tail stage from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_TAIL_STAGE, CommandStage.SENT.name).build()
        request.getWaitTailStage().assert().isEqualTo(CommandStage.SENT)
    }

    @Test
    fun `should return null when wait tail stage header is missing`() {
        val request = MockServerRequest.builder().build()
        request.getWaitTailStage().assert().isNull()
    }

    @Test
    fun `should get wait tail context from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_TAIL_CONTEXT, "test").build()
        request.getWaitTailContext().assert().isEqualTo("test")
    }

    @Test
    fun `should get wait tail processor from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_TAIL_PROCESSOR, "test").build()
        request.getWaitTailProcessor().assert().isEqualTo("test")
    }

    @Test
    fun `should get wait tail function from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_TAIL_FUNCTION, "test").build()
        request.getWaitTailFunction().assert().isEqualTo("test")
    }

    @Test
    fun `should get tenant id from header`() {
        val tenantId = generateGlobalId()
        val request = MockServerRequest.builder()
            .header(CommandHeaders.TENANT_ID, tenantId)
            .build()
        request.getTenantId(MOCK_AGGREGATE_METADATA).assert().isEqualTo(tenantId)
    }

    @Test
    fun `should get aggregate id from header`() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .header(CommandHeaders.AGGREGATE_ID, aggregateId)
            .build()
        request.getAggregateId().assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should get local first flag from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.LOCAL_FIRST, "true")
            .build()
        request.getLocalFirst().assert().isEqualTo(true)
    }

    @Test
    fun `should detect sse from accept header`() {
        val request = MockServerRequest.builder()
            .header("Accept", "text/event-stream")
            .build()
        request.isSse().assert().isTrue()
    }

    @Test
    fun `should get wait timeout from header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_TIME_OUT, "5000")
            .build()
        request.getWaitTimeout().assert().isEqualTo(Duration.ofMillis(5000))
    }

    @Test
    fun `should expose documented wait timeout header`() {
        CommandHeaders.WAIT_TIME_OUT.assert().isEqualTo("Command-Wait-Timeout")
    }

    @Test
    fun `should get wait timeout from documented header`() {
        val request = MockServerRequest.builder()
            .header("Command-Wait-Timeout", "5000")
            .build()
        request.getWaitTimeout().assert().isEqualTo(Duration.ofMillis(5000))
    }

    @Test
    fun `should get wait timeout from legacy misspelled header`() {
        val request = MockServerRequest.builder()
            .header("Command-Wait-Timout", "5000")
            .build()
        request.getWaitTimeout().assert().isEqualTo(Duration.ofMillis(5000))
    }

    @Test
    fun `should use default wait timeout when header is missing`() {
        val request = MockServerRequest.builder().build()
        request.getWaitTimeout(Duration.ofSeconds(10)).assert().isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun `should get tenant id from path variable`() {
        val tenantId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, tenantId)
            .build()
        request.getTenantId(MOCK_AGGREGATE_METADATA).assert().isEqualTo(tenantId)
    }

    @Test
    fun `should get aggregate id from path variable`() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()
        request.getAggregateId().assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should extract stage wait plan`() {
        val commandMessage = mockk<CommandMessage<Any>> {
            every { commandId } returns generateGlobalId()
            every { contextName } returns generateGlobalId()
        }

        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_STAGE, CommandStage.SAGA_HANDLED.name)
            .build()
        val waitPlan = request.extractWaitPlan(commandMessage)
        waitPlan.waitCommandId.assert().isEqualTo(commandMessage.commandId)
        val target = waitPlan.target as StageWaitTarget
        target.function.assert().isNotNull()
        target.stage.assert().isEqualTo(CommandStage.SAGA_HANDLED)
        target.function!!.contextName.assert().isEqualTo(commandMessage.contextName)
    }

    @Test
    fun `should extract simple chain wait plan`() {
        val commandMessage = mockk<CommandMessage<Any>> {
            every { commandId } returns generateGlobalId()
            every { contextName } returns generateGlobalId()
        }

        val request = MockServerRequest.builder()
            .header(CommandHeaders.WAIT_STAGE, CommandStage.SAGA_HANDLED.name)
            .header(CommandHeaders.WAIT_TAIL_STAGE, CommandStage.PROJECTED.name)
            .build()
        val waitPlan = request.extractWaitPlan(commandMessage)
        waitPlan.waitCommandId.assert().isEqualTo(commandMessage.commandId)
        val target = waitPlan.target as ChainWaitTarget
        target.function.contextName.assert().isEqualTo(commandMessage.contextName)
        target.tail.stage.assert().isEqualTo(CommandStage.PROJECTED)
        target.tail.function.contextName.assert().isEqualTo(commandMessage.contextName)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `a declared but blank path variable is rejected instead of falling back to the header`(blank: String) {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, blank)
            .pathVariable(MessageRecords.OWNER_ID, blank)
            .pathVariable(MessageRecords.ID, blank)
            .header(CommandHeaders.TENANT_ID, "victim")
            .header(CommandHeaders.OWNER_ID, "victim")
            .header(CommandHeaders.AGGREGATE_ID, "victim")
            .build()

        assertThrownBy<IllegalArgumentException> { request.getTenantId(MOCK_AGGREGATE_METADATA) }
            .hasMessage("Path variable [tenantId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { request.getTenantIdOrDefault(MOCK_AGGREGATE_METADATA) }
        // Every reader rejects any blank identity path variable the route declares, before reading its own fact.
        assertThrownBy<IllegalArgumentException> { request.getOwnerId() }
            .hasMessage("Path variable [tenantId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { request.getAggregateId() }
            .hasMessage("Path variable [tenantId] must not be blank.")
        assertThrownBy<IllegalArgumentException> { request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID) }
        assertThrownBy<IllegalArgumentException> { request.getAggregateId(AggregateRoute.Owner.NEVER, null) }
    }

    @Test
    fun `a declared path variable wins over a header that agrees or is absent`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .pathVariable(MessageRecords.ID, "id-a")
            .header(CommandHeaders.TENANT_ID, "tenant-a")
            // The aggregate ID is not a fact a header may contradict: the path wins, the header is ignored.
            .header(CommandHeaders.AGGREGATE_ID, "victim")
            .build()

        request.getTenantId(MOCK_AGGREGATE_METADATA).assert().isEqualTo("tenant-a")
        request.getOwnerId().assert().isEqualTo("owner-a")
        request.getAggregateId().assert().isEqualTo("id-a")
    }

    /** The deprecated reader keeps 9.2's rule: for an aggregate owned by its ID, the owner header wins over `{id}`. */
    @Test
    fun `the deprecated owner-policy aggregate id reader keeps 9_2 behaviour`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "a")
            .header(CommandHeaders.OWNER_ID, "b")
            .build()
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID).assert().isEqualTo("b")
        request.getAggregateId(AggregateRoute.Owner.AGGREGATE_ID, "c").assert().isEqualTo("c")
        request.getAggregateId(AggregateRoute.Owner.ALWAYS).assert().isEqualTo("a")
        request.getAggregateId(AggregateRoute.Owner.ALWAYS, "c").assert().isEqualTo("a")
    }

    @Test
    fun `a header contradicting a declared tenant or owner path variable is rejected`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .header(CommandHeaders.TENANT_ID, "victim")
            .header(CommandHeaders.OWNER_ID, "victim")
            .build()

        assertThrownBy<IllegalArgumentException> {
            request.getTenantId(MOCK_AGGREGATE_METADATA)
        }.hasMessage("Conflicting tenantId: the route fixes [tenant-a], but the request header gives [victim].")
        assertThrownBy<IllegalArgumentException> {
            request.getOwnerId()
        }.hasMessage("Conflicting ownerId: the route fixes [owner-a], but the request header gives [victim].")
    }

    @Test
    fun `a route without the variable reads the header`() {
        val request = MockServerRequest.builder()
            .header(CommandHeaders.TENANT_ID, "tenant-h")
            .header(CommandHeaders.OWNER_ID, "owner-h")
            .header(CommandHeaders.AGGREGATE_ID, "id-h")
            .build()

        request.getTenantId(MOCK_AGGREGATE_METADATA).assert().isEqualTo("tenant-h")
        request.getOwnerId().assert().isEqualTo("owner-h")
        request.getAggregateId().assert().isEqualTo("id-h")
        MockServerRequest.builder().build().getTenantIdOrDefault(MOCK_AGGREGATE_METADATA)
            .assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }

    @Test
    fun `the space reader of a route reads the header only for a spaced aggregate`() {
        val request = MockServerRequest.builder().header(WowHeaders.SPACE_ID, "space-a").build()

        request.getSpaceId(Order::class.java.aggregateRouteMetadata()).assert().isEqualTo("space-a")
        request.getSpaceId(Cart::class.java.aggregateRouteMetadata()).assert().isNull()
    }

    @Test
    fun `each deprecated reader agrees with its public replacement`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
            .pathVariable(MessageRecords.OWNER_ID, "owner-a")
            .pathVariable(MessageRecords.ID, "id-a")
            .header(WowHeaders.SPACE_ID, "space-a")
            .header(CommandHeaders.REQUEST_ID, "request-a")
            .build()
        val order = Order::class.java.aggregateRouteMetadata()
        val identity = request.identity(order.aggregateMetadata)

        request.getTenantId(order.aggregateMetadata).assert().isEqualTo(identity.tenantId()).isEqualTo("tenant-a")
        request.getTenantIdOrDefault(order.aggregateMetadata).assert().isEqualTo("tenant-a")
        request.getOwnerId().assert().isEqualTo(identity.ownerId()).isEqualTo("owner-a")
        request.getAggregateId().assert().isEqualTo(identity.aggregateId()).isEqualTo("id-a")
        request.getSpaceId().assert().isEqualTo(identity.spaceId()).isEqualTo("space-a")
        request.getSpaceId(order).assert().isEqualTo(request.identity(order).spaceId())
        identity.readOwnerId().assert().isEqualTo("owner-a")
        identity.requestId().assert().isEqualTo("request-a")
        identity.request.assert().isSameAs(request)
    }

    @Test
    fun `the replacement applies the aggregate's own policies`() {
        // Cart: static tenant, owner = aggregate ID, not spaced.
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "cart-a")
            .header(CommandHeaders.TENANT_ID, "ignored")
            .header(WowHeaders.SPACE_ID, "space-a")
            .build()
        val cart = Cart::class.java.aggregateRouteMetadata()
        val identity = request.identity(cart)

        identity.tenantId().assert().isEqualTo(cart.aggregateMetadata.staticTenantId)
        request.getTenantId(cart.aggregateMetadata).assert().isEqualTo(identity.tenantId())
        identity.ownerId().assert().isEqualTo("cart-a")
        identity.aggregateId().assert().isEqualTo("cart-a")
        identity.spaceId().assert().isNull()
        identity.readOwnerId().assert().isNull()
    }
}

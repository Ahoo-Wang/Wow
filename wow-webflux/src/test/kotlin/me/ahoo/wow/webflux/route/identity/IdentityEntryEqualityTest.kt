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

package me.ahoo.wow.webflux.route.identity

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.DEFAULT_SPACE_ID
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.saga.stateless.StatelessSagaFunction
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Mono

/**
 * The same command, sent to the same target through each entry — REST (the route's request), an in-process
 * `CommandGateway` caller (`toCommandMessage` with explicit arguments) and a saga reacting to an event of that tenant
 * and space — carries the same identity, and the identity each entry gave before the identity resolver (golden values).
 */
class IdentityEntryEqualityTest {
    data class Touch(@AggregateId val id: String)

    data class Touched(val value: String)

    data class Identity(
        val namedAggregate: String,
        val tenantId: String,
        val id: String,
        val ownerId: String,
        val spaceId: String,
    )

    private val commandMessageFactory = SimpleCommandMessageFactory(
        NoOpValidator,
        SimpleCommandBuilderRewriterRegistry()
    )

    private fun CommandMessage<*>.identity() = Identity(
        namedAggregate = "${aggregateId.contextName}.${aggregateId.aggregateName}",
        tenantId = aggregateId.tenantId,
        id = aggregateId.id,
        ownerId = ownerId,
        spaceId = spaceId,
    )

    private fun rest(route: AggregateRouteMetadata<*>, body: Any, request: MockServerRequest): Identity =
        DefaultCommandMessageExtractor(commandMessageFactory, DefaultCommandBuilderExtractor)
            .extract(route, body, request)
            .block()!!
            .identity()

    private fun saga(
        route: AggregateRouteMetadata<*>,
        body: Any,
        eventTenantId: String,
        eventSpaceId: String,
        ownerId: String,
    ): Identity {
        val sent = mutableListOf<CommandMessage<*>>()
        val event = Touched("upstream").toDomainEvent(
            aggregateId = MaterializedNamedAggregate("upstream-context", "upstream")
                .aggregateId(id = "upstream-id", tenantId = eventTenantId),
            commandId = "upstream-command",
            spaceId = eventSpaceId,
        )
        val saga = StatelessSagaFunction(
            delegate = ReturningFunction(
                body.commandBuilder().namedAggregate(route.aggregateMetadata.namedAggregate).ownerId(ownerId),
                event
            ),
            commandGateway = mockk {
                every { send(any<CommandMessage<*>>()) } answers {
                    sent += firstArg<CommandMessage<*>>()
                    Mono.empty()
                }
            },
            commandMessageFactory = commandMessageFactory,
        )
        saga.invoke(SimpleDomainEventExchange(event)).block()
        return sent.single().identity()
    }

    @Test
    fun `an owned spaced aggregate gets the same identity from every entry`() {
        val route = Order::class.java.aggregateRouteMetadata()
        val body = Touch(id = "order-a")
        val expected = Identity(
            namedAggregate = route.aggregateMetadata.namedAggregate.let { "${it.contextName}.${it.aggregateName}" },
            tenantId = "tenant-a",
            id = "order-a",
            ownerId = "owner-a",
            spaceId = "space-a",
        )

        rest(
            route,
            body,
            MockServerRequest.builder()
                .pathVariable(MessageRecords.TENANT_ID, "tenant-a")
                .pathVariable(MessageRecords.OWNER_ID, "owner-a")
                .pathVariable(MessageRecords.ID, "order-a")
                .header(WowHeaders.SPACE_ID, "space-a")
                .build()
        ).assert().isEqualTo(expected)
        body.toCommandMessage(
            namedAggregate = route.aggregateMetadata.namedAggregate,
            tenantId = "tenant-a",
            ownerId = "owner-a",
            spaceId = "space-a",
        ).identity().assert().isEqualTo(expected)
        saga(route, body, eventTenantId = "tenant-a", eventSpaceId = "space-a", ownerId = "owner-a")
            .assert().isEqualTo(expected)
    }

    @Test
    fun `a static tenant aggregate owned by its ID gets the same identity from every entry`() {
        val route = Cart::class.java.aggregateRouteMetadata()
        val namedAggregate: NamedAggregate = route.aggregateMetadata.namedAggregate
        val staticTenantId = route.aggregateMetadata.staticTenantId!!
        val body = Touch(id = "customer-a")
        val expected = Identity(
            namedAggregate = "${namedAggregate.contextName}.${namedAggregate.aggregateName}",
            tenantId = staticTenantId,
            id = "customer-a",
            ownerId = "customer-a",
            // A cart is not spaced: every entry drops the space it is given.
            spaceId = DEFAULT_SPACE_ID,
        )

        rest(
            route,
            body,
            MockServerRequest.builder()
                .pathVariable(MessageRecords.OWNER_ID, "customer-a")
                .header(WowHeaders.SPACE_ID, "space-a")
                .build()
        ).assert().isEqualTo(expected)
        body.toCommandMessage(
            namedAggregate = namedAggregate,
            tenantId = staticTenantId,
            ownerId = "customer-a",
            spaceId = "space-a",
        ).identity().assert().isEqualTo(expected)
        saga(route, body, eventTenantId = staticTenantId, eventSpaceId = "space-a", ownerId = "customer-a")
            .assert().isEqualTo(expected)
    }

    /**
     * Unchanged on purpose: only the HTTP adapter applies the static tenant and owner = aggregate ID. An in-process
     * command that names neither lands in the default tenant with its own aggregate ID, as before 9.3.0 (changing it
     * would make 9.2 and 9.3 nodes of one cluster disagree).
     */
    @Test
    fun `in-process commands keep their own tenant and owner rules`() {
        val route = Cart::class.java.aggregateRouteMetadata()
        Touch(id = "cart-a").toCommandMessage(
            namedAggregate = route.aggregateMetadata.namedAggregate,
            ownerId = "customer-a",
        ).identity().assert().isEqualTo(
            Identity(
                namedAggregate = route.aggregateMetadata.namedAggregate
                    .let { "${it.contextName}.${it.aggregateName}" },
                tenantId = TenantId.DEFAULT_TENANT_ID,
                id = "cart-a",
                ownerId = "customer-a",
                spaceId = DEFAULT_SPACE_ID,
            )
        )
    }

    private class ReturningFunction(
        private val result: Any,
        event: DomainEvent<*>,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val contextName: String = event.contextName
        override val name: String = "onEvent"
        override val processor: Any = "processor"
        override val supportedType: Class<*> = Touched::class.java
        override val supportedTopics: Set<NamedAggregate> = emptySet()
        override val functionKind: FunctionKind = FunctionKind.EVENT

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.just(result)
    }
}

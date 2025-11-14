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
package me.ahoo.wow.test

import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import me.ahoo.wow.test.aggregate.DefaultGivenStage
import me.ahoo.wow.test.aggregate.GivenStage

/**
 * Aggregate Verifier provides utilities for testing domain aggregates using the Given/When/Expect pattern.
 *
 * This object facilitates setting up and executing tests for aggregate behavior by providing methods to create
 * GivenStage instances. The Given/When/Expect pattern allows for declarative testing of command handling,
 * event production, and state changes in domain aggregates.
 *
 * Key features:
 * - Fluent API for test setup and verification
 * - Support for event sourcing and state-based aggregates
 * - Integration with in-memory event stores for isolated testing
 * - Dependency injection support via ServiceProvider
 * - Multi-tenant aggregate testing capabilities
 *
 * Example usage:
 * ```kotlin
 * aggregateVerifier<Cart, CartState>()
 *     .given(CartItemAdded(...))
 *     .whenCommand(AddCartItem(...))
 *     .expectEventType(CartItemAdded::class)
 *     .expectState { items.assert().hasSize(1) }
 *     .verify()
 * ```
 *
 * @author ahoo wang
 */
object AggregateVerifier {
    /**
     * Creates a GivenStage for testing an aggregate using the command class as the aggregate type.
     *
     * This extension function on Class<C> initializes the testing framework with aggregate metadata
     * derived from the command class annotation. It sets up the necessary components for testing
     * aggregate behavior including event store, state factory, and service provider.
     *
     * @param C the type of the command aggregate class
     * @param S the type of the state aggregate
     * @param aggregateId the unique identifier for the aggregate instance, defaults to a generated global ID
     * @param tenantId the tenant identifier for multi-tenant scenarios, defaults to DEFAULT_TENANT_ID
     * @param stateAggregateFactory factory for creating state aggregate instances, defaults to ConstructorStateAggregateFactory
     * @param eventStore the event store implementation for event sourcing, defaults to InMemoryEventStore for isolated testing
     * @param serviceProvider container for dependency injection, defaults to SimpleServiceProvider
     * @return a GivenStage instance ready for chaining test expectations
     * @throws IllegalArgumentException if aggregate metadata cannot be resolved from the class annotations
     *
     * Example:
     * ```kotlin
     * Cart::class.java.aggregateVerifier<Cart, CartState>()
     *     .given()
     *     .whenCommand(AddCartItem(productId = "item1", quantity = 1))
     *     .expectEventType(CartItemAdded::class)
     *     .expectState { items.assert().hasSize(1) }
     *     .verify()
     * ```
     */
    fun <C : Any, S : Any> Class<C>.aggregateVerifier(
        aggregateId: String = generateGlobalId(),
        tenantId: String = TenantId.DEFAULT_TENANT_ID,
        stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        eventStore: EventStore = InMemoryEventStore(),
        serviceProvider: ServiceProvider = SimpleServiceProvider()
    ): GivenStage<S> {
        val metadata: AggregateMetadata<C, S> = aggregateMetadata()
        return DefaultGivenStage(
            aggregateId = metadata.aggregateId(
                id = aggregateId,
                tenantId = tenantId,
            ),
            metadata = metadata,
            stateAggregateFactory = stateAggregateFactory,
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
            serviceProvider = serviceProvider,
        )
    }

    /**
     * Creates a GivenStage for testing an aggregate with explicit command and state types.
     *
     * This static method provides an alternative way to initialize aggregate testing when you need to
     * specify both command and state aggregate types explicitly. It internally delegates to the
     * extension function on the command aggregate type.
     *
     * @param C the type of the command aggregate
     * @param S the type of the state aggregate
     * @param commandAggregateType the Class representing the command aggregate type
     * @param stateAggregateType the Class representing the state aggregate type (used for type safety)
     * @param aggregateId the unique identifier for the aggregate instance, defaults to a generated global ID
     * @param tenantId the tenant identifier for multi-tenant scenarios, defaults to DEFAULT_TENANT_ID
     * @param stateAggregateFactory factory for creating state aggregate instances, defaults to ConstructorStateAggregateFactory
     * @param eventStore the event store implementation for event sourcing, defaults to InMemoryEventStore for isolated testing
     * @param serviceProvider container for dependency injection, defaults to SimpleServiceProvider
     * @return a GivenStage instance ready for chaining test expectations
     * @throws IllegalArgumentException if aggregate metadata cannot be resolved from the command class
     *
     * Example:
     * ```kotlin
     * aggregateVerifier(Cart::class.java, CartState::class.java)
     *     .given(CartItemAdded(...))
     *     .whenCommand(RemoveCartItem(productIds = setOf("item1")))
     *     .expectEventType(CartItemRemoved::class)
     *     .expectState { items.assert().isEmpty() }
     *     .verify()
     * ```
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("UnusedParameter")
    fun <C : Any, S : Any> aggregateVerifier(
        commandAggregateType: Class<C>,
        stateAggregateType: Class<S>,
        aggregateId: String = generateGlobalId(),
        tenantId: String = TenantId.DEFAULT_TENANT_ID,
        stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        eventStore: EventStore = InMemoryEventStore(),
        serviceProvider: ServiceProvider = SimpleServiceProvider()
    ): GivenStage<S> = commandAggregateType.aggregateVerifier(
        aggregateId = aggregateId,
        tenantId = tenantId,
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        serviceProvider = serviceProvider,
    )
}

/**
 * Creates a GivenStage for testing an aggregate using reified generic types.
 *
 * This inline function provides a convenient way to create aggregate verifiers without explicitly
 * specifying Class objects, leveraging Kotlin's reified generics for type-safe testing setup.
 * Uses default configurations for event store and service provider.
 *
 * @param C the reified type of the command aggregate
 * @param S the reified type of the state aggregate
 * @param aggregateId the unique identifier for the aggregate instance, defaults to a generated global ID
 * @param tenantId the tenant identifier for multi-tenant scenarios, defaults to DEFAULT_TENANT_ID
 * @return a GivenStage instance ready for chaining test expectations
 * @throws IllegalArgumentException if aggregate metadata cannot be resolved from the command class
 *
 * Example:
 * ```kotlin
 * aggregateVerifier<Cart, CartState>()
 *     .given()
 *     .whenCommand(AddCartItem(productId = "item1", quantity = 1))
 *     .expectEventType(CartItemAdded::class)
 *     .expectState { items.assert().hasSize(1) }
 *     .verify()
 * ```
 */
inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = generateGlobalId(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID
): GivenStage<S> = C::class.java.aggregateVerifier(
    aggregateId = aggregateId,
    tenantId = tenantId,
)

/**
 * Creates a GivenStage for testing an aggregate with custom service provider using reified generics.
 *
 * This inline function allows specifying a custom ServiceProvider for dependency injection while
 * using default event store. Useful for testing aggregates with mocked dependencies.
 *
 * @param C the reified type of the command aggregate
 * @param S the reified type of the state aggregate
 * @param aggregateId the unique identifier for the aggregate instance, defaults to a generated global ID
 * @param tenantId the tenant identifier for multi-tenant scenarios, defaults to DEFAULT_TENANT_ID
 * @param serviceProvider the service provider instance for dependency injection
 * @return a GivenStage instance ready for chaining test expectations
 * @throws IllegalArgumentException if aggregate metadata cannot be resolved from the command class
 *
 * Example:
 * ```kotlin
 * val mockServiceProvider = SimpleServiceProvider().apply {
 *     register("inventoryService", mockInventoryService)
 * }
 * aggregateVerifier<Order, OrderState>(serviceProvider = mockServiceProvider)
 *     .inject(mockPricingService)
 *     .given()
 *     .whenCommand(CreateOrder(...))
 *     .expectEventType(OrderCreated::class)
 *     .verify()
 * ```
 */
inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = generateGlobalId(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID,
    serviceProvider: ServiceProvider
): GivenStage<S> = C::class.java.aggregateVerifier(
    aggregateId = aggregateId,
    tenantId = tenantId,
    serviceProvider = serviceProvider,
)

/**
 * Creates a GivenStage for testing an aggregate with custom event store and service provider using reified generics.
 *
 * This inline function provides full control over both event store and service provider configurations,
 * enabling comprehensive testing scenarios including custom event stores for testing event sourcing behavior.
 *
 * @param C the reified type of the command aggregate
 * @param S the reified type of the state aggregate
 * @param aggregateId the unique identifier for the aggregate instance, defaults to a generated global ID
 * @param tenantId the tenant identifier for multi-tenant scenarios, defaults to DEFAULT_TENANT_ID
 * @param eventStore the event store implementation, defaults to InMemoryEventStore
 * @param serviceProvider the service provider for dependency injection, defaults to SimpleServiceProvider
 * @return a GivenStage instance ready for chaining test expectations
 * @throws IllegalArgumentException if aggregate metadata cannot be resolved from the command class
 *
 * Example:
 * ```kotlin
 * val customEventStore = InMemoryEventStore()
 * val mockServiceProvider = SimpleServiceProvider().apply {
 *     register("paymentService", mockPaymentService)
 * }
 * aggregateVerifier<Order, OrderState>(
 *     eventStore = customEventStore,
 *     serviceProvider = mockServiceProvider
 * )
 *     .given()
 *     .whenCommand(PayOrder(...))
 *     .expectEventType(OrderPaid::class)
 *     .expectState { status.assert().isEqualTo(OrderStatus.PAID) }
 *     .verify()
 * ```
 */
inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = generateGlobalId(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID,
    eventStore: EventStore = InMemoryEventStore(),
    serviceProvider: ServiceProvider = SimpleServiceProvider()
): GivenStage<S> = C::class.java.aggregateVerifier(
    aggregateId = aggregateId,
    tenantId = tenantId,
    eventStore = eventStore,
    serviceProvider = serviceProvider,
)

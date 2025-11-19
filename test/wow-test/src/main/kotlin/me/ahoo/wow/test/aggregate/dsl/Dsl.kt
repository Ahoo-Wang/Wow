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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.aggregate.AggregateExpecter
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.dsl.NameSpecCapable
import me.ahoo.wow.test.dsl.TestDslMarker
import me.ahoo.wow.test.saga.stateless.dsl.InjectPublicServiceCapable

/**
 * Defines the Domain Specific Language (DSL) for aggregate testing in the WOW framework.
 *
 * This interface provides the entry point for creating test scenarios that validate
 * aggregate behavior through a fluent, readable syntax. It allows setting up test
 * preconditions, executing commands, and verifying results in a structured manner.
 *
 * @param S the type of the aggregate state
 */
@TestDslMarker
interface AggregateDsl<S : Any> :
    InjectPublicServiceCapable,
    AggregateDslContextCapable<S> {
    /**
     * Starts a new test scenario for an aggregate.
     *
     * This method initializes a test context with the specified parameters and executes
     * the test scenario defined in the block. Each call to `on` creates an independent
     * test scenario that can be run in parallel.
     *
     * @param aggregateId the unique identifier for the aggregate (auto-generated if not specified)
     * @param tenantId the tenant identifier for multi-tenant scenarios (defaults to system tenant)
     * @param stateAggregateFactory factory for creating aggregate state instances
     * @param eventStore the event store to use for event sourcing (defaults to in-memory store)
     * @param serviceProvider provider for dependency injection during testing
     * @param block the test scenario definition using GivenDsl
     */
    fun on(
        aggregateId: String = generateGlobalId(),
        tenantId: String = TenantId.DEFAULT_TENANT_ID,
        stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        eventStore: EventStore = InMemoryEventStore(),
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        block: GivenDsl<S>.() -> Unit
    )

    /**
     * Creates a branching test scenario from a previously referenced ExpectStage.
     *
     * This method allows testing alternative scenarios starting from any previously
     * marked verification point (using ref() in ExpectDsl). It enables complex
     * test flows where multiple branches diverge from the same verified state.
     *
     * Example usage:
     * ```kotlin
     * // In your test setup
     * on {
     *     whenCommand(CreateOrderCommand(...)) {
     *         expectEventType(OrderCreated::class)
     *         ref("order-created")  // Mark this verification point
     *         expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
     *     }
     * }
     *
     * // Later, create branches from the marked point
     * fork("order-created", "Pay Order") {
     *     whenCommand(PayOrderCommand(...)) {
     *         expectEventType(OrderPaid::class)
     *         expectState { status.assert().isEqualTo(OrderStatus.PAID) }
     *     }
     * }
     * fork("order-created", "Cancel Order") {
     *     whenCommand(CancelOrderCommand(...)) {
     *         expectEventType(OrderCancelled::class)
     *         expectState { status.assert().isEqualTo(OrderStatus.CANCELLED) }
     *     }
     * }
     * ```
     *
     * @param ref the reference name of a previously marked ExpectStage
     * @param name optional descriptive name for the forked scenario
     * @param verifyError whether to verify that an error occurred at the referenced stage
     * @param block the test scenario to execute in the forked context
     */
    fun fork(
        ref: String,
        name: String = "",
        verifyError: Boolean = false,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    )
}

/**
 * Defines the "Given" phase of aggregate testing where preconditions are established.
 *
 * This interface provides methods to set up the initial state of an aggregate before
 * executing commands. It supports various ways to initialize aggregate state including
 * event replay, direct state setting, and dependency injection.
 *
 * @param S the type of the aggregate state
 */
@TestDslMarker
interface GivenDsl<S : Any> :
    WhenDsl<S>,
    NameSpecCapable,
    AggregateDslContextCapable<S> {
    /**
     * Injects services or dependencies into the test context.
     *
     * This method allows registering mock implementations or test-specific services
     * that the aggregate depends on during command execution.
     *
     * @param inject a lambda that configures the service provider with test dependencies
     */
    fun inject(inject: ServiceProvider.() -> Unit)

    /**
     * Sets the owner ID for the aggregate in this test scenario.
     *
     * The owner ID is used for access control and may affect command execution
     * behavior in multi-user or permission-based scenarios.
     *
     * @param ownerId the identifier of the aggregate owner
     */
    fun givenOwnerId(ownerId: String)

    /**
     * Initializes the aggregate by replaying a single domain event.
     *
     * This method applies the specified event to set up the aggregate state,
     * then continues to the command execution phase.
     *
     * @param event the domain event to replay
     * @param block the continuation block for command execution
     */
    fun givenEvent(
        event: Any,
        block: WhenDsl<S>.() -> Unit
    )

    /**
     * Initializes the aggregate by replaying multiple domain events.
     *
     * This method applies the specified events in order to reconstruct the
     * aggregate state, simulating previous command executions.
     *
     * @param events array of domain events to replay (empty by default)
     * @param block the continuation block for command execution
     */
    fun givenEvent(
        events: Array<out Any> = emptyArray(),
        block: WhenDsl<S>.() -> Unit
    )

    /**
     * Initializes the aggregate with a specific state and version.
     *
     * This method directly sets the aggregate state and version number,
     * bypassing event sourcing for scenarios where direct state setup is preferred.
     *
     * @param state the initial state to set on the aggregate
     * @param version the version number for the aggregate state
     * @param block the continuation block for command execution
     */
    fun givenState(
        state: S,
        version: Int,
        block: WhenDsl<S>.() -> Unit
    )
}

/**
 * Defines the "When" phase of aggregate testing where commands are executed.
 *
 * This interface provides methods to execute commands on aggregates that have been
 * set up in the Given phase, transitioning to the Expect phase for result validation.
 *
 * @param S the type of the aggregate state
 */
@TestDslMarker
interface WhenDsl<S : Any> :
    NameSpecCapable,
    AggregateDslContextCapable<S> {
    /**
     * Executes a command on the aggregate and validates the results.
     *
     * This method processes the specified command through the aggregate's command handling
     * logic and provides an ExpectDsl context for defining assertions on the outcome.
     *
     * @param command the command object to execute on the aggregate
     * @param header optional command header for additional context (defaults to empty)
     * @param ownerId optional owner ID override for the command execution
     * @param block the expectation block that defines assertions on command results
     */
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.empty(),
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        block: ExpectDsl<S>.() -> Unit
    )
}

/**
 * Defines the "Expect" phase of aggregate testing where results are validated.
 *
 * This interface extends AggregateExpecter to provide comprehensive assertion methods
 * for validating command execution results, and adds the fork method for creating
 * branching test scenarios.
 *
 * @param S the type of the aggregate state
 */
@TestDslMarker
interface ExpectDsl<S : Any> :
    AggregateExpecter<S, ExpectDsl<S>>,
    AggregateDslContextCapable<S> {
    /**
     * Marks the current ExpectStage with a reference name for later branching.
     *
     * This method stores the current verification state under the specified reference,
     * allowing subsequent test scenarios to fork from this exact point using
     * AggregateDsl.fork(ref, ...). This enables complex test flows with multiple
     * branches diverging from the same verified aggregate state.
     *
     * Example usage:
     * ```kotlin
     * whenCommand(CreateOrderCommand(...)) {
     *     expectEventType(OrderCreated::class)
     *     ref("order-created")  // Mark this point for later branching
     *     expectState { /* ... */ }
     * }
     *
     * // Later in the same test class
     * aggregateDsl.fork("order-created", "Pay Order") {
     *     whenCommand(PayOrderCommand(...)) {
     *         expectEventType(OrderPaid::class)
     *     }
     * }
     * ```
     *
     * @param ref the unique reference name to assign to this verification point
     */
    fun ref(ref: String)

    /**
     * Creates a branching test scenario from the current verified state.
     *
     * This method allows testing multiple command sequences or alternative scenarios
     * starting from the same verified aggregate state. Each fork creates an isolated
     * test context that can execute additional commands and expectations.
     *
     * Example usage:
     * ```kotlin
     * whenCommand(CreateOrderCommand(...)) {
     *     expectEventType(OrderCreated::class)
     *     fork("Pay Order") {
     *         whenCommand(PayOrderCommand(...)) {
     *             expectEventType(OrderPaid::class)
     *         }
     *     }
     *     fork("Cancel Order") {
     *         whenCommand(CancelOrderCommand(...)) {
     *             expectEventType(OrderCancelled::class)
     *         }
     *     }
     * }
     * ```
     *
     * @param name optional descriptive name for the forked scenario
     * @param verifyError whether to verify that an error occurred before forking
     * @param block the test scenario to execute in the forked context
     */
    fun fork(
        name: String = "",
        verifyError: Boolean = false,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    )
}

/**
 * Defines a forked test context that allows continuing testing from a verified state.
 *
 * This interface extends GivenDsl to provide full testing capabilities within a forked
 * scenario, allowing additional command executions and expectations starting from
 * the verified state of a previous command execution.
 *
 * @param S the type of the aggregate state
 */
@TestDslMarker
interface ForkedVerifiedStageDsl<S : Any> : GivenDsl<S> {
    /** The verified result from the previous command execution that this fork starts from. */
    val verifiedResult: ExpectedResult<S>

    /** The state aggregate from the verified result. */
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate

    /** The root state object from the aggregate. */
    val stateRoot: S
        get() = stateAggregate.state
}

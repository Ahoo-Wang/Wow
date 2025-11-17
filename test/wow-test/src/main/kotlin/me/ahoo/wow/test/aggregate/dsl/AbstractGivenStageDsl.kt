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
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer

/**
 * Abstract base class for GivenDsl implementations that provides common functionality.
 *
 * This class implements the core GivenDsl interface methods and provides a foundation
 * for concrete implementations. It handles service injection, owner ID management,
 * and coordinates the setup of test scenarios with events or state.
 *
 * @param S the state type of the aggregate
 */
abstract class AbstractGivenStageDsl<S : Any> :
    AbstractDynamicTestBuilder(),
    Decorator<GivenStage<S>>,
    GivenDsl<S>,
    Named {
    /**
     * The name identifier for this test scenario.
     * Used in dynamic test naming and reporting.
     */
    final override var name: String = ""
        private set

    /**
     * Sets the name for this test scenario.
     *
     * This method assigns a descriptive name to the current test scenario, which will be
     * used in test reporting and dynamic test naming to provide better identification
     * of test cases.
     *
     * @param name the descriptive name for the test scenario
     */
    override fun name(name: String) {
        this.name = name
    }

    /**
     * Injects services into the test service provider.
     *
     * This method allows configuration of mock implementations, test-specific services,
     * or dependency overrides that will be available to the aggregate during command
     * execution. The injected services are merged with the existing service provider.
     *
     * @param inject lambda function that configures the service provider with test dependencies
     */
    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    /**
     * Sets the owner ID for subsequent operations.
     *
     * This method configures the owner identifier that will be used for all subsequent
     * commands and events in this test scenario. The owner ID is typically used for
     * access control and multi-user scenarios.
     *
     * @param ownerId the owner identifier to use for commands and events
     */
    override fun givenOwnerId(ownerId: String) {
        delegate.givenOwnerId(ownerId)
    }

    /**
     * Sets up the aggregate with a single initial event.
     *
     * This method initializes the aggregate state by replaying the specified domain event,
     * simulating that the event has already occurred in the aggregate's history. This is
     * useful for testing scenarios that depend on previous aggregate state changes.
     *
     * @param event the initial domain event to replay on the aggregate
     * @param block the test scenario continuation using WhenDsl for command execution
     */
    override fun givenEvent(
        event: Any,
        block: WhenDsl<S>.() -> Unit
    ) {
        givenEvent(arrayOf(event), block)
    }

    /**
     * Sets up the aggregate by replaying multiple domain events.
     *
     * This method initializes the aggregate state by replaying a sequence of domain events
     * in order, reconstructing the aggregate's state as if these events had occurred previously.
     * This enables testing of complex scenarios that require specific historical context.
     *
     * @param events array of domain events to replay in sequence (empty array for no initial events)
     * @param block the test scenario continuation using WhenDsl for command execution
     */
    override fun givenEvent(
        events: Array<out Any>,
        block: WhenDsl<S>.() -> Unit
    ) {
        val whenStage = delegate.givenEvent(*events)
        val whenDsl = DefaultWhenDsl(context, whenStage)
        block(whenDsl)

        val displayName = buildString {
            append("Given")
            append(" Events[${events.joinToString(",") { it.javaClass.simpleName }}]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(
            displayName,
            whenDsl.dynamicNodes,
        )
        dynamicNodes.add(container)
    }

    /**
     * Sets up the aggregate with a specific initial state and version.
     *
     * This method directly initializes the aggregate with the provided state object and version
     * number, bypassing event sourcing. This is useful for testing scenarios where you want to
     * start with a known state without replaying historical events.
     *
     * @param state the initial state object to set on the aggregate
     * @param version the version number to assign to the aggregate state
     * @param block the test scenario continuation using WhenDsl for command execution
     */
    override fun givenState(
        state: S,
        version: Int,
        block: WhenDsl<S>.() -> Unit
    ) {
        val whenStage = delegate.givenState(state, version)
        val whenDsl = DefaultWhenDsl(context, whenStage)
        block(whenDsl)
        val displayName = buildString {
            append("Given[State]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }

    /**
     * Executes a command without prior event setup.
     *
     * This method allows executing a command on an aggregate that starts with no prior events
     * or state (empty aggregate). It creates a clean slate for testing command behavior on
     * newly created aggregates.
     *
     * @param command the command object to execute on the aggregate
     * @param header optional message header containing additional context for the command
     * @param ownerId the owner identifier for the command execution
     * @param block the expectation definition using ExpectDsl for result validation
     */
    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val givenStage = delegate.givenEvent()
        val whenDsl = DefaultWhenDsl(context, givenStage)
        whenDsl.whenCommand(command, header, ownerId, block)
        val displayName = buildString {
            append("Given[Empty]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}

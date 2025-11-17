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
     * @param name the descriptive name for the test
     */
    override fun name(name: String) {
        this.name = name
    }

    /**
     * Injects services into the test service provider.
     *
     * @param inject lambda that configures the service provider with test dependencies
     */
    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    /**
     * Sets the owner ID for subsequent operations.
     *
     * @param ownerId the owner identifier to use for commands and events
     */
    override fun givenOwnerId(ownerId: String) {
        delegate.givenOwnerId(ownerId)
    }

    /**
     * Sets up the aggregate with a single initial event.
     *
     * @param event the initial domain event
     * @param block the test scenario continuation using WhenDsl
     */
    override fun givenEvent(
        event: Any,
        block: WhenDsl<S>.() -> Unit
    ) {
        givenEvent(arrayOf(event), block)
    }

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
     * @param state the initial state of the aggregate
     * @param version the version number of the initial state
     * @param block the test scenario continuation using WhenDsl
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
     * @param command the command to execute
     * @param header message header for the command
     * @param ownerId the owner identifier for the command
     * @param block the expectation definition using ExpectDsl
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

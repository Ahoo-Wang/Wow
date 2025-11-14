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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import me.ahoo.wow.test.saga.stateless.WhenStage
import org.junit.jupiter.api.DynamicContainer

/**
 * Default implementation of [WhenDsl] that creates dynamic test containers for saga scenarios.
 *
 * This class wraps a [WhenStage] and provides methods to configure event processing
 * and create dynamic test containers that group related expectation tests.
 * It supports naming test scenarios for better organization.
 *
 * @param T The type of the saga being tested.
 * @property delegate The underlying when stage that handles event processing.
 * @property name The name of the test scenario (can be set via [name] method).
 */
class DefaultWhenDsl<T : Any>(
    override val delegate: WhenStage<T>
) : AbstractDynamicTestBuilder(),
    Decorator<WhenStage<T>>,
    WhenDsl<T>,
    Named {
    override var name: String = ""
        private set

    /**
     * Sets the name for this test scenario.
     *
     * The name will be included in the dynamic test container's display name
     * to help identify and organize test scenarios.
     *
     * @param name The descriptive name for the test scenario.
     */
    override fun name(name: String) {
        this.name = name
    }

    /**
     * Injects services into the underlying when stage.
     *
     * @param inject A lambda function that configures services on the [ServiceProvider].
     */
    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    /**
     * Sets a filter for message functions in the underlying when stage.
     *
     * @param filter A predicate function that determines which message functions to include.
     */
    override fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean) {
        delegate.functionFilter(filter)
    }

    /**
     * Defines an event processing scenario and sets up expectations.
     *
     * This method processes the given event through the saga, executes the
     * expectation block, and creates a dynamic test container with a descriptive
     * name that includes the event type and optional scenario name.
     *
     * @param event The domain event to process.
     * @param state Optional state to provide to state-aware saga functions.
     * @param ownerId The owner ID for the event processing.
     * @param block The expectation block that defines assertions on the saga results.
     */
    override fun whenEvent(
        event: Any,
        state: Any?,
        ownerId: String,
        block: ExpectDsl<T>.() -> Unit
    ) {
        val expectStage = delegate.whenEvent(event, state, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When ")
            if (state != null) {
                append("State")
            }
            append(" Event")
            append("[${event.javaClass.simpleName}]")
            appendName(name)
        }
        val dynamicTest = DynamicContainer.dynamicContainer(displayName, expectDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}

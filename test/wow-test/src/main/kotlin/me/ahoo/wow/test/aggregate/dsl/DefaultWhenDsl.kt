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
import me.ahoo.wow.test.aggregate.WhenStage
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer

/**
 * Default implementation of the WhenDsl interface for defining command execution in tests.
 *
 * This class provides functionality to specify commands to be executed against an aggregate
 * and define expectations for the results. It manages test naming and coordinates
 * the transition from command execution to expectation verification.
 *
 * @param S the state type of the aggregate
 * @property delegate the underlying WhenStage that handles command execution
 */
class DefaultWhenDsl<S : Any>(
    override val context: AggregateDslContext<S>,
    private val delegate: WhenStage<S>
) : AbstractDynamicTestBuilder(),
    WhenDsl<S>,
    Named {
    /**
     * The name identifier for this test scenario.
     * Used in test reporting and dynamic test naming.
     */
    override var name: String = ""
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
     * Executes a command and defines expectations for the result.
     *
     * This method processes the given command through the aggregate and allows
     * specification of expected outcomes using the ExpectDsl.
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
        val expectStage = delegate.whenCommand(command, header, ownerId)
        val expectDsl = DefaultExpectDsl(context, expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When[${command.javaClass.simpleName}]")
            appendName(name)
        }
        val dynamicTest = DynamicContainer.dynamicContainer(displayName, expectDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}

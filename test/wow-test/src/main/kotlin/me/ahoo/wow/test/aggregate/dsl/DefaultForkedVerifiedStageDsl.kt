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
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer

/**
 * Default implementation of the ForkedVerifiedStageDsl interface for forked test scenarios.
 *
 * This class provides functionality for testing alternative paths after an aggregate
 * has been verified, allowing branching of test execution to explore different outcomes
 * or error conditions from the same verified state.
 *
 * @param S the state type of the aggregate
 * @property delegate the underlying VerifiedStage that provides the verified result
 */
class DefaultForkedVerifiedStageDsl<S : Any>(
    override val context: AggregateDslContext<S>,
    override val delegate: VerifiedStage<S>
) : AbstractGivenStageDsl<S>(),
    ForkedVerifiedStageDsl<S> {
    /**
     * The verified result from the parent test execution.
     * This contains the final state, events, and any errors from the command processing.
     */
    override val verifiedResult: ExpectedResult<S>
        get() = delegate.verifiedResult

    /**
     * Executes a command in the context of the verified stage.
     *
     * This method allows testing additional commands using the state that resulted
     * from previous verification, enabling complex multi-step test scenarios.
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
            append("Given[Verified Stage]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}

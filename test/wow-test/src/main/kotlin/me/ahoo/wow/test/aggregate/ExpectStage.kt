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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import org.assertj.core.error.MultipleAssertionsError
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.function.Consumer

interface ExpectStage<S : Any> : AggregateExpecter<S, ExpectStage<S>> {
    fun verify(): VerifiedStage<S> {
        return verify(true)
    }

    /**
     * 完成流程编排后，执行验证逻辑.
     */
    fun verify(immediately: Boolean): VerifiedStage<S>
}

internal class DefaultExpectStage<C : Any, S : Any>(
    private val metadata: AggregateMetadata<C, S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider,
    private val expectedResultMono: Mono<ExpectedResult<S>>
) : ExpectStage<S> {

    private val expectStates: MutableList<Consumer<ExpectedResult<S>>> = mutableListOf()

    private val cachedVerifiedStage: VerifiedStage<S> by lazy<VerifiedStage<S>>(this) {
        lateinit var expectedResult: ExpectedResult<S>
        val expectErrors = mutableListOf<AssertionError>()
        expectedResultMono
            .test()
            .consumeNextWith {
                verifyStateAggregateSerializable(it.stateAggregate)
                expectedResult = it
                for (expectState in expectStates) {
                    try {
                        expectState.accept(it)
                    } catch (e: AssertionError) {
                        expectErrors.add(e)
                    }
                }
            }
            .verifyComplete()
        DefaultVerifiedStage(
            verifiedResult = expectedResult,
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
            assertionErrors = expectErrors
        )
    }

    override fun expect(expected: ExpectedResult<S>.() -> Unit): ExpectStage<S> {
        expectStates.add(expected)
        return this
    }

    override fun verify(immediately: Boolean): VerifiedStage<S> {
        if (immediately.not()) {
            return cachedVerifiedStage
        }
        if (cachedVerifiedStage.assertionErrors.isNotEmpty()) {
            throw MultipleAssertionsError(cachedVerifiedStage.assertionErrors)
        }
        return cachedVerifiedStage
    }
}

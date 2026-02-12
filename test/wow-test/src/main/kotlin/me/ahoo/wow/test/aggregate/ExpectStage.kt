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

/**
 * Defines the stage for specifying expectations after command execution in aggregate testing.
 *
 * This interface extends AggregateExpecter to provide methods for validating command results
 * and includes the verify method to execute the accumulated expectations.
 *
 * @param S the type of the aggregate state
 */
interface ExpectStage<S : Any> : AggregateExpecter<S, ExpectStage<S>> {
    /**
     * Executes all accumulated expectations and returns a verified stage.
     *
     * This method immediately verifies all expectations that have been set up,
     * throwing an exception if any expectations fail.
     *
     * @return a VerifiedStage containing the verified results
     * @throws AssertionError if any expectations fail
     */
    fun verify(): VerifiedStage<S> = verify(true)

    /**
     * Executes expectations based on the immediate flag.
     *
     * When immediately is true, all expectations are verified immediately.
     * When false, verification is deferred until the result is accessed.
     *
     * @param immediately whether to verify expectations immediately
     * @return a VerifiedStage containing the results
     * @throws AssertionError if immediately is true and any expectations fail
     */
    fun verify(immediately: Boolean): VerifiedStage<S>
}

/**
 * Default implementation of ExpectStage that manages expectation validation.
 *
 * This class accumulates expectations and provides lazy verification of command execution results.
 * It uses reactive streams to handle asynchronous command processing.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @property metadata metadata about the aggregate
 * @property commandAggregateFactory factory for creating command aggregates
 * @property serviceProvider provider for service dependencies
 * @property expectedResultMono reactive stream containing the expected result
 */
internal class DefaultExpectStage<C : Any, S : Any>(
    private val metadata: AggregateMetadata<C, S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider,
    private val expectedResultMono: Mono<ExpectedResult<S>>
) : ExpectStage<S> {
    /** List of accumulated expectation functions to be executed during verification. */
    private val expectStates: MutableList<Consumer<ExpectedResult<S>>> = mutableListOf()

    /**
     * Lazily initialized verified stage that executes the command and caches the result.
     *
     * This property performs the actual command execution and state verification,
     * ensuring the aggregate state can be serialized/deserialized correctly.
     */
    private val cachedVerifiedStage: VerifiedStage<S> by lazy<VerifiedStage<S>>(this) {
        lateinit var expectedResult: ExpectedResult<S>
        expectedResultMono
            .test()
            .consumeNextWith {
                verifyStateAggregateSerializable(it.stateAggregate)
                expectedResult = it
            }
            .verifyComplete()
        DefaultVerifiedStage(
            verifiedResult = expectedResult,
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider
        )
    }

    /**
     * Adds a custom expectation to the list of expectations to be verified.
     *
     * @param expected the expectation function to add
     * @return this ExpectStage for method chaining
     */
    override fun expect(expected: ExpectedResult<S>.() -> Unit): ExpectStage<S> {
        expectStates.add(expected)
        return this
    }

    /**
     * Verifies all accumulated expectations against the command execution result.
     *
     * If immediately is true, all expectations are checked and any failures are collected
     * into a MultipleAssertionsError. If false, returns the cached verified stage without
     * immediate validation.
     *
     * @param immediately whether to perform immediate validation
     * @return the VerifiedStage containing the results
     * @throws MultipleAssertionsError if immediately is true and any expectations fail
     */
    override fun verify(immediately: Boolean): VerifiedStage<S> {
        if (immediately.not()) {
            return cachedVerifiedStage
        }
        val assertionErrors = mutableListOf<AssertionError>()
        for (expectState in expectStates) {
            try {
                expectState.accept(cachedVerifiedStage.verifiedResult)
            } catch (e: AssertionError) {
                assertionErrors.add(e)
            }
        }

        if (assertionErrors.isNotEmpty()) {
            throw MultipleAssertionsError(assertionErrors)
        }
        return cachedVerifiedStage
    }
}

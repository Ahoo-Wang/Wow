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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject

/**
 * Defines the stage after command execution and expectation verification in aggregate testing.
 *
 * This interface extends both GivenStage and AggregateExpecter, allowing for
 * additional command executions or further expectations on the verified results.
 *
 * @param S the type of the aggregate state
 */
interface VerifiedStage<S : Any> :
    GivenStage<S>,
    AggregateExpecter<S, VerifiedStage<S>> {
    /** The verified result of the command execution. */
    val verifiedResult: ExpectedResult<S>

    /** The state aggregate after command execution. */
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate

    /** The root state object from the aggregate. */
    val stateRoot: S
        get() = stateAggregate.state

    /**
     * Applies additional expectations to the verified result.
     *
     * @param expected a lambda function that receives the ExpectedResult and performs assertions
     * @return this VerifiedStage for method chaining
     */
    override fun expect(expected: ExpectedResult<S>.() -> Unit): VerifiedStage<S> {
        expected(verifiedResult)
        return this
    }

    /**
     * Continues to the next stage, optionally verifying error conditions.
     *
     * @param verifyError whether to verify that an error occurred
     * @return this VerifiedStage for method chaining
     * @throws AssertionError if verifyError is true and no error occurred
     */
    fun then(verifyError: Boolean = false): VerifiedStage<S>

    /**
     * Creates a completely independent test branch context from the current state.
     *
     * This method allows testing multiple command sequences from the same verified state,
     * creating isolated copies of the aggregate and event streams.
     *
     * @param verifyError whether to verify that an error occurred before forking
     * @return a new VerifiedStage with copied state for independent testing
     */
    fun fork(verifyError: Boolean = false): VerifiedStage<S>

    /**
     * Creates a test branch and executes a handler function on it.
     *
     * This convenience method combines forking and handling in a single call,
     * useful for testing alternative scenarios from the same state.
     *
     * @param verifyError whether to verify that an error occurred before forking
     * @param handle a lambda function that receives the forked VerifiedStage and performs operations
     * @return this VerifiedStage (not the forked one) for method chaining
     */
    fun fork(
        verifyError: Boolean = false,
        handle: VerifiedStage<S>.() -> Unit
    ): VerifiedStage<S> {
        val forkedVerifiedStage = fork(verifyError)
        handle(forkedVerifiedStage)
        return this
    }
}

/**
 * Verifies that a StateAggregate can be properly serialized and deserialized.
 *
 * This function ensures that aggregate state can be correctly converted to JSON and back,
 * which is important for persistence and distributed scenarios.
 *
 * @param S the type of the aggregate state
 * @param stateAggregate the state aggregate to verify
 * @return the deserialized state aggregate (or original if not initialized)
 * @throws AssertionError if serialization/deserialization fails or objects don't match
 */
internal fun <S : Any> verifyStateAggregateSerializable(stateAggregate: StateAggregate<S>): StateAggregate<S> {
    if (!stateAggregate.initialized) {
        return stateAggregate
    }
    val serialized = stateAggregate.toJsonString()
    val deserialized = serialized.toObject<StateAggregate<S>>()
    deserialized.assert().isEqualTo(stateAggregate)
    return deserialized
}

/**
 * Default implementation of VerifiedStage for aggregate testing.
 *
 * This class provides the standard implementation for post-verification operations,
 * allowing further command executions or expectations on verified results.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @property verifiedResult the result of command execution and verification
 * @property metadata metadata about the aggregate
 * @property commandAggregateFactory factory for creating command aggregates
 * @property serviceProvider provider for service dependencies
 */
internal class DefaultVerifiedStage<C : Any, S : Any>(
    override val verifiedResult: ExpectedResult<S>,
    override val metadata: AggregateMetadata<C, S>,
    override val commandAggregateFactory: CommandAggregateFactory,
    override val serviceProvider: ServiceProvider
) : AbstractGivenStage<C, S>(),
    VerifiedStage<S> {
    /** The aggregate ID from the verified result's state aggregate. */
    override val aggregateId: AggregateId
        get() = verifiedResult.stateAggregate.aggregateId

    /**
     * A state aggregate factory that returns the verified state aggregate.
     *
     * This factory always returns the same verified state aggregate instance,
     * allowing further operations to work with the verified state.
     */
    override val stateAggregateFactory: StateAggregateFactory
        get() = object : StateAggregateFactory {
            override fun <S : Any> create(
                metadata: StateAggregateMetadata<S>,
                aggregateId: AggregateId
            ): StateAggregate<S> {
                @Suppress("UNCHECKED_CAST")
                return verifiedResult.stateAggregate as StateAggregate<S>
            }
        }

    /**
     * Continues execution, optionally verifying error conditions.
     *
     * @param verifyError whether to check for errors before proceeding
     * @return this VerifiedStage for method chaining
     */
    override fun then(verifyError: Boolean): VerifiedStage<S> {
        verifyError(verifyError)
        return this
    }

    /**
     * Creates a forked copy of this VerifiedStage for independent testing.
     *
     * The fork includes deep copies of the state aggregate, event stream, and service provider,
     * allowing for isolated command executions without affecting the original stage.
     *
     * @param verifyError whether to verify error conditions before forking
     * @return a new VerifiedStage with copied state
     */
    override fun fork(verifyError: Boolean): VerifiedStage<S> {
        verifyError(verifyError)
        val forkedStateAggregate = verifyStateAggregateSerializable(verifiedResult.stateAggregate)
        val forkedEventStream = verifiedResult.domainEventStream?.deepCopy(DomainEventStream::class.java)
        val forkedResult = verifiedResult.copy(
            stateAggregate = forkedStateAggregate,
            domainEventStream = forkedEventStream,
        )
        val forkedServiceProvider = serviceProvider.copy()
        val forkedCommandAggregateFactory = SimpleCommandAggregateFactory(InMemoryEventStore())
        val forkedVerifiedStage = DefaultVerifiedStage(
            verifiedResult = forkedResult,
            metadata = this.metadata,
            commandAggregateFactory = forkedCommandAggregateFactory,
            serviceProvider = forkedServiceProvider,
        )
        return forkedVerifiedStage
    }

    /**
     * Verifies error conditions based on the verifyError flag.
     *
     * @param verifyError if true, throws an exception if an error occurred in the result
     * @throws AssertionError if verifyError is true and an error exists in the result
     */
    private fun verifyError(verifyError: Boolean) {
        if (verifyError) {
            if (verifiedResult.hasError) {
                throw AssertionError(
                    "An exception[${verifiedResult.error}] occurred in the verified result.",
                    verifiedResult.error,
                )
            }
        }
    }
}

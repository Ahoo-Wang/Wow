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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.test.dsl.InjectServiceCapable

/**
 * Defines the stage for setting up test preconditions in aggregate testing.
 *
 * This interface provides methods to configure the initial state of aggregates
 * before command execution, including injecting services, setting owner IDs,
 * and providing initial events or state.
 *
 * @param S the type of the aggregate state
 */
interface GivenStage<S : Any> : InjectServiceCapable<GivenStage<S>> {

    /**
     * Sets the owner ID for the aggregate in this test.
     *
     * The owner ID is used to determine access permissions and may affect
     * command execution behavior in multi-tenant scenarios.
     *
     * @param ownerId the owner identifier to set
     * @return this GivenStage for method chaining
     */
    fun givenOwnerId(ownerId: String): GivenStage<S>
    fun givenSpaceId(spaceId: SpaceId): GivenStage<S>

    /**
     * Sets up the aggregate by replaying the given domain events.
     *
     * This method initializes the aggregate state by applying the provided events
     * in order, simulating previous command executions.
     *
     * @param events the domain events to replay on the aggregate
     * @return a WhenStage for specifying the command to execute
     */
    fun given(vararg events: Any): WhenStage<S> {
        return givenEvent(*events)
    }

    fun givenEvent(vararg events: Any): WhenStage<S>

    fun givenState(state: S, version: Int): WhenStage<S>

    fun givenState(state: StateAggregate<S>): WhenStage<S>
}

/**
 * Extension function that allows executing a command directly from the Given stage.
 *
 * This convenience method combines the given setup (with no events) and command execution
 * in a single call, useful for testing commands on empty aggregates.
 *
 * @param S the aggregate state type
 * @param command the command to execute
 * @param header optional command header (defaults to empty)
 * @param ownerId optional owner ID override (defaults to previously set owner)
 * @param spaceId optional space ID override
 * @return an ExpectStage for defining expectations
 */
fun <S : Any> GivenStage<S>.whenCommand(
    command: Any,
    header: Header = DefaultHeader.empty(),
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
): ExpectStage<S> = this.givenEvent().whenCommand(command, header, ownerId, spaceId)

/**
 * Abstract base class for GivenStage implementations.
 *
 * This class provides common functionality for setting up aggregate test preconditions,
 * including owner ID management and service injection.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 */
internal abstract class AbstractGivenStage<C : Any, S : Any> : GivenStage<S> {
    /** Where this stage's aggregate history lives and its commands run. */
    abstract val runtime: AggregateTestRuntime<C, S>

    /** Keeps the `whenCommand`s that branch from this stage independent. */
    private val siblings = SiblingHistory()

    protected var ownerId: String = OwnerId.DEFAULT_OWNER_ID
        private set

    protected var spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
        private set

    override fun inject(inject: ServiceProvider.() -> Unit): GivenStage<S> {
        inject(runtime.serviceProvider)
        return this
    }

    override fun givenOwnerId(ownerId: String): GivenStage<S> {
        this.ownerId = ownerId
        return this
    }

    override fun givenSpaceId(spaceId: SpaceId): GivenStage<S> {
        this.spaceId = spaceId
        return this
    }

    /** Appends [events] to the history as one stream at its next version (none: the history as it is). */
    override fun givenEvent(vararg events: Any): WhenStage<S> {
        val runtime = runtime
        val ownerId = ownerId
        val spaceId = spaceId
        return DefaultWhenStage(runtime = runtime, ownerId = ownerId, spaceId = spaceId, siblings = siblings) {
            it.appendGiven(events, ownerId, spaceId)
        }
    }

    override fun givenState(
        state: S,
        version: Int
    ): WhenStage<S> {
        val stateAggregate =
            runtime.metadata.toStateAggregate(
                state = state,
                version = version,
                ownerId = ownerId,
                spaceId = spaceId,
                aggregateId = runtime.aggregateId.id,
                tenantId = runtime.aggregateId.tenantId,
            )
        return givenState(stateAggregate)
    }

    /** Starts the history from [state], saved as its snapshot, in a runtime of its own. */
    override fun givenState(state: StateAggregate<S>): WhenStage<S> {
        val runtime = runtimeForGivenState()
        return DefaultWhenStage(runtime = runtime, ownerId = ownerId, spaceId = spaceId, siblings = siblings) {
            it.seedState(state)
        }
    }

    /** The runtime a given state starts from: one without history for the aggregate. */
    protected open fun runtimeForGivenState(): AggregateTestRuntime<C, S> = runtime
}

internal class DefaultGivenStage<C : Any, S : Any>(
    override val runtime: AggregateTestRuntime<C, S>
) : AbstractGivenStage<C, S>()

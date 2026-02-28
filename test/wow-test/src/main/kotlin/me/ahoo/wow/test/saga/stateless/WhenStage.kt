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

package me.ahoo.wow.test.saga.stateless

import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.test.dsl.InjectServiceCapable

/**
 * Represents the "when" stage in stateless saga testing.
 *
 * This interface provides methods to configure the event that triggers the saga
 * and set up expectations for the resulting commands. The typical flow is:
 * 1. Configure the triggering event
 * 2. Set expectations on the generated commands
 *
 * @param T The type of the saga being tested.
 */
interface WhenStage<T : Any> : InjectServiceCapable<WhenStage<T>> {

    /**
     * Sets a filter for message functions to be considered during testing.
     *
     * This allows restricting which saga functions are executed based on
     * custom criteria.
     *
     * @param filter A predicate function that determines which message functions to include.
     * @return The current stage for method chaining.
     */
    fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean): WhenStage<T>

    /**
     * Filters message functions by name.
     *
     * This is a convenience method that filters functions to only include
     * those with the specified name.
     *
     * @param functionName The name of the function to include.
     * @return The current stage for method chaining.
     */
    fun functionName(functionName: String): WhenStage<T> =
        functionFilter {
            it.name == functionName
        }

    /**
     * Triggers saga processing with a domain event and optional parameters.
     *
     * This method is the core function for initiating saga testing by providing
     * an event and optional state/owner information.
     *
     * @param event The domain event that triggers the saga.
     * @param state Optional state to provide to the saga processing.
     * @param ownerId The owner ID for the event processing.
     * @return An expectation stage to define assertions on the saga results.
     */
    fun whenEvent(
        event: Any,
        state: Any? = null,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
    ): ExpectStage<T>

    fun whenEvent(event: Any): ExpectStage<T> {
        return whenEvent(event, null, OwnerId.DEFAULT_OWNER_ID, SpaceIdCapable.DEFAULT_SPACE_ID)
    }
}

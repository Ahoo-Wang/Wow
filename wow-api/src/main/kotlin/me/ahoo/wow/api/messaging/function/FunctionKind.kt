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

package me.ahoo.wow.api.messaging.function

import me.ahoo.wow.api.messaging.TopicKind

/**
 * Defines the different kinds of functions in the messaging system.
 *
 * Function kinds categorize functions by their purpose and the type of messages
 * they handle. Each function kind is associated with a corresponding [TopicKind]
 * for message routing and processing consistency.
 *
 * @property topicKind The corresponding topic kind for this function type
 */
enum class FunctionKind(
    val topicKind: TopicKind
) {
    /**
     * Command function kind.
     *
     * Functions that handle command messages, which are imperative instructions
     * to perform actions. Commands typically modify system state and expect
     * responses or acknowledgments.
     */
    COMMAND(TopicKind.COMMAND),

    /**
     * Event sourcing function kind.
     *
     * Functions that handle event sourcing operations, typically involved in
     * rebuilding state from event streams or managing event persistence.
     */
    SOURCING(TopicKind.EVENT_STREAM),

    /**
     * Event handling function kind.
     *
     * Functions that process domain events, which are facts about past occurrences.
     * Event functions typically trigger side effects or update read models.
     */
    EVENT(TopicKind.EVENT_STREAM),

    /**
     * State event function kind.
     *
     * Functions that handle state change events, which notify about changes
     * to entity state. These functions are typically used for state synchronization
     * and reactive updates across the system.
     */
    STATE_EVENT(TopicKind.STATE_EVENT),

    /**
     * Error handling function kind.
     *
     * Functions that handle error conditions and exceptions in the messaging system.
     * These functions are used for error recovery, logging, and compensation logic.
     */
    ERROR(TopicKind.UNDEFINED)
}

/**
 * Interface for entities that are aware of their function kind.
 *
 * Classes implementing this interface can report their associated function kind,
 * enabling type-safe handling and routing based on the function category.
 * This is useful for processors, handlers, and routing components that need
 * to behave differently based on the function type.
 */
interface FunctionKindCapable {
    /**
     * The function kind associated with this entity.
     *
     * The specific kind is determined by the implementing class and indicates
     * how functions of this type should be processed or routed.
     */
    val functionKind: FunctionKind
}

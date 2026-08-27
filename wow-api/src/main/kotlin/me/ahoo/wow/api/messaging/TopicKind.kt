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

package me.ahoo.wow.api.messaging

/**
 * Defines the different kinds of topics in the messaging system.
 *
 * Topic kinds categorize topics by their purpose and behavior, helping with
 * routing, processing, and system organization. Each kind corresponds to
 * different message patterns and processing requirements.
 */
enum class TopicKind {
    /**
     * Undefined or unknown topic kind.
     *
     * Used for topics that don't fit other categories or when the kind
     * cannot be determined. Typically used for error handling or legacy topics.
     */
    UNDEFINED,

    /**
     * Command topic kind.
     *
     * Used for topics that carry command messages, which are imperative
     * instructions to perform actions. Commands typically expect a response
     * or acknowledgment and may modify system state.
     */
    COMMAND,

    /**
     * Event stream topic kind.
     *
     * Used for topics that carry event streams, representing sequences of
     * domain events that have occurred. Events are facts about past occurrences
     * and are typically used for event sourcing and reactive systems.
     */
    EVENT_STREAM,

    /**
     * State event topic kind.
     *
     * Used for topics that carry state change events, representing
     * notifications about changes to entity state. These are typically
     * used for state synchronization and reactive updates across systems.
     */
    STATE_EVENT
}

/**
 * Interface for entities that are aware of their topic kind.
 *
 * Classes implementing this interface can report their associated topic kind,
 * enabling type-safe handling and routing based on the topic category.
 * This is useful for processors, handlers, and routing components that need
 * to behave differently based on the message pattern.
 */
interface TopicKindCapable {
    /**
     * The topic kind associated with this entity.
     *
     * The specific kind is determined by the implementing class and indicates
     * how messages of this topic should be processed or routed.
     */
    val topicKind: TopicKind
}

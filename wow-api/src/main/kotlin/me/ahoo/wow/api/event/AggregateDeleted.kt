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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.annotation.Event

/**
 * Marker interface for events indicating that an aggregate has been deleted.
 *
 * This interface is implemented by domain events that signal the permanent removal
 * of an aggregate from the system. When an aggregate is deleted, this event allows
 * other components, services, and projections to react appropriately - such as
 * cleaning up related data, updating indexes, or notifying external systems.
 *
 * Aggregate deletion is typically a permanent operation that cannot be undone,
 * though some systems may support recovery through [AggregateRecovered] events.
 *
 * @see AggregateRecovered for events indicating aggregate recovery
 * @see me.ahoo.wow.api.annotation.Event for the event annotation
 *
 * @sample
 * ```kotlin
 * class OrderDeleted : AggregateDeleted {
 *     // Additional event data can be included here
 * }
 * ```
 */
@Event
interface AggregateDeleted

/**
 * Default implementation of [AggregateDeleted] for simple deletion events.
 *
 * This object can be used directly when no additional event data is needed
 * beyond the fact that an aggregate has been deleted. It serves as a
 * convenient default for basic aggregate deletion scenarios.
 */
object DefaultAggregateDeleted : AggregateDeleted

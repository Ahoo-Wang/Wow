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

/**
 * Marker interface indicating that a domain event should be ignored during state aggregation and event sourcing.
 *
 * Events implementing this interface are excluded from the normal event sourcing process,
 * meaning they do not modify the aggregate's state or version. This is useful for
 * publishing events that serve only as notifications or error signals without affecting
 * the domain model's persistent state.
 *
 * **Use Cases:**
 * Publishing failure events when business validation fails during command processing,
 * allowing downstream subscribers to react to errors without modifying aggregate state.
 *
 * **Trigger Conditions (all must be met):**
 * - Event implements [me.ahoo.wow.api.exception.ErrorInfo] (marks as error event)
 * - Event implements [IgnoreSourcing] (this interface)
 * - Event version equals 1
 *
 * **Effects:**
 * - [me.ahoo.wow.modeling.state.StateAggregate.onSourcing] skips sourcing this event and doesn't change aggregate version
 * - [me.ahoo.wow.eventsourcing.state.SendStateEventFilter] ignores uninitialized state aggregates for state event bus
 * - Aggregate snapshot processors cannot receive this state event (not stored in snapshot repository)
 *
 * @see me.ahoo.wow.api.exception.ErrorInfo for error event marking
 * @see DomainEvent for regular domain events
 */
interface IgnoreSourcing

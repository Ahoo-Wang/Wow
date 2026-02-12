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

package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.event.EventExchange
import java.util.concurrent.ConcurrentHashMap

/**
 * Exchange container for state events during message processing.
 * Provides access to the state event message and allows attaching processing attributes.
 *
 * @param S The type of the state in the state event.
 */
interface StateEventExchange<S : Any> : EventExchange<StateEventExchange<S>, StateEvent<S>>

/**
 * Simple implementation of StateEventExchange using a concurrent hash map for attributes.
 * Provides thread-safe attribute storage for message processing.
 *
 * @param S The type of the state in the state event.
 * @param message The state event message being processed.
 * @param attributes Mutable map for storing processing attributes (default: empty ConcurrentHashMap).
 */
class SimpleStateEventExchange<S : Any>(
    override val message: StateEvent<S>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : StateEventExchange<S>
